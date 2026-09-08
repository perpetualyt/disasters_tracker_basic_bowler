package dev.perpetualyt.disasters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class EndPlayerPredictor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    DisastersTrackerClient.MOD_ID
            );

    private EndPlayerPredictor() {}

    /*
     * All model choices return the same target:
     * predicted number of players left at END OF GAME.
     *
     * KNN:
     *   weighted average of historical end-player counts.
     *
     * WAVE_AVERAGE_DEATHS:
     *   current players minus the historical mean deaths for the
     *   current wave and each remaining wave.
     *
     * EXACT_COMBO_AVERAGE_DEATHS:
     *   current players minus the historical mean immediate deaths for
     *   this exact current disaster combo (pooled across historical wave
     *   positions), plus ordinary wave-average
     *   deaths for future waves that have not rolled yet.
     *
     * Exact-combo matching is order-insensitive. If the exact combo has
     * never appeared in a compatible historical row, it falls back to the
     * ordinary same-wave average for that current wave.
     *
     * DISASTER_PERCENT_CORRELATION:
     *   fits a ridge regression across the ENTIRE compatible complete
     *   dataset. The target is total deaths / original starting players.
     *   Features are the disaster identities visible up through the current
     *   wave. Separate stage-specific fits prevent future-disaster leakage.
     *
     *   Each disaster coefficient is therefore an estimated change in final
     *   mortality fraction associated with seeing that disaster, controlling
     *   for the other disasters visible at that prediction stage. The
     *   intercept absorbs average baseline/future-wave mortality.
     *
     * DISASTER_CHAT_DEATH_HISTORY:
     *   estimates each disaster independently from deaths_<disaster>.
     *
     *   wave mode ON:
     *     use attributed deaths from the same wave number, from rows where
     *     that disaster was cumulatively active/relevant in that wave.
     *
     *   wave mode HYPERSPECIFIC:
     *     first condition on the disaster's START wave, then read deaths from
     *     the target/current wave. This models a start-wave-specific trajectory.
     *
     *     Example: if live Flood started in W1, then its W3 DEDS uses
     *     historical wave_3_deaths_flood only from games where Flood started
     *     in W1. It does NOT require Flood to start in W3.
     *
     *   wave mode AUTO:
     *     ON for ordinary disasters, but this start-cohort HYPERSPECIFIC
     *     trajectory for Flood and The Floor Is Lava.
     *
     *   wave mode OFF:
     *     pool every per-wave active sample across all waves, normalize each
     *     by players alive at that wave roll, then rescale to the live count.
     *
     *     Example: if Floor Is Lava historically killed 4 of 10 players in
     *     wave 2, then when it rolls in wave 1 with 15 players alive that
     *     sample contributes an estimate of 4/10 * 15 = 6 deaths.
     *
     *   DEDS is TOTAL expected player loss during the current wave:
     *
     *       active-disaster attributed deaths
     *       + average Fall deaths for this wave
     *       + average Void deaths for this wave
     *       + average Misc deaths for this wave
     *
     *   Misc is recorded from the scoreboard-loss remainder and therefore
     *   includes quits/unattributed losses. No residual term is used.
     *
     *   predictedPlayers / END uses the same DEDS target at every stage:
     *     - R1: current W1 DEDS + average historical W2 DEDS + average
     *       historical W3 DEDS
     *     - R2: current W2 DEDS + average historical W3 DEDS
     *     - R3: current W3 DEDS
     *
     *   Each future-wave historical DEDS total is:
     *     per-wave attributed disaster deaths + Fall + Void + Misc.
     *
     *   Two-disaster interaction/synergy is intentionally NOT modeled yet;
     *   pair estimates are the sum of the two individual means.
     */
    public static Prediction predict(
            ModelDataManager.PredictionModel model,
            int waveNumber,
            Integer startingPlayers,
            Integer currentPlayers,
            String currentMap,
            String currentPlayerCountBasis,
            List<List<String>> observedWaves,
            List<List<String>> observedActiveDisastersByWave,
            List<Integer> observedWavePlayersAtRoll,
            List<String> activeDisasters,
            boolean percentageBasedAverages,
            ModelDataManager.WaveSpecificMode waveSpecificMode
    ) {
        if (model == null) {
            model = ModelDataManager.PredictionModel.KNN;
        }

        return switch (model) {
            case KNN ->
                    predictKnn(
                            waveNumber,
                            startingPlayers,
                            currentPlayers,
                            currentMap,
                            currentPlayerCountBasis,
                            observedWaves
                    );

            case WAVE_AVERAGE_DEATHS ->
                    predictWaveAverageDeaths(
                            waveNumber,
                            currentPlayers,
                            currentPlayerCountBasis,
                            percentageBasedAverages
                    );

            case EXACT_COMBO_AVERAGE_DEATHS ->
                    predictExactComboAverageDeaths(
                            waveNumber,
                            currentPlayers,
                            currentPlayerCountBasis,
                            observedWaves,
                            percentageBasedAverages
                    );

            case DISASTER_PERCENT_CORRELATION ->
                    predictDisasterPercentCorrelation(
                            waveNumber,
                            startingPlayers,
                            currentPlayers,
                            currentPlayerCountBasis,
                            observedWaves
                    );

            case DISASTER_CHAT_DEATH_HISTORY ->
                    predictDisasterChatDeathHistory(
                            waveNumber,
                            currentPlayers,
                            currentMap,
                            currentPlayerCountBasis,
                            observedWaves,
                            observedActiveDisastersByWave,
                            observedWavePlayersAtRoll,
                            activeDisasters,
                            waveSpecificMode
                    );
        };
    }

    public static PercentileResult predictedFinalSurvivorPercentile(
            Double predictedFinalSurvivors,
            String currentPlayerCountBasis
    ) {
        if (predictedFinalSurvivors == null
                || !Double.isFinite(predictedFinalSurvivors)) {
            return null;
        }

        Path file =
                ModelDataManager.getModelDatasetPath();

        if (!Files.exists(file)) {
            return null;
        }

        try {
            CsvTable table =
                    readCsv(file);

            boolean currentExcluding =
                    isExcludingBasis(currentPlayerCountBasis);

            int compatibleGames = 0;
            int strictlyLowerGames = 0;

            for (List<String> row :
                    table.rows()) {

                if (!isCompatibleCompleteRow(
                        table,
                        row,
                        currentExcluding
                )) {
                    continue;
                }

                Double historicalEnd =
                        parseDouble(
                                table.get(
                                        row,
                                        "end_players"
                                )
                        );

                if (historicalEnd == null
                        || !Double.isFinite(historicalEnd)) {
                    continue;
                }

                compatibleGames++;

                /*
                 * "Better than X% of games" means the current game's
                 * UNROUNDED predicted final survivor count is strictly higher
                 * than that historical game's ACTUAL final survivor count.
                 * Ties are not counted as better.
                 */
                if (predictedFinalSurvivors > historicalEnd) {
                    strictlyLowerGames++;
                }
            }

            if (compatibleGames == 0) {
                return null;
            }

            return new PercentileResult(
                    100.0
                            * strictlyLowerGames
                            / compatibleGames,
                    compatibleGames,
                    strictlyLowerGames
            );

        } catch (IOException exception) {
            LOGGER.error(
                    "Could not calculate predicted final-survivor percentile",
                    exception
            );
            return null;
        }
    }

    public static String modelId(
            ModelDataManager.PredictionModel model,
            boolean percentageBasedAverages
    ) {
        return modelId(
                model,
                percentageBasedAverages,
                true
        );
    }

    public static String modelId(
            ModelDataManager.PredictionModel model,
            boolean percentageBasedAverages,
            boolean waveSpecific
    ) {
        return modelId(
                model,
                percentageBasedAverages,
                waveSpecific
                        ? ModelDataManager.WaveSpecificMode.ON
                        : ModelDataManager.WaveSpecificMode.OFF
        );
    }

    public static String modelId(
            ModelDataManager.PredictionModel model,
            boolean percentageBasedAverages,
            ModelDataManager.WaveSpecificMode waveSpecificMode
    ) {
        if (model == null) {
            model = ModelDataManager.PredictionModel.KNN;
        }

        if (model
                == ModelDataManager.PredictionModel.DISASTER_CHAT_DEATH_HISTORY) {

            return chatDeathModelId(
                    waveSpecificMode
            );
        }

        if (!percentageBasedAverages
                || model == ModelDataManager.PredictionModel.KNN
                || model
                == ModelDataManager.PredictionModel.DISASTER_PERCENT_CORRELATION) {

            return model.modelId();
        }

        return switch (model) {
            case KNN ->
                    model.modelId();

            case WAVE_AVERAGE_DEATHS ->
                    "wave_avg_death_fraction_v1";

            case EXACT_COMBO_AVERAGE_DEATHS ->
                    "exact_history_death_fraction_v1";

            case DISASTER_PERCENT_CORRELATION ->
                    model.modelId();

            case DISASTER_CHAT_DEATH_HISTORY ->
                    chatDeathModelId(
                            waveSpecificMode
                    );
        };
    }

    private static String chatDeathModelId(
            ModelDataManager.WaveSpecificMode mode
    ) {
        ModelDataManager.WaveSpecificMode resolved =
                mode == null
                        ? ModelDataManager.WaveSpecificMode.ON
                        : mode;

        return switch (resolved) {
            case OFF ->
                    "disaster_chat_death_history_all_waves_v16";
            case ON ->
                    "disaster_chat_death_history_wave_specific_v16";
            case HYPERSPECIFIC ->
                    "disaster_chat_death_history_hyperspecific_v16";
            case MAP_HYPERSPECIFIC ->
                    "disaster_chat_death_history_hyperhyper_map_v17";
            case AUTO ->
                    "disaster_chat_death_history_auto_v16";
        };
    }

    private static final double DISASTER_PERCENT_RIDGE_LAMBDA =
            1.0;

    /*
     * Whole-dataset disaster-percentage correlation model.
     *
     * For prediction after wave N:
     *   y = total_deaths / starting_players
     *
     * x = binary indicators for disasters that had appeared through wave N.
     *
     * We fit:
     *   y ~= intercept + sum(beta_d * x_d)
     *
     * using every compatible COMPLETE game and L2/ridge regularization.
     *
     * Because the historical feature set is truncated to the same prediction
     * stage as the live game, this does not peek at future disasters.
     *
     * The intercept represents average baseline + still-unknown future-wave
     * mortality. beta_d is the percentage-of-original-players mortality
     * associated with disaster d after accounting for the other currently
     * observable disasters.
     */
    private static Prediction predictDisasterPercentCorrelation(
            int waveNumber,
            Integer startingPlayers,
            Integer currentPlayers,
            String currentPlayerCountBasis,
            List<List<String>> observedWaves
    ) {
        if (waveNumber < 1
                || waveNumber > 3
                || startingPlayers == null
                || startingPlayers <= 0
                || currentPlayers == null) {
            return null;
        }

        Path file =
                ModelDataManager
                        .getModelDatasetPath();

        if (!Files.exists(file)) {
            return null;
        }

        try {
            CsvTable table =
                    readCsv(file);

            boolean currentExcluding =
                    isExcludingBasis(
                            currentPlayerCountBasis
                    );

            List<RegressionObservation> observations =
                    new ArrayList<>();

            Set<String> vocabulary =
                    new HashSet<>();

            for (List<String> row :
                    table.rows()) {

                if (!isCompatibleCompleteRow(
                        table,
                        row,
                        currentExcluding
                )) {
                    continue;
                }

                Integer historicalStart =
                        parseInt(
                                table.get(
                                        row,
                                        "starting_players"
                                )
                        );

                Double historicalDeaths =
                        parseDouble(
                                table.get(
                                        row,
                                        "total_deaths"
                                )
                        );

                if (historicalStart == null
                        || historicalStart <= 0
                        || historicalDeaths == null) {
                    continue;
                }

                double mortalityFraction =
                        historicalDeaths
                                / historicalStart;

                Set<String> disasters =
                        historicalDisasters(
                                table,
                                row,
                                waveNumber
                        );

                vocabulary.addAll(
                        disasters
                );

                observations.add(
                        new RegressionObservation(
                                disasters,
                                mortalityFraction
                        )
                );
            }

            /*
             * A handful of rows is not enough to support a useful correlation
             * model; return no prediction rather than manufacturing one.
             */
            if (observations.size() < 5) {
                return null;
            }

            List<String> featureNames =
                    new ArrayList<>(
                            vocabulary
                    );

            featureNames.sort(
                    String::compareTo
            );

            /*
             * +1 for the intercept.
             */
            int dimensions =
                    featureNames.size() + 1;

            double[][] normal =
                    new double[dimensions][dimensions];

            double[] rhs =
                    new double[dimensions];

            Map<String, Integer> featureIndex =
                    new HashMap<>();

            for (int i = 0;
                 i < featureNames.size();
                 i++) {

                featureIndex.put(
                        featureNames.get(i),
                        i + 1
                );
            }

            for (RegressionObservation observation :
                    observations) {

                double[] x =
                        new double[dimensions];

                x[0] = 1.0;

                for (String disaster :
                        observation.disasters()) {

                    Integer index =
                            featureIndex.get(
                                    disaster
                            );

                    if (index != null) {
                        x[index] = 1.0;
                    }
                }

                for (int i = 0;
                     i < dimensions;
                     i++) {

                    rhs[i] +=
                            x[i]
                                    * observation
                                    .mortalityFraction();

                    for (int j = 0;
                         j < dimensions;
                         j++) {

                        normal[i][j] +=
                                x[i] * x[j];
                    }
                }
            }

            /*
             * Ridge regularization stabilizes rare/co-occurring disasters.
             * Do not regularize the intercept.
             */
            for (int i = 1;
                 i < dimensions;
                 i++) {

                normal[i][i] +=
                        DISASTER_PERCENT_RIDGE_LAMBDA;
            }

            double[] coefficients =
                    solveLinearSystem(
                            normal,
                            rhs
                    );

            if (coefficients == null) {
                return null;
            }

            Set<String> currentDisasters =
                    observedDisasters(
                            observedWaves,
                            waveNumber
                    );

            double predictedMortality =
                    coefficients[0];

            for (String disaster :
                    currentDisasters) {

                Integer index =
                        featureIndex.get(
                                disaster
                        );

                if (index != null) {
                    predictedMortality +=
                            coefficients[index];
                }
            }

            predictedMortality =
                    Math.max(
                            0.0,
                            Math.min(
                                    1.0,
                                    predictedMortality
                            )
                    );

            double predictedEndPlayers =
                    startingPlayers
                            * (
                            1.0
                                    - predictedMortality
                    );

            int predicted =
                    clampPrediction(
                            currentPlayers,
                            predictedEndPlayers
                    );

            return new Prediction(
                    predicted,
                    clampPredictionRaw(
                            currentPlayers,
                            predictedEndPlayers
                    ),
                    observations.size(),
                    "disaster_percent_ridge_v1",
                    false,
                    null,
                    null,
                    null,
                    Map.of(),
                    null,
                    null,
                    null,
                    Map.of(),
                    observations.size()
            );

        } catch (IOException exception) {
            LOGGER.error(
                    "Could not run disaster-percent correlation model",
                    exception
            );

            return null;
        }
    }

    /*
     * Gaussian elimination with partial pivoting for the small ridge-regression
     * normal equation system. Returns null only if the matrix is numerically
     * singular despite ridge regularization.
     */
    private static double[] solveLinearSystem(
            double[][] matrix,
            double[] vector
    ) {
        int n =
                vector.length;

        double[][] a =
                new double[n][n + 1];

        for (int row = 0;
             row < n;
             row++) {

            System.arraycopy(
                    matrix[row],
                    0,
                    a[row],
                    0,
                    n
            );

            a[row][n] =
                    vector[row];
        }

        for (int pivot = 0;
             pivot < n;
             pivot++) {

            int bestRow =
                    pivot;

            double best =
                    Math.abs(
                            a[pivot][pivot]
                    );

            for (int row = pivot + 1;
                 row < n;
                 row++) {

                double candidate =
                        Math.abs(
                                a[row][pivot]
                        );

                if (candidate > best) {
                    best = candidate;
                    bestRow = row;
                }
            }

            if (best < 1.0e-10) {
                return null;
            }

            if (bestRow != pivot) {
                double[] temp =
                        a[pivot];

                a[pivot] =
                        a[bestRow];

                a[bestRow] =
                        temp;
            }

            double divisor =
                    a[pivot][pivot];

            for (int column = pivot;
                 column <= n;
                 column++) {

                a[pivot][column] /=
                        divisor;
            }

            for (int row = 0;
                 row < n;
                 row++) {

                if (row == pivot) {
                    continue;
                }

                double factor =
                        a[row][pivot];

                if (Math.abs(factor)
                        < 1.0e-14) {
                    continue;
                }

                for (int column = pivot;
                     column <= n;
                     column++) {

                    a[row][column] -=
                            factor
                                    * a[pivot][column];
                }
            }
        }

        double[] solution =
                new double[n];

        for (int row = 0;
             row < n;
             row++) {

            solution[row] =
                    a[row][n];
        }

        return solution;
    }


    /*
     * Disaster-chat-death history model.
     *
     * Unlike the older wave-difference models, this model reads the fixed
     * deaths_<disaster> columns in live games.csv and treats those explicit
     * chat-attributed causes as the main signal.
     *
     * CURRENT WAVE
     * ------------
     * For every disaster that just rolled, estimate its expected attributed
     * deaths from previous games where that same disaster appeared at the same
     * wave number. If there are no same-wave examples, back off to that
     * disaster at any wave; if even that is unavailable, back off to the
     * generic per-disaster death distribution for this wave.
     *
     * The disaster death count is intentionally the whole-game attributed
     * total for that disaster. So if Floor Is Lava rolls now and its residual
     * lava kills somebody later, that historical death still belongs to Floor
     * Is Lava's contribution.
     *
     * FUTURE WAVES
     * ------------
     * Future disaster identities are unknown. For each future wave, average
     * the historical sum of deaths_<disaster> for the disasters that rolled
     * in that wave, excluding candidate waves containing a disaster already
     * seen in the current game because disasters do not repeat. If that filter
     * leaves no examples, use the unfiltered future-wave distribution.
     *
     * RESIDUAL
     * --------
     * Non-disaster player loss is modeled explicitly by wave from the
     * wave_N_fall, wave_N_void, and wave_N_misc CSV columns. wave_N_misc is
     * derived from scoreboard player loss and includes quits/unattributed
     * losses. There is no residual model.
     *
     * Only complete LIVE rows with chat-attribution data are used. Legacy data
     * cannot train this model because it has no per-disaster chat counts.
     */
    private static Prediction predictDisasterChatDeathHistory(
            int waveNumber,
            Integer currentPlayers,
            String currentMap,
            String currentPlayerCountBasis,
            List<List<String>> observedWaves,
            List<List<String>> observedActiveDisastersByWave,
            List<Integer> observedWavePlayersAtRoll,
            List<String> activeDisasters,
            ModelDataManager.WaveSpecificMode waveSpecificMode
    ) {
        if (waveNumber < 1
                || waveNumber > 3
                || currentPlayers == null
                || observedWaves == null
                || observedWaves.size() < waveNumber) {
            return null;
        }

        Set<String> currentActiveDisasters =
                normalizeCombo(
                        activeDisasters == null
                                ? List.of()
                                : activeDisasters
                );

        if (currentActiveDisasters.isEmpty()) {
            return null;
        }

        Path file =
                ModelDataManager
                        .getLiveGamesCsvPath();

        if (!Files.exists(file)) {
            return null;
        }

        try {
            CsvTable table =
                    readCsv(file);

            boolean currentExcluding =
                    isExcludingBasis(
                            currentPlayerCountBasis
                    );

            List<List<String>> eligibleRows =
                    new ArrayList<>();

            boolean mapHyperspecific =
                    waveSpecificMode
                            == ModelDataManager.WaveSpecificMode.MAP_HYPERSPECIFIC;

            if (mapHyperspecific
                    && (currentMap == null
                    || currentMap.isBlank())) {
                return null;
            }

            for (List<String> row :
                    table.rows()) {

                if (!isCompatibleLiveDeathRow(
                        table,
                        row,
                        currentExcluding
                )) {
                    continue;
                }

                if (mapHyperspecific
                        && !sameNormalizedMap(
                        currentMap,
                        table.get(
                                row,
                                "map"
                        )
                )) {
                    continue;
                }

                eligibleRows.add(row);
            }

            /*
             * HYPER-HYPER uses the exact same start-wave / target-wave
             * semantics as HYPER, but every row-derived Chat-DEDS component
             * (direct disaster deaths, future-wave candidates, and generic
             * Fall/Void/Misc baselines) comes only from the current map.
             * The separate MiscCorrelationModel remains globally fit because
             * per-map 20-game coefficient caches are not yet supported.
             *
             * No global minimum sample threshold. A prediction may be based
             * on one compatible historical sample; SIZE reports the actual
             * current-disaster support count.
             *
             * A disaster with zero cached direct-kill samples contributes
             * zero rather than forcing N/A.
             */
            double currentAttributedDeaths = 0.0;
            double currentVariance = 0.0;

            int currentTrainingRows =
                    Integer.MAX_VALUE;

            boolean fallbackUsed = false;

            /*
             * Diagnostic decomposition of DEDS by disasters CURRENTLY ACTIVE
             * during this prediction wave.
             *
             * A carry-over disaster remains in the CURRENT box and remains
             * part of DEDS while its scoreboard entry is active.
             *
             * wave_specific=ON learns from attributed deaths in THIS wave
             * number, not from the disaster's whole-game total.
             */
            Map<String, Double> currentDeathsByDisaster =
                    new LinkedHashMap<>();

            Map<String, Integer> currentSampleSizesByDisaster =
                    new LinkedHashMap<>();

            int minEndTrainingRows =
                    Integer.MAX_VALUE;

            for (String rawDisaster :
                    activeDisasters) {

                String disaster =
                        normalizeToken(
                                rawDisaster
                        );

                if (disaster.isBlank()
                        || currentDeathsByDisaster.containsKey(
                                disaster
                        )) {
                    continue;
                }

                /*
                 * Wave N DEDS always predicts deaths DURING Wave N.
                 *
                 * HYPER/HYPER-HYPER/AUTO additionally condition on WHEN this live
                 * disaster first started. That lets long-running hazards have
                 * different trajectories by start wave.
                 */
                int liveStartWave =
                        observedStartWave(
                                observedWaves,
                                disaster,
                                waveNumber
                        );

                if (liveStartWave == 0) {
                    /*
                     * Defensive fallback only; activeDisasters normally comes
                     * from seenDisasters, so the start wave should be known.
                     */
                    liveStartWave =
                            waveNumber;
                }

                DisasterDeathEstimate estimate =
                        estimateHistoricalDisasterDeaths(
                                table,
                                eligibleRows,
                                disaster,
                                waveNumber,
                                liveStartWave,
                                waveSpecificMode,
                                currentPlayers
                        );

                if (estimate == null
                        || estimate.rows() == 0) {

                    /*
                     * No cached direct-kill sample for this disaster is not
                     * evidence that the whole prediction is impossible.
                     * Passive/modifier disasters can legitimately have zero
                     * direct chat-attributed kills.
                     */
                    currentDeathsByDisaster.put(
                            disaster,
                            0.0
                    );
                    currentSampleSizesByDisaster.put(
                            disaster,
                            0
                    );
                    minEndTrainingRows = 0;
                    continue;
                }

                currentDeathsByDisaster.put(
                        disaster,
                        estimate.average()
                );
                currentSampleSizesByDisaster.put(
                        disaster,
                        estimate.rows()
                );
                minEndTrainingRows =
                        Math.min(
                                minEndTrainingRows,
                                estimate.rows()
                        );

                currentAttributedDeaths +=
                        estimate.average();

                currentVariance +=
                        estimate.stdev()
                                * estimate.stdev();

                currentTrainingRows =
                        Math.min(
                                currentTrainingRows,
                                estimate.rows()
                        );

                /*
                 * No cross-scope fallback: wave_specific chooses the actual
                 * sample population.
                 */
            }

            WaveCauseAverage currentOtherDeaths =
                    averageWaveCauseDeaths(
                            table,
                            eligibleRows,
                            waveNumber
                    );

            /*
             * Correlation acknowledger:
             *
             * generic wave-Misc baseline
             * + sum of the cached age-specific coefficients for every
             * disaster already seen in THIS game.
             *
             * The cache is fit in 20-game milestones and uses separate
             * disaster coefficients for age 0/1/2.
             */
            MiscCorrelationModel.Adjustment
                    currentMiscCorrelation =
                    MiscCorrelationModel
                            .adjustmentForSeenDisasters(
                                    currentPlayerCountBasis,
                                    waveNumber,
                                    observedWaves
                            );

            double currentAdjustedMisc =
                    Math.max(
                            0.0,
                            Math.min(
                                    currentPlayers,
                                    currentOtherDeaths.misc()
                                            + currentMiscCorrelation
                                            .additionalMiscDeaths()
                            )
                    );

            double currentOtherTotal =
                    currentOtherDeaths.fall()
                            + currentOtherDeaths.voidDeaths()
                            + currentAdjustedMisc;

            minEndTrainingRows =
                    Math.min(
                            minEndTrainingRows,
                            currentOtherDeaths.rows()
                    );

            if (currentMiscCorrelation
                    .usedCoefficients() > 0) {

                minEndTrainingRows =
                        Math.min(
                                minEndTrainingRows,
                                currentMiscCorrelation
                                        .minimumSupport()
                        );
            }

            double currentTotalDeaths =
                    currentAttributedDeaths
                            + currentOtherTotal;

            /*
             * END uses the same mode-aware per-wave disaster estimates.
             *
             * Current wave:
             *   currentTotalDeaths = known/seen disaster DEDS for this wave
             *                       + Fall + Void + Misc.
             *
             * Future waves are split into:
             *
             *   (A) already-seen disasters that can keep killing later;
             *       each one is projected into the future wave using the SAME
             *       OFF/ON/HYPER/HYPER-HYPER/AUTO mode. For HYPER/HYPER-HYPER/AUTO, its live start-wave
             *       cohort remains fixed.
             *
             *   (B) disasters that have not rolled yet. Their identities are
             *       unknown, so average historical NEW-disaster sets for that
             *       future wave. HYPER/HYPER-HYPER/AUTO treats each hypothetical new
             *       disaster as starting in that future wave.
             *
             *   (C) future Fall + Void + derived Misc averages.
             *
             * This matters for Flood / Floor Is Lava. A W1 Flood can have
             * near-zero W1 deaths but substantial W3 deaths; HYPER/HYPER-HYPER must retain
             * the W1-start cohort when projecting its W3 contribution.
             */
            double remainingExpectedDeaths =
                    currentTotalDeaths;

            Set<String> alreadySeen =
                    observedDisasters(
                            observedWaves,
                            waveNumber
                    );

            for (int futureWave =
                         waveNumber + 1;
                 futureWave <= 3;
                 futureWave++) {

                /*
                 * A) Carry-over deaths from disasters already seen.
                 */
                for (String disaster :
                        alreadySeen) {

                    int liveStartWave =
                            observedStartWave(
                                    observedWaves,
                                    disaster,
                                    waveNumber
                            );

                    if (liveStartWave == 0) {
                        continue;
                    }

                    DisasterDeathEstimate futureKnown =
                            estimateHistoricalDisasterDeaths(
                                    table,
                                    eligibleRows,
                                    disaster,
                                    futureWave,
                                    liveStartWave,
                                    waveSpecificMode,
                                    currentPlayers
                            );

                    if (futureKnown != null
                            && futureKnown.rows() > 0) {

                        remainingExpectedDeaths +=
                                futureKnown.average();

                        minEndTrainingRows =
                                Math.min(
                                        minEndTrainingRows,
                                        futureKnown.rows()
                                );

                    } else {
                        /*
                         * Same visible reliability convention as current DEDS:
                         * unsupported expected contribution defaults to zero,
                         * and END minimum sample support becomes 0.
                         */
                        minEndTrainingRows = 0;
                    }
                }

                /*
                 * B) Unknown disasters that START in this future wave.
                 */
                AverageResult futureNewDisasters =
                        averageFutureWaveAttributedDeaths(
                                table,
                                eligibleRows,
                                futureWave,
                                alreadySeen,
                                true,
                                waveSpecificMode,
                                currentPlayers
                        );

                if (futureNewDisasters == null
                        || futureNewDisasters.rows() == 0) {

                    futureNewDisasters =
                            averageFutureWaveAttributedDeaths(
                                    table,
                                    eligibleRows,
                                    futureWave,
                                    alreadySeen,
                                    false,
                                    waveSpecificMode,
                                    currentPlayers
                            );

                    fallbackUsed = true;
                }

                if (futureNewDisasters != null
                        && futureNewDisasters.rows() > 0) {

                    remainingExpectedDeaths +=
                            futureNewDisasters.average();

                    minEndTrainingRows =
                            Math.min(
                                    minEndTrainingRows,
                                    futureNewDisasters.rows()
                            );

                } else {
                    minEndTrainingRows = 0;
                }

                /*
                 * C) Non-disaster loss in the future wave.
                 */
                WaveCauseAverage futureOtherDeaths =
                        averageWaveCauseDeaths(
                                table,
                                eligibleRows,
                                futureWave
                        );

                /*
                 * Only disasters ALREADY SEEN in the live game are known here,
                 * so carry their cached Misc correlations forward using the
                 * correct future age bucket. Unknown future disaster identities
                 * remain represented by the ordinary future-wave Misc baseline.
                 */
                MiscCorrelationModel.Adjustment
                        futureMiscCorrelation =
                        MiscCorrelationModel
                                .adjustmentForSeenDisasters(
                                        currentPlayerCountBasis,
                                        futureWave,
                                        observedWaves
                                );

                double futureAdjustedMisc =
                        Math.max(
                                0.0,
                                Math.min(
                                        currentPlayers,
                                        futureOtherDeaths.misc()
                                            + futureMiscCorrelation
                                            .additionalMiscDeaths()
                                )
                        );

                remainingExpectedDeaths +=
                        futureOtherDeaths.fall()
                                + futureOtherDeaths.voidDeaths()
                                + futureAdjustedMisc;

                minEndTrainingRows =
                        Math.min(
                                minEndTrainingRows,
                                futureOtherDeaths.rows()
                        );

                if (futureMiscCorrelation
                        .usedCoefficients() > 0) {

                    minEndTrainingRows =
                            Math.min(
                                    minEndTrainingRows,
                                    futureMiscCorrelation
                                            .minimumSupport()
                            );
                }
            }

            double predictedEndPlayers =
                    currentPlayers
                            - remainingExpectedDeaths;

            int predicted =
                    clampPrediction(
                            currentPlayers,
                            predictedEndPlayers
                    );

            return new Prediction(
                    predicted,
                    clampPredictionRaw(
                            currentPlayers,
                            predictedEndPlayers
                    ),
                    currentTrainingRows == Integer.MAX_VALUE
                            ? 0
                            : currentTrainingRows,
                    modelId(
                            ModelDataManager.PredictionModel
                                    .DISASTER_CHAT_DEATH_HISTORY,
                            false,
                            waveSpecificMode
                    ),
                    fallbackUsed,
                    null,
                    currentTotalDeaths,
                    Math.sqrt(
                            Math.max(
                                    0.0,
                                    currentVariance
                                            + currentOtherDeaths.variance()
                            )
                    ),
                    Map.copyOf(
                            currentDeathsByDisaster
                    ),
                    currentOtherDeaths.fall(),
                    currentOtherDeaths.voidDeaths(),
                    currentAdjustedMisc,
                    Map.copyOf(
                            currentSampleSizesByDisaster
                    ),
                    minEndTrainingRows == Integer.MAX_VALUE
                            ? 0
                            : minEndTrainingRows
            );

        } catch (IOException exception) {
            LOGGER.error(
                    "Could not run disaster-chat-death history model",
                    exception
            );

            return null;
        }
    }

    private static boolean isCompatibleLiveDeathRow(
            CsvTable table,
            List<String> row,
            boolean currentExcluding
    ) {
        if (!parseBoolean(
                table.get(
                        row,
                        "complete_game"
                )
        )) {
            return false;
        }

        String historicalBasis =
                table.get(
                        row,
                        "player_count_basis"
                );

        if (isExcludingBasis(
                historicalBasis
        ) != currentExcluding) {
            return false;
        }

        /*
         * v0.9.27+ rows have fixed deaths_<disaster> columns. The migration
         * intentionally leaves those cells blank for games recorded before
         * chat-attribution existed, so require at least one nonblank fixed
         * death cell rather than treating old unknowns as zeros.
         */
        for (String disaster :
                allHistoricalDisasters(
                        table,
                        row
                )) {

            String value =
                    table.get(
                            row,
                            deathColumnForDisaster(
                                    disaster
                            )
                    );

            if (!value.isBlank()) {
                return true;
            }
        }

        return false;
    }

    private static DisasterDeathEstimate
    estimateHistoricalDisasterDeaths(
            CsvTable table,
            List<List<String>> rows,
            String normalizedDisaster,
            int waveNumber,
            int disasterStartWave,
            ModelDataManager.WaveSpecificMode waveSpecificMode,
            double targetPlayers
    ) {
        /*
         * ON:
         *   same historical wave number; disaster may have started earlier
         *   because wave_N_active_disasters is cumulative-seen.
         *
         * HYPERSPECIFIC:
         *   condition historical games on the SAME disaster START wave as the
         *   live disaster, then read deaths from the requested target wave.
         *
         *   Example: live Flood start=W1, target=W3:
         *     historical start wave must be W1
         *     value read is wave_3_deaths_flood
         *
         * AUTO:
         *   this HYPERSPECIFIC trajectory for Flood / The Floor Is Lava,
         *   ON for everything else.
         *
         * OFF:
         *   pool all per-wave active samples across W1/W2/W3, normalize by
         *   players-at-roll, then rescale to targetPlayers.
         *
         * Whole-game deaths_<disaster> is not used for DEDS.
         */
        ModelDataManager.WaveSpecificMode effectiveMode =
                effectiveWaveSpecificMode(
                        waveSpecificMode,
                        normalizedDisaster
                );

        if (effectiveMode
                == ModelDataManager.WaveSpecificMode.ON
                || effectiveMode
                == ModelDataManager.WaveSpecificMode.HYPERSPECIFIC) {

            List<Double> values =
                    effectiveMode
                            == ModelDataManager.WaveSpecificMode.HYPERSPECIFIC
                            ? collectWaveDeathsForStartCohort(
                                    table,
                                    rows,
                                    normalizedDisaster,
                                    waveNumber,
                                    disasterStartWave
                            )
                            : collectWaveDisasterDeathValues(
                                    table,
                                    rows,
                                    normalizedDisaster,
                                    waveNumber
                            );

            if (values.isEmpty()) {
                return null;
            }

            AverageResult result =
                    averageValues(values);

            return new DisasterDeathEstimate(
                    result.average(),
                    result.stdev(),
                    result.rows(),
                    0
            );
        }

        if (targetPlayers <= 0.0) {
            return new DisasterDeathEstimate(
                    0.0,
                    0.0,
                    0,
                    0
            );
        }

        List<Double> mortalityFractions =
                collectAllWaveDisasterDeathFractions(
                        table,
                        rows,
                        normalizedDisaster
                );

        if (mortalityFractions.isEmpty()) {
            return null;
        }

        AverageResult fractionResult =
                averageValues(
                        mortalityFractions
                );

        return new DisasterDeathEstimate(
                fractionResult.average()
                        * targetPlayers,
                fractionResult.stdev()
                        * targetPlayers,
                fractionResult.rows(),
                0
        );
    }

    private static List<Double>
    collectWaveDisasterDeathValues(
            CsvTable table,
            List<List<String>> rows,
            String normalizedDisaster,
            int waveNumber
    ) {
        List<Double> values =
                new ArrayList<>();

        for (List<String> row :
                rows) {

            if (!historicalActiveWaveDisasters(
                    table,
                    row,
                    waveNumber
            ).contains(
                    normalizedDisaster
            )) {
                continue;
            }

            Double value =
                    parseDouble(
                            table.get(
                                    row,
                                    waveDisasterDeathColumn(
                                            waveNumber,
                                            normalizedDisaster
                                    )
                            )
                    );

            if (value != null) {
                values.add(value);
            }
        }

        return values;
    }

    private static List<Double>
    collectWaveDeathsForStartCohort(
            CsvTable table,
            List<List<String>> rows,
            String normalizedDisaster,
            int targetWaveNumber,
            int requiredStartWave
    ) {
        List<Double> values =
                new ArrayList<>();

        if (requiredStartWave < 1
                || requiredStartWave > 3
                || targetWaveNumber < requiredStartWave
                || targetWaveNumber > 3) {

            return values;
        }

        for (List<String> row :
                rows) {

            if (historicalStartWave(
                    table,
                    row,
                    normalizedDisaster
            ) != requiredStartWave) {
                continue;
            }

            /*
             * The START-wave filter and DEATH-wave target are intentionally
             * separate dimensions.
             *
             * A W1-start Flood can contribute a W3 training value here via
             * wave_3_deaths_flood.
             */
            if (!historicalActiveWaveDisasters(
                    table,
                    row,
                    targetWaveNumber
            ).contains(
                    normalizedDisaster
            )) {
                continue;
            }

            Double value =
                    parseDouble(
                            table.get(
                                    row,
                                    waveDisasterDeathColumn(
                                            targetWaveNumber,
                                            normalizedDisaster
                                    )
                            )
                    );

            if (value != null) {
                values.add(
                        value
                );
            }
        }

        return values;
    }

    private static ModelDataManager.WaveSpecificMode
    effectiveWaveSpecificMode(
            ModelDataManager.WaveSpecificMode configuredMode,
            String normalizedDisaster
    ) {
        ModelDataManager.WaveSpecificMode mode =
                configuredMode == null
                        ? ModelDataManager.WaveSpecificMode.ON
                        : configuredMode;

        if (mode
                == ModelDataManager.WaveSpecificMode.MAP_HYPERSPECIFIC) {
            /*
             * Row filtering to the live map happens once at the top of the
             * Chat-DEDS predictor. Within that map-specific pool, direct
             * disaster trajectories use ordinary HYPERSPECIFIC semantics.
             */
            return ModelDataManager.WaveSpecificMode.HYPERSPECIFIC;
        }

        if (mode != ModelDataManager.WaveSpecificMode.AUTO) {
            return mode;
        }

        return isAutoHyperspecificDisaster(
                normalizedDisaster
        )
                ? ModelDataManager.WaveSpecificMode.HYPERSPECIFIC
                : ModelDataManager.WaveSpecificMode.ON;
    }

    private static boolean isAutoHyperspecificDisaster(
            String normalizedDisaster
    ) {
        String normalized =
                normalizeToken(
                        normalizedDisaster
                );

        return "flood".equals(
                normalized
        )
                || "the_floor_is_lava".equals(
                normalized
        )
                || "floor_is_lava".equals(
                normalized
        );
    }

    private static List<Double>
    collectAllWaveDisasterDeathFractions(
            CsvTable table,
            List<List<String>> rows,
            String normalizedDisaster
    ) {
        List<Double> fractions =
                new ArrayList<>();

        for (List<String> row :
                rows) {

            for (int wave = 1;
                 wave <= 3;
                 wave++) {

                if (!historicalActiveWaveDisasters(
                        table,
                        row,
                        wave
                ).contains(
                        normalizedDisaster
                )) {
                    continue;
                }

                Double deaths =
                        parseDouble(
                                table.get(
                                        row,
                                        waveDisasterDeathColumn(
                                                wave,
                                                normalizedDisaster
                                        )
                                )
                        );

                Integer playersAtRoll =
                        historicalPlayersAtRoll(
                                table,
                                row,
                                wave
                        );

                if (deaths == null
                        || playersAtRoll == null
                        || playersAtRoll <= 0) {
                    continue;
                }

                fractions.add(
                        deaths / playersAtRoll
                );
            }
        }

        return fractions;
    }

    private static Set<String> historicalActiveWaveDisasters(
            CsvTable table,
            List<String> row,
            int waveNumber
    ) {
        return parseCombo(
                table.get(
                        row,
                        "wave_"
                                + waveNumber
                                + "_active_disasters"
                )
        );
    }

    private static String waveDisasterDeathColumn(
            int waveNumber,
            String normalizedDisaster
    ) {
        return "wave_"
                + waveNumber
                + "_deaths_"
                + normalizeToken(
                        normalizedDisaster
                );
    }

    private static Integer historicalPlayersAtRoll(
            CsvTable table,
            List<String> row,
            int waveNumber
    ) {
        String modelColumn =
                "wave_"
                        + waveNumber
                        + "_players_at_roll_model";

        String rawColumn =
                "wave_"
                        + waveNumber
                        + "_players_at_roll";

        return parseInt(
                firstNonBlankCell(
                        table.get(
                                row,
                                modelColumn
                        ),
                        table.get(
                                row,
                                rawColumn
                        )
                )
        );
    }

    private static AverageResult
    averageHistoricalWaveTotalDeds(
            CsvTable table,
            List<List<String>> rows,
            int waveNumber
    ) {
        List<Double> totals =
                new ArrayList<>();

        for (List<String> row :
                rows) {

            Set<String> activeDisasters =
                    historicalActiveWaveDisasters(
                            table,
                            row,
                            waveNumber
                    );

            if (activeDisasters.isEmpty()) {
                continue;
            }

            double disasterDeaths = 0.0;
            boolean completeDisasterData = true;

            for (String disaster :
                    activeDisasters) {

                Double value =
                        parseDouble(
                                table.get(
                                        row,
                                        waveDisasterDeathColumn(
                                                waveNumber,
                                                disaster
                                        )
                                )
                        );

                if (value == null) {
                    completeDisasterData = false;
                    break;
                }

                disasterDeaths +=
                        value;
            }

            if (!completeDisasterData) {
                continue;
            }

            String prefix =
                    "wave_"
                            + waveNumber
                            + "_";

            Double fall =
                    parseDouble(
                            table.get(
                                    row,
                                    prefix + "fall"
                            )
                    );

            Double voidDeaths =
                    parseDouble(
                            table.get(
                                    row,
                                    prefix + "void"
                            )
                    );

            Double misc =
                    parseDouble(
                            table.get(
                                    row,
                                    prefix + "misc"
                            )
                    );

            if (fall == null
                    || voidDeaths == null
                    || misc == null) {
                continue;
            }

            totals.add(
                    disasterDeaths
                            + fall
                            + voidDeaths
                            + misc
            );
        }

        return averageValues(
                totals
        );
    }

    private static WaveCauseAverage
    averageWaveCauseDeaths(
            CsvTable table,
            List<List<String>> rows,
            int waveNumber
    ) {
        List<Double> fallValues =
                new ArrayList<>();
        List<Double> voidValues =
                new ArrayList<>();
        List<Double> miscValues =
                new ArrayList<>();

        String prefix =
                "wave_"
                        + waveNumber
                        + "_";

        for (List<String> row :
                rows) {

            Double fall =
                    parseDouble(
                            table.get(
                                    row,
                                    prefix + "fall"
                            )
                    );

            Double voidDeaths =
                    parseDouble(
                            table.get(
                                    row,
                                    prefix + "void"
                            )
                    );

            Double misc =
                    parseDouble(
                            table.get(
                                    row,
                                    prefix + "misc"
                            )
                    );

            /*
             * Old rows predate per-wave cause accounting and are left blank.
             * Only rows with all three explicit cause values train this
             * baseline; no residual/backfill is fabricated.
             */
            if (fall == null
                    || voidDeaths == null
                    || misc == null) {
                continue;
            }

            fallValues.add(fall);
            voidValues.add(voidDeaths);
            miscValues.add(misc);
        }

        AverageResult fallAverage =
                averageValues(fallValues);
        AverageResult voidAverage =
                averageValues(voidValues);
        AverageResult miscAverage =
                averageValues(miscValues);

        double fall =
                fallAverage == null
                        ? 0.0
                        : fallAverage.average();

        double voidDeaths =
                voidAverage == null
                        ? 0.0
                        : voidAverage.average();

        double misc =
                miscAverage == null
                        ? 0.0
                        : miscAverage.average();

        double variance = 0.0;

        if (fallAverage != null) {
            variance +=
                    fallAverage.stdev()
                            * fallAverage.stdev();
        }

        if (voidAverage != null) {
            variance +=
                    voidAverage.stdev()
                            * voidAverage.stdev();
        }

        if (miscAverage != null) {
            variance +=
                    miscAverage.stdev()
                            * miscAverage.stdev();
        }

        int supportingRows =
                Math.min(
                        fallValues.size(),
                        Math.min(
                                voidValues.size(),
                                miscValues.size()
                        )
                );

        return new WaveCauseAverage(
                fall,
                voidDeaths,
                misc,
                variance,
                supportingRows
        );
    }

    private static DisasterDeathEstimate
    estimateWholeGameDisasterDeaths(
            CsvTable table,
            List<List<String>> rows,
            String normalizedDisaster,
            int startWave,
            boolean waveSpecific,
            double targetPlayersAtStart
    ) {
        String column =
                deathColumnForDisaster(
                        normalizedDisaster
                );

        if (waveSpecific) {
            List<Double> values =
                    new ArrayList<>();

            for (List<String> row :
                    rows) {

                if (!historicalWaveDisasters(
                        table,
                        row,
                        startWave
                ).contains(
                        normalizedDisaster
                )) {
                    continue;
                }

                Double deaths =
                        parseDouble(
                                table.get(
                                        row,
                                        column
                                )
                        );

                if (deaths != null) {
                    values.add(deaths);
                }
            }

            if (values.isEmpty()) {
                return null;
            }

            AverageResult result =
                    averageValues(values);

            return new DisasterDeathEstimate(
                    result.average(),
                    result.stdev(),
                    result.rows(),
                    0
            );
        }

        if (targetPlayersAtStart <= 0.0) {
            return null;
        }

        List<Double> fractions =
                new ArrayList<>();

        for (List<String> row :
                rows) {

            int historicalStartWave =
                    historicalStartWave(
                            table,
                            row,
                            normalizedDisaster
                    );

            if (historicalStartWave == 0) {
                continue;
            }

            Double deaths =
                    parseDouble(
                            table.get(
                                    row,
                                    column
                            )
                    );

            Integer playersAtRoll =
                    historicalPlayersAtRoll(
                            table,
                            row,
                            historicalStartWave
                    );

            if (deaths == null
                    || playersAtRoll == null
                    || playersAtRoll <= 0) {
                continue;
            }

            fractions.add(
                    deaths / playersAtRoll
            );
        }

        if (fractions.isEmpty()) {
            return null;
        }

        AverageResult result =
                averageValues(fractions);

        return new DisasterDeathEstimate(
                result.average()
                        * targetPlayersAtStart,
                result.stdev()
                        * targetPlayersAtStart,
                result.rows(),
                0
        );
    }

    private static int historicalStartWave(
            CsvTable table,
            List<String> row,
            String normalizedDisaster
    ) {
        for (int wave = 1;
             wave <= 3;
             wave++) {

            if (historicalWaveDisasters(
                    table,
                    row,
                    wave
            ).contains(
                    normalizedDisaster
            )) {
                return wave;
            }
        }

        return 0;
    }

    private static int observedStartWave(
            List<List<String>> observedWaves,
            String normalizedDisaster,
            int throughWave
    ) {
        if (observedWaves == null) {
            return 0;
        }

        int limit =
                Math.min(
                        throughWave,
                        observedWaves.size()
                );

        for (int wave = 1;
             wave <= limit;
             wave++) {

            for (String raw :
                    observedWaves.get(
                            wave - 1
                    )) {

                if (normalizeToken(
                        raw
                ).equals(
                        normalizedDisaster
                )) {
                    return wave;
                }
            }
        }

        return 0;
    }

    private static double observedPlayersAtRoll(
            List<Integer> observedWavePlayersAtRoll,
            int waveNumber,
            double fallback
    ) {
        if (observedWavePlayersAtRoll == null
                || waveNumber < 1
                || waveNumber > observedWavePlayersAtRoll.size()) {
            return fallback;
        }

        Integer value =
                observedWavePlayersAtRoll.get(
                        waveNumber - 1
                );

        return value == null
                || value <= 0
                ? fallback
                : value;
    }

    private static boolean wasObservedActive(
            List<List<String>> observedActiveDisastersByWave,
            String normalizedDisaster,
            int waveNumber
    ) {
        if (observedActiveDisastersByWave == null
                || waveNumber < 1
                || waveNumber > observedActiveDisastersByWave.size()) {
            return false;
        }

        for (String raw :
                observedActiveDisastersByWave.get(
                        waveNumber - 1
                )) {

            if (normalizeToken(
                    raw
            ).equals(
                    normalizedDisaster
            )) {
                return true;
            }
        }

        return false;
    }

    private static AverageResult
    averageFutureWaveWholeGameDisasterDeaths(
            CsvTable table,
            List<List<String>> rows,
            int waveNumber,
            Set<String> alreadyObserved,
            boolean enforceNoRepeat
    ) {
        List<Double> totals =
                new ArrayList<>();

        for (List<String> row :
                rows) {

            Set<String> newlyStarted =
                    historicalWaveDisasters(
                            table,
                            row,
                            waveNumber
                    );

            if (newlyStarted.isEmpty()) {
                continue;
            }

            if (enforceNoRepeat
                    && intersects(
                            newlyStarted,
                            alreadyObserved
                    )) {
                continue;
            }

            double total = 0.0;
            boolean hasAnyValue = false;

            for (String disaster :
                    newlyStarted) {

                Double deaths =
                        parseDouble(
                                table.get(
                                        row,
                                        deathColumnForDisaster(
                                                disaster
                                        )
                                )
                        );

                if (deaths == null) {
                    /*
                     * Unknown/old direct-attribution value. Treat this
                     * disaster as zero rather than invalidating the whole
                     * future wave candidate.
                     */
                    continue;
                }

                total += deaths;
                hasAnyValue = true;
            }

            if (hasAnyValue
                    || !newlyStarted.isEmpty()) {
                totals.add(total);
            }
        }

        return averageValues(totals);
    }

    private static AverageResult
    averageFutureWaveAttributedDeaths(
            CsvTable table,
            List<List<String>> rows,
            int waveNumber,
            Set<String> alreadySeen,
            boolean enforceNoRepeat,
            ModelDataManager.WaveSpecificMode waveSpecificMode,
            double targetPlayers
    ) {
        List<Double> totals =
                new ArrayList<>();

        for (List<String> row :
                rows) {

            Set<String> disasters =
                    historicalWaveDisasters(
                            table,
                            row,
                            waveNumber
                    );

            if (disasters.isEmpty()) {
                continue;
            }

            if (enforceNoRepeat
                    && intersects(
                            disasters,
                            alreadySeen
                    )) {
                continue;
            }

            /*
             * Candidate future-wave identities come from historical waves.
             * Their expected deaths are still additive independent-disaster
             * means. Pair interaction/synergy is intentionally not modeled.
             */
            double total = 0.0;

            for (String disaster :
                    disasters) {

                DisasterDeathEstimate estimate =
                        estimateHistoricalDisasterDeaths(
                                table,
                                rows,
                                disaster,
                                waveNumber,
                                /*
                                 * Candidate identities here are disasters
                                 * newly STARTING in this future wave.
                                 */
                                waveNumber,
                                waveSpecificMode,
                                targetPlayers
                        );

                if (estimate == null
                        || estimate.rows() == 0) {

                    /*
                     * Same zero-default rule for future candidate disasters:
                     * no cached direct-kill sample contributes zero rather
                     * than invalidating the entire candidate wave.
                     */
                    continue;
                }

                total += estimate.average();
            }

            totals.add(total);
        }

        return averageValues(totals);
    }

    private static Double
    attributedDeathsForHistoricalWaves(
            CsvTable table,
            List<String> row,
            int firstWave,
            int lastWave
    ) {
        Set<String> disasters =
                new HashSet<>();

        for (int wave = firstWave;
             wave <= lastWave;
             wave++) {

            disasters.addAll(
                    historicalWaveDisasters(
                            table,
                            row,
                            wave
                    )
            );
        }

        if (disasters.isEmpty()) {
            return null;
        }

        return attributedDeathsForDisasters(
                table,
                row,
                disasters
        );
    }

    private static Double attributedDeathsForDisasters(
            CsvTable table,
            List<String> row,
            Set<String> disasters
    ) {
        double total = 0.0;

        for (String disaster :
                disasters) {

            Double value =
                    parseDouble(
                            table.get(
                                    row,
                                    deathColumnForDisaster(
                                            disaster
                                    )
                            )
                    );

            if (value == null) {
                return null;
            }

            total += value;
        }

        return total;
    }

    private static Set<String> historicalWaveDisasters(
            CsvTable table,
            List<String> row,
            int waveNumber
    ) {
        return parseCombo(
                table.get(
                        row,
                        "wave_"
                                + waveNumber
                                + "_disasters"
                )
        );
    }

    private static Set<String> allHistoricalDisasters(
            CsvTable table,
            List<String> row
    ) {
        Set<String> disasters =
                new HashSet<>();

        for (int wave = 1;
             wave <= 3;
             wave++) {

            disasters.addAll(
                    historicalWaveDisasters(
                            table,
                            row,
                            wave
                    )
            );
        }

        return disasters;
    }

    private static String deathColumnForDisaster(
            String normalizedDisaster
    ) {
        return "deaths_"
                + normalizeToken(
                        normalizedDisaster
                );
    }

    private static boolean intersects(
            Set<String> a,
            Set<String> b
    ) {
        if (a == null
                || b == null
                || a.isEmpty()
                || b.isEmpty()) {
            return false;
        }

        for (String value :
                a) {

            if (b.contains(value)) {
                return true;
            }
        }

        return false;
    }

    private static String firstNonBlankCell(
            String first,
            String second
    ) {
        if (first != null
                && !first.isBlank()) {
            return first;
        }

        return second == null
                ? ""
                : second;
    }

    private static AverageResult averageValues(
            List<Double> values
    ) {
        if (values == null
                || values.isEmpty()) {
            return null;
        }

        double sum = 0.0;
        double squares = 0.0;

        for (Double value :
                values) {

            if (value == null) {
                continue;
            }

            sum += value;
            squares += value * value;
        }

        int rows =
                values.size();

        if (rows == 0) {
            return null;
        }

        double mean =
                sum / rows;

        double variance =
                Math.max(
                        0.0,
                        squares / rows
                                - mean * mean
                );

        return new AverageResult(
                mean,
                Math.sqrt(variance),
                rows
        );
    }

    /*
     * Existing "fancy" empirical kNN model.
     */
    private static Prediction predictKnn(
            int waveNumber,
            Integer startingPlayers,
            Integer currentPlayers,
            String currentMap,
            String currentPlayerCountBasis,
            List<List<String>> observedWaves
    ) {
        if (waveNumber < 1
                || waveNumber > 3
                || startingPlayers == null
                || currentPlayers == null) {
            return null;
        }

        Path file =
                ModelDataManager
                        .getModelDatasetPath();

        if (!Files.exists(file)) {
            return null;
        }

        try {
            CsvTable table =
                    readCsv(file);

            Set<String> currentDisasters =
                    observedDisasters(
                            observedWaves,
                            waveNumber
                    );

            boolean currentExcluding =
                    isExcludingBasis(
                            currentPlayerCountBasis
                    );

            double weightedEnd = 0.0;
            double weightSum = 0.0;
            int compatibleRows = 0;

            String wavePrefix =
                    "wave_" + waveNumber + "_";

            for (List<String> row :
                    table.rows()) {

                if (!isCompatibleCompleteRow(
                        table,
                        row,
                        currentExcluding
                )) {
                    continue;
                }

                Integer historicalPlayers =
                        parseInt(
                                table.get(
                                        row,
                                        wavePrefix
                                                + "players_at_roll"
                                )
                        );

                Integer historicalStart =
                        parseInt(
                                table.get(
                                        row,
                                        "starting_players"
                                )
                        );

                Integer historicalEnd =
                        parseInt(
                                table.get(
                                        row,
                                        "end_players"
                                )
                        );

                if (historicalPlayers == null
                        || historicalStart == null
                        || historicalEnd == null) {
                    continue;
                }

                Set<String> historicalDisasters =
                        historicalDisasters(
                                table,
                                row,
                                waveNumber
                        );

                double playerDistance =
                        Math.abs(
                                currentPlayers
                                        - historicalPlayers
                        );

                double startDistance =
                        Math.abs(
                                startingPlayers
                                        - historicalStart
                        );

                double disasterDistance =
                        jaccardDistance(
                                currentDisasters,
                                historicalDisasters
                        );

                double mapPenalty =
                        mapPenalty(
                                currentMap,
                                table.get(
                                        row,
                                        "map"
                                )
                        );

                double distance =
                        0.30 * playerDistance
                                + 0.08 * startDistance
                                + 3.00 * disasterDistance
                                + 0.35 * mapPenalty;

                double weight =
                        1.0
                                / Math.pow(
                                0.35 + distance,
                                2.0
                        );

                weightedEnd +=
                        weight * historicalEnd;

                weightSum += weight;
                compatibleRows++;
            }

            if (compatibleRows < 3
                    || weightSum <= 0.0) {
                return null;
            }

            int predicted =
                    clampPrediction(
                            currentPlayers,
                            weightedEnd / weightSum
                    );

            return new Prediction(
                    predicted,
                    clampPredictionRaw(
                            currentPlayers,
                            weightedEnd / weightSum
                    ),
                    compatibleRows,
                    "empirical_knn_v1",
                    false,
                    null,
                    null,
                    null,
                    Map.of(),
                    null,
                    null,
                    null,
                    Map.of(),
                    compatibleRows
            );

        } catch (IOException exception) {
            LOGGER.error(
                    "Could not read model dataset for kNN prediction",
                    exception
            );

            return null;
        }
    }

    /*
     * Baseline 1:
     * "average of the usual deaths this wave gets"
     *
     * To keep the overlay target as END-OF-GAME players, at wave N we
     * subtract:
     *
     *   avg deaths in wave N
     * + avg deaths in wave N+1
     * + ... through wave 3
     *
     * from the players alive when wave N rolls.
     *
     * For legacy wave 1 only, rows with a nonzero pre-wave-1 loss field
     * are skipped because the old sheet's wave-1 death accounting can
     * include those pre-wave losses.
     */
    private static Prediction predictWaveAverageDeaths(
            int waveNumber,
            Integer currentPlayers,
            String currentPlayerCountBasis,
            boolean percentageBasedAverages
    ) {
        if (waveNumber < 1
                || waveNumber > 3
                || currentPlayers == null) {
            return null;
        }

        Path file =
                ModelDataManager
                        .getModelDatasetPath();

        if (!Files.exists(file)) {
            return null;
        }

        try {
            CsvTable table =
                    readCsv(file);

            boolean currentExcluding =
                    isExcludingBasis(
                            currentPlayerCountBasis
                    );

            int minRows = Integer.MAX_VALUE;
            double predictedPlayers =
                    currentPlayers;

            for (int wave = waveNumber;
                 wave <= 3;
                 wave++) {

                AverageResult average =
                        averageWaveDeaths(
                                table,
                                wave,
                                currentExcluding,
                                percentageBasedAverages
                        );

                if (average == null
                        || average.rows() == 0) {
                    return null;
                }

                if (percentageBasedAverages) {
                    /*
                     * Average a mortality FRACTION, then apply it to the
                     * expected players entering that wave. This naturally
                     * scales future-wave deaths down as the lobby shrinks.
                     */
                    predictedPlayers *=
                            Math.max(
                                    0.0,
                                    1.0
                                            - average.average()
                            );
                } else {
                    predictedPlayers -=
                            average.average();
                }

                minRows =
                        Math.min(
                                minRows,
                                average.rows()
                        );
            }

            int predicted =
                    clampPrediction(
                            currentPlayers,
                            predictedPlayers
                    );

            return new Prediction(
                    predicted,
                    clampPredictionRaw(
                            currentPlayers,
                            predictedPlayers
                    ),
                    minRows == Integer.MAX_VALUE
                            ? 0
                            : minRows,
                    modelId(
                            ModelDataManager.PredictionModel.WAVE_AVERAGE_DEATHS,
                            percentageBasedAverages
                    ),
                    false,
                    null,
                    null,
                    null,
                    Map.of(),
                    null,
                    null,
                    null,
                    Map.of(),
                    minRows == Integer.MAX_VALUE
                            ? 0
                            : minRows
            );

        } catch (IOException exception) {
            LOGGER.error(
                    "Could not read model dataset for wave-average prediction",
                    exception
            );

            return null;
        }
    }

    /*
     * Baseline 2:
     * "average of the usual deaths this exact disaster combo gets"
     *
     * Current wave:
     *   use historical average immediate deaths for the exact combo,
     *   pooled across wave positions to get more samples.
     *
     * Future waves:
     *   their disasters are unknown, so use ordinary wave-average deaths.
     *
     * If the exact combo has zero compatible historical examples, fall
     * back to the same-wave average for the current wave.
     */
    /*
     * Exact-history model v2.
     *
     * Unlike v1, this does NOT match only the current wave's disaster combo.
     * It uses the entire observed disaster history, wave by wave.
     *
     * Example at wave 3:
     *   current history = [A+B] -> [C+D] -> [E+F]
     *
     * Historical games are first checked for an exact history match ending
     * at the CURRENT wave. If none exist, the model backs off by dropping the
     * oldest observed wave:
     *
     *   wave 3: [1,2,3] -> [2,3] -> [3] -> ordinary wave average
     *   wave 2: [1,2]   -> [2]   -> ordinary wave average
     *   wave 1: [1]     -> ordinary wave average
     *
     * MATCHING therefore means how many consecutive MOST-RECENT waves,
     * including the current wave, exactly matched the historical rows used
     * for the current-wave death average.
     *
     * SIZE is the number of historical games used in that average.
     * DEDS is that average immediate death count; STDEV is its population standard deviation.
     */
    private static Prediction predictExactComboAverageDeaths(
            int waveNumber,
            Integer currentPlayers,
            String currentPlayerCountBasis,
            List<List<String>> observedWaves,
            boolean percentageBasedAverages
    ) {
        if (waveNumber < 1
                || waveNumber > 3
                || currentPlayers == null
                || observedWaves == null
                || observedWaves.size() < waveNumber) {
            return null;
        }

        Path file =
                ModelDataManager
                        .getModelDatasetPath();

        if (!Files.exists(file)) {
            return null;
        }

        try {
            CsvTable table =
                    readCsv(file);

            boolean currentExcluding =
                    isExcludingBasis(
                            currentPlayerCountBasis
                    );

            ExactHistoryAverage currentAverage =
                    averageDeathsFromBestExactHistory(
                            table,
                            waveNumber,
                            observedWaves,
                            currentExcluding,
                            percentageBasedAverages
                    );

            boolean fallbackUsed = false;
            int exactMatchWaves = 0;
            AverageResult fallbackAverage = null;

            double deathsThisWave;
            Double deathsThisWaveStdev;
            int sampleSize;

            if (currentAverage != null
                    && currentAverage.rows() > 0) {

                deathsThisWave =
                        currentAverage.average();

                sampleSize =
                        currentAverage.rows();

                deathsThisWaveStdev =
                        currentAverage.stdev();

                exactMatchWaves =
                        currentAverage.exactMatchWaves();

            } else {
                fallbackAverage =
                        averageWaveDeaths(
                                table,
                                waveNumber,
                                currentExcluding,
                                percentageBasedAverages
                        );

                fallbackUsed = true;

                if (fallbackAverage == null
                        || fallbackAverage.rows() == 0) {
                    return null;
                }

                deathsThisWave =
                        fallbackAverage.average();

                deathsThisWaveStdev =
                        fallbackAverage.stdev();

                sampleSize =
                        fallbackAverage.rows();
            }

            double predictedPlayers =
                    currentPlayers;

            if (percentageBasedAverages) {
                /*
                 * The averages above are mortality fractions in percentage
                 * mode. DEDS/STDEV shown on screen are converted back to
                 * direct player counts using the CURRENT wave population.
                 */
                double currentFraction =
                        deathsThisWave;

                double currentFractionStdev =
                        deathsThisWaveStdev == null
                                ? 0.0
                                : deathsThisWaveStdev;

                deathsThisWave =
                        currentPlayers
                                * currentFraction;

                deathsThisWaveStdev =
                        currentPlayers
                                * currentFractionStdev;

                predictedPlayers *=
                        Math.max(
                                0.0,
                                1.0 - currentFraction
                        );
            } else {
                predictedPlayers -=
                        deathsThisWave;
            }

            /*
             * Disaster identities for future waves are unknown, so those
             * use ordinary per-wave historical averages. In percentage mode
             * each future mortality fraction is applied sequentially to the
             * expected number of players entering that future wave.
             */
            for (int wave = waveNumber + 1;
                 wave <= 3;
                 wave++) {

                AverageResult futureAverage =
                        averageWaveDeaths(
                                table,
                                wave,
                                currentExcluding,
                                percentageBasedAverages
                        );

                if (futureAverage == null
                        || futureAverage.rows() == 0) {
                    return null;
                }

                if (percentageBasedAverages) {
                    predictedPlayers *=
                            Math.max(
                                    0.0,
                                    1.0
                                            - futureAverage.average()
                            );
                } else {
                    predictedPlayers -=
                            futureAverage.average();
                }
            }

            int predicted =
                    clampPrediction(
                            currentPlayers,
                            predictedPlayers
                    );

            return new Prediction(
                    predicted,
                    clampPredictionRaw(
                            currentPlayers,
                            predictedPlayers
                    ),
                    sampleSize,
                    modelId(
                            ModelDataManager.PredictionModel.EXACT_COMBO_AVERAGE_DEATHS,
                            percentageBasedAverages
                    ),
                    fallbackUsed,
                    exactMatchWaves,
                    deathsThisWave,
                    deathsThisWaveStdev,
                    Map.of(),
                    null,
                    null,
                    null,
                    Map.of(),
                    sampleSize
            );

        } catch (IOException exception) {
            LOGGER.error(
                    "Could not read model dataset for exact-history prediction",
                    exception
            );

            return null;
        }
    }

    private static ExactHistoryAverage
    averageDeathsFromBestExactHistory(
            CsvTable table,
            int currentWaveNumber,
            List<List<String>> observedWaves,
            boolean currentExcluding,
            boolean percentageBasedAverages
    ) {
        double[] deathTotals =
                new double[4];

        double[] deathSquares =
                new double[4];

        int[] rowCounts =
                new int[4];

        String deathColumn =
                "wave_"
                        + currentWaveNumber
                        + "_deaths";

        for (List<String> row :
                table.rows()) {

            if (!isCompatibleCompleteRow(
                    table,
                    row,
                    currentExcluding
            )) {
                continue;
            }

            if (shouldSkipLegacyWaveOneDeath(
                    table,
                    row,
                    currentWaveNumber
            )) {
                continue;
            }

            Double deaths =
                    parseDouble(
                            table.get(
                                    row,
                                    deathColumn
                            )
                    );

            if (deaths == null) {
                continue;
            }

            double value = deaths;

            if (percentageBasedAverages) {
                Integer playersAtRoll =
                        parseInt(
                                table.get(
                                        row,
                                        "wave_"
                                                + currentWaveNumber
                                                + "_players_at_roll"
                                )
                        );

                if (playersAtRoll == null
                        || playersAtRoll <= 0) {
                    continue;
                }

                value =
                        deaths
                                / playersAtRoll;
            }

            int matchDepth =
                    exactObservedSuffixMatchDepth(
                            table,
                            row,
                            observedWaves,
                            currentWaveNumber
                    );

            if (matchDepth <= 0) {
                continue;
            }

            deathTotals[matchDepth] +=
                    value;

            deathSquares[matchDepth] +=
                    value * value;

            rowCounts[matchDepth]++;
        }

        for (int depth = currentWaveNumber;
             depth >= 1;
             depth--) {

            if (rowCounts[depth] > 0) {
                double mean =
                        deathTotals[depth]
                                / rowCounts[depth];

                double variance =
                        Math.max(
                                0.0,
                                deathSquares[depth]
                                        / rowCounts[depth]
                                        - mean * mean
                        );

                return new ExactHistoryAverage(
                        mean,
                        Math.sqrt(variance),
                        rowCounts[depth],
                        depth
                );
            }
        }

        return null;
    }

    private static int exactObservedSuffixMatchDepth(
            CsvTable table,
            List<String> historicalRow,
            List<List<String>> observedWaves,
            int currentWaveNumber
    ) {
        /*
         * Try the deepest history first, always ending at the current wave.
         *
         * At wave 3:
         *   depth 3 -> compare waves 1,2,3
         *   depth 2 -> compare waves 2,3
         *   depth 1 -> compare wave 3 only
         *
         * This prevents an old wave-1 match from controlling the current-wave
         * death estimate when the current disaster itself does not match.
         */
        for (int depth = currentWaveNumber;
             depth >= 1;
             depth--) {

            int firstWave =
                    currentWaveNumber
                            - depth
                            + 1;

            boolean allMatch = true;

            for (int wave = firstWave;
                 wave <= currentWaveNumber;
                 wave++) {

                Set<String> currentCombo =
                        normalizeCombo(
                                observedWaves.get(
                                        wave - 1
                                )
                        );

                Set<String> historicalCombo =
                        parseCombo(
                                table.get(
                                        historicalRow,
                                        "wave_"
                                                + wave
                                                + "_disasters"
                                )
                        );

                if (!historicalCombo.equals(
                        currentCombo
                )) {
                    allMatch = false;
                    break;
                }
            }

            if (allMatch) {
                return depth;
            }
        }

        return 0;
    }

    private static AverageResult averageWaveDeaths(
            CsvTable table,
            int waveNumber,
            boolean currentExcluding,
            boolean percentageBasedAverages
    ) {
        double total = 0.0;
        double squares = 0.0;
        int rows = 0;

        String deathColumn =
                "wave_"
                        + waveNumber
                        + "_deaths";

        for (List<String> row :
                table.rows()) {

            if (!isCompatibleCompleteRow(
                    table,
                    row,
                    currentExcluding
            )) {
                continue;
            }

            if (shouldSkipLegacyWaveOneDeath(
                    table,
                    row,
                    waveNumber
            )) {
                continue;
            }

            Double deaths =
                    parseDouble(
                            table.get(
                                    row,
                                    deathColumn
                            )
                    );

            if (deaths == null) {
                continue;
            }

            double value = deaths;

            if (percentageBasedAverages) {
                Integer playersAtRoll =
                        parseInt(
                                table.get(
                                        row,
                                        "wave_"
                                                + waveNumber
                                                + "_players_at_roll"
                                )
                        );

                if (playersAtRoll == null
                        || playersAtRoll <= 0) {
                    continue;
                }

                value =
                        deaths
                                / playersAtRoll;
            }

            total += value;
            squares += value * value;
            rows++;
        }

        if (rows == 0) {
            return null;
        }

        double mean =
                total / rows;

        double variance =
                Math.max(
                        0.0,
                        squares / rows
                                - mean * mean
                );

        return new AverageResult(
                mean,
                Math.sqrt(variance),
                rows
        );
    }

    private static boolean isCompatibleCompleteRow(
            CsvTable table,
            List<String> row,
            boolean currentExcluding
    ) {
        if (!parseBoolean(
                table.get(
                        row,
                        "complete_game"
                )
        )) {
            return false;
        }

        String historicalBasis =
                table.get(
                        row,
                        "player_count_basis"
                );

        return isExcludingBasis(
                historicalBasis
        ) == currentExcluding;
    }

    private static boolean shouldSkipLegacyWaveOneDeath(
            CsvTable table,
            List<String> row,
            int waveNumber
    ) {
        if (waveNumber != 1) {
            return false;
        }

        String source =
                table.get(
                        row,
                        "data_source"
                );

        if (!"legacy".equalsIgnoreCase(
                source
        )) {
            return false;
        }

        Integer beforeWave1 =
                parseInt(
                        table.get(
                                row,
                                "before_wave_1_losses"
                        )
                );

        return beforeWave1 != null
                && beforeWave1 != 0;
    }

    private static double clampPredictionRaw(
            int currentPlayers,
            double predicted
    ) {
        return Math.max(
                0.0,
                Math.min(
                        currentPlayers,
                        predicted
                )
        );
    }

    private static int clampPrediction(
            int currentPlayers,
            double predicted
    ) {
        return (int) Math.round(
                clampPredictionRaw(
                        currentPlayers,
                        predicted
                )
        );
    }

    /*
     * User-defined Ticket-to-Ride-style loss.
     *
     * Official route values:
     *   distance 0 1 2 3 4 5 6
     *   points   0 1 2 4 7 10 15
     *
     * Since a Disasters prediction can miss by >6 players, distances above
     * six are decomposed into as many length-6 routes as possible plus the
     * remainder.
     *
     * Final weighting:
     *   wave 1 = 1/7
     *   wave 2 = 2/7
     *   wave 3 = 4/7
     */
    public static Double ticketToRideLoss(
            List<? extends Number> predictions,
            Integer actualEndPlayers
    ) {
        if (actualEndPlayers == null
                || predictions == null
                || predictions.isEmpty()) {
            return null;
        }

        /*
         * TTR scoring uses the UNROUNDED prediction whenever one is available.
         * UI prediction boxes still show integer survivor counts.
         *
         * For fractional miss distances, interpolate linearly between the
         * neighboring Ticket-to-Ride route scores. Example:
         *
         *   distance 1.5 -> score 1.5
         *   distance 3.5 -> score 5.5
         *
         * Distances >6 keep the existing repeated-length-6 extension.
         */
        int[] weights = {1, 2, 4};

        double weightedScore = 0.0;
        int usedWeight = 0;

        int count =
                Math.min(
                        3,
                        predictions.size()
                );

        for (int i = 0;
             i < count;
             i++) {

            Number prediction =
                    predictions.get(i);

            if (prediction == null) {
                continue;
            }

            double score =
                    routeScoreFractional(
                            Math.abs(
                                    prediction.doubleValue()
                                            - actualEndPlayers
                            )
                    );

            weightedScore +=
                    weights[i] * score;

            usedWeight +=
                    weights[i];
        }

        if (usedWeight == 0) {
            return null;
        }

        return weightedScore
                / usedWeight;
    }

    public static double routeScoreFractional(
            double distance
    ) {
        if (!Double.isFinite(distance)
                || distance <= 0.0) {
            return 0.0;
        }

        int sixes =
                (int) Math.floor(
                        distance / 6.0
                );

        double remainder =
                distance
                        - sixes * 6.0;

        double score =
                sixes * 15.0;

        double[] points = {
                0.0,
                1.0,
                2.0,
                4.0,
                7.0,
                10.0,
                15.0
        };

        int lower =
                (int) Math.floor(
                        remainder
                );

        if (lower >= 6) {
            return score + 15.0;
        }

        double fraction =
                remainder
                        - lower;

        return score
                + points[lower]
                + fraction
                * (
                points[lower + 1]
                        - points[lower]
        );
    }

    public static int routeScore(
            int distance
    ) {
        return (int) Math.round(
                routeScoreFractional(
                        distance
                )
        );
    }

    private static boolean isExcludingBasis(
            String basis
    ) {
        return basis != null
                && basis.toLowerCase(
                        Locale.ROOT
                ).contains(
                        "excluding_recorder"
                );
    }

    private static boolean sameNormalizedMap(
            String currentMap,
            String historicalMap
    ) {
        if (currentMap == null
                || currentMap.isBlank()
                || historicalMap == null
                || historicalMap.isBlank()) {
            return false;
        }

        return normalizeToken(
                currentMap
        ).equals(
                normalizeToken(
                        historicalMap
                )
        );
    }

    private static double mapPenalty(
            String currentMap,
            String historicalMap
    ) {
        if (currentMap == null
                || currentMap.isBlank()
                || historicalMap == null
                || historicalMap.isBlank()) {
            return 0.0;
        }

        return normalizeToken(
                currentMap
        ).equals(
                normalizeToken(
                        historicalMap
                )
        )
                ? 0.0
                : 1.0;
    }

    private static Set<String> observedDisasters(
            List<List<String>> waves,
            int waveNumber
    ) {
        Set<String> out =
                new HashSet<>();

        int count =
                Math.min(
                        waveNumber,
                        waves.size()
                );

        for (int i = 0;
             i < count;
             i++) {

            for (String disaster :
                    waves.get(i)) {

                String normalized =
                        normalizeToken(
                                disaster
                        );

                if (!normalized.isBlank()) {
                    out.add(normalized);
                }
            }
        }

        return out;
    }

    private static Set<String> historicalDisasters(
            CsvTable table,
            List<String> row,
            int waveNumber
    ) {
        Set<String> out =
                new HashSet<>();

        for (int wave = 1;
             wave <= waveNumber;
             wave++) {

            String value =
                    table.get(
                            row,
                            "wave_"
                                    + wave
                                    + "_disasters"
                    );

            if (value.isBlank()) {
                continue;
            }

            for (String part :
                    value.split("\\|")) {

                String normalized =
                        normalizeToken(
                                part
                        );

                if (!normalized.isBlank()) {
                    out.add(normalized);
                }
            }
        }

        return out;
    }

    private static Set<String> normalizeCombo(
            List<String> disasters
    ) {
        Set<String> out =
                new HashSet<>();

        if (disasters == null) {
            return out;
        }

        for (String disaster :
                disasters) {

            String normalized =
                    normalizeToken(
                            disaster
                    );

            if (!normalized.isBlank()) {
                out.add(normalized);
            }
        }

        return out;
    }

    private static Set<String> parseCombo(
            String value
    ) {
        Set<String> out =
                new HashSet<>();

        if (value == null
                || value.isBlank()) {
            return out;
        }

        /*
         * model_dataset.csv normalizes combos to "A | B".
         */
        for (String part :
                value.split("\\|")) {

            String normalized =
                    normalizeToken(
                            part
                    );

            if (!normalized.isBlank()) {
                out.add(normalized);
            }
        }

        return out;
    }

    private static double jaccardDistance(
            Set<String> a,
            Set<String> b
    ) {
        if (a.isEmpty()
                && b.isEmpty()) {
            return 0.0;
        }

        Set<String> union =
                new HashSet<>(a);

        union.addAll(b);

        Set<String> intersection =
                new HashSet<>(a);

        intersection.retainAll(b);

        return 1.0
                - (
                (double) intersection.size()
                        / union.size()
        );
    }

    private static String normalizeToken(
            String value
    ) {
        if (value == null) {
            return "";
        }

        return value
                .toLowerCase(Locale.ROOT)
                .replaceAll(
                        "[^a-z0-9]+",
                        "_"
                )
                .replaceAll(
                        "^_+|_+$",
                        ""
                );
    }

    private static boolean parseBoolean(
            String value
    ) {
        return "true".equalsIgnoreCase(
                value == null
                        ? ""
                        : value.strip()
        );
    }

    private static Integer parseInt(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return null;
        }

        try {
            return Integer.parseInt(
                    value.strip()
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Double parseDouble(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return null;
        }

        try {
            return Double.parseDouble(
                    value.strip()
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static CsvTable readCsv(
            Path file
    ) throws IOException {
        List<String> lines =
                Files.readAllLines(
                        file,
                        StandardCharsets.UTF_8
                );

        if (lines.isEmpty()) {
            return new CsvTable(
                    Map.of(),
                    List.of()
            );
        }

        List<String> header =
                parseCsvLine(
                        lines.get(0)
                );

        Map<String, Integer> index =
                new HashMap<>();

        for (int i = 0;
             i < header.size();
             i++) {

            index.put(
                    header.get(i),
                    i
            );
        }

        List<List<String>> rows =
                new ArrayList<>();

        for (int i = 1;
             i < lines.size();
             i++) {

            if (lines.get(i).isBlank()) {
                continue;
            }

            rows.add(
                    parseCsvLine(
                            lines.get(i)
                    )
            );
        }

        return new CsvTable(
                index,
                rows
        );
    }

    private static List<String> parseCsvLine(
            String line
    ) {
        List<String> cells =
                new ArrayList<>();

        StringBuilder current =
                new StringBuilder();

        boolean inQuotes = false;

        for (int i = 0;
             i < line.length();
             i++) {

            char c =
                    line.charAt(i);

            if (c == '"') {
                if (inQuotes
                        && i + 1 < line.length()
                        && line.charAt(i + 1) == '"') {

                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }

                continue;
            }

            if (c == ','
                    && !inQuotes) {

                cells.add(
                        current.toString()
                );

                current.setLength(0);
                continue;
            }

            current.append(c);
        }

        cells.add(
                current.toString()
        );

        return cells;
    }

    public record PercentileResult(
            double percentile,
            int compatibleGames,
            int strictlyLowerGames
    ) {}

    public record Prediction(
            int predictedPlayers,
            double rawPredictedPlayers,
            int trainingRows,
            String modelId,
            boolean fallbackUsed,
            Integer exactMatchWaves,
            Double deathsThisWave,
            Double deathsThisWaveStdev,
            Map<String, Double> deathsThisWaveByDisaster,
            Double fallDeathsThisWave,
            Double voidDeathsThisWave,
            Double miscDeathsThisWave,
            Map<String, Integer> deathsThisWaveSampleSizes,
            Integer endTrainingRows
    ) {}

    private record RegressionObservation(
            Set<String> disasters,
            double mortalityFraction
    ) {}

    private record AverageResult(
            double average,
            double stdev,
            int rows
    ) {}

    private record ExactHistoryAverage(
            double average,
            double stdev,
            int rows,
            int exactMatchWaves
    ) {}

    private record WaveCauseAverage(
            double fall,
            double voidDeaths,
            double misc,
            double variance,
            int rows
    ) {
        private double total() {
            return fall
                    + voidDeaths
                    + misc;
        }
    }

    private record DisasterDeathEstimate(
            double average,
            double stdev,
            int rows,
            int fallbackLevel
    ) {}

    private record CsvTable(
            Map<String, Integer> index,
            List<List<String>> rows
    ) {
        private String get(
                List<String> row,
                String column
        ) {
            Integer position =
                    index.get(column);

            if (position == null
                    || position < 0
                    || position >= row.size()) {
                return "";
            }

            String value =
                    row.get(position);

            return value == null
                    ? ""
                    : value.strip();
        }
    }
}
