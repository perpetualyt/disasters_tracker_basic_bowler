package dev.perpetualyt.disasters;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

public final class PredictionOverlay {

    private static final Identifier HUD_ID =
            Identifier.fromNamespaceAndPath(
                    DisastersTrackerClient.MOD_ID,
                    "prediction_overlay"
            );

    private static final int LIVE_BOX_MIN_WIDTH = 28;
    private static final int LIVE_BOX_MAX_WIDTH = 116;

    /*
     * Live box height is now based on the number of visible text rows.
     * A passed wave normally has only:
     *   Wave N
     *   Guess: X
     * so it collapses to 24 px instead of retaining the full diagnostic
     * height. Width also hugs the W:/G: text instead of enforcing the old
     * 48 px minimum. The current exact-history wave still expands for
     * MATCHING/SIZE/DEDS/STDEV.
     */
    private static final int LIVE_BOX_MIN_HEIGHT = 24;
    private static final int LIVE_BOX_MAX_HEIGHT = 76;

    private static final int FINAL_BOX_MIN_WIDTH = 64;
    private static final int FINAL_BOX_MAX_WIDTH = 88;
    private static final int FINAL_BOX_HEIGHT = 34;

    private static final int DISASTER_BOX_MIN_WIDTH = 110;
    private static final int DISASTER_BOX_MAX_WIDTH = 170;

    private static final int LEADERBOARD_MIN_WIDTH = 92;
    private static final int LEADERBOARD_MAX_WIDTH = 180;

    private static final int GAP = 4;
    private static final int TOP = 8;

    private static final int BACKGROUND = 0xD01D6F42;
    private static final int OUTLINE = 0xFF55D98A;
    private static final int DEAD_BACKGROUND = 0xD07A1F1F;
    private static final int DEAD_OUTLINE = 0xFFFF5A5A;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED_TEXT = 0xFFD7FFE5;

    private static final Set<String> HIDDEN_PASSIVE_DEDS_DISASTERS =
            Set.of(
                    "half_health",
                    "blackout",
                    "swappage"
            );

    /*
     * Display-only exclusions for the top-right DEATHS panel.
     * Grounded is a status/modifier disaster and should not get its own
     * visible death row even if old/corrupt attribution data contains one.
     *
     * This does NOT remove Grounded from game state, CSV data, DEDS training,
     * cumulative-seen semantics, or disaster ordering.
     */
    private static final Set<String> HIDDEN_DEATH_OVERLAY_DISASTERS =
            Set.of(
                    "grounded"
            );

    private static final List<Guess> liveGuesses =
            new ArrayList<>();

    private static boolean recorderAlive = true;

    private static final Map<String, DisasterDeathLine> disasterDeaths =
            new LinkedHashMap<>();

    private static final Map<String, CommunityGuessLine> communityGuesses =
            new LinkedHashMap<>();

    private static List<CommunityLossLine> finalCommunityLosses =
            List.of();

    private static final long FINAL_SUMMARY_DURATION_MS = 5000L;

    private static boolean finalSummaryVisible = false;
    private static long finalSummaryShownAtMs = 0L;

    private static List<Integer> finalPredictions =
            List.of();
    private static Integer finalActual = null;
    private static Double finalLoss = null;

    private static Double currentGamePercentile = null;
    private static int currentGamePercentileSampleSize = 0;

    private PredictionOverlay() {}

    public static void initialize() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                HUD_ID,
                PredictionOverlay::render
        );
    }

    public static void resetForGame() {
        liveGuesses.clear();
        disasterDeaths.clear();
        communityGuesses.clear();
        finalCommunityLosses = List.of();

        recorderAlive = true;

        finalSummaryVisible = false;
        finalSummaryShownAtMs = 0L;
        finalPredictions = List.of();
        finalActual = null;
        finalLoss = null;
        currentGamePercentile = null;
        currentGamePercentileSampleSize = 0;
    }

    public static void setRecorderAlive(
            boolean value
    ) {
        recorderAlive = value;
    }

    public static void registerDisaster(
            String name,
            Integer colorRgb
    ) {
        registerDisaster(
                name,
                colorRgb,
                List.of()
        );
    }

    public static void registerDisaster(
            String name,
            Integer colorRgb,
            List<Integer> colorPattern
    ) {
        if (name == null || name.isBlank()) {
            return;
        }

        String key =
                name.toLowerCase(Locale.ROOT);

        disasterDeaths.putIfAbsent(
                key,
                new DisasterDeathLine(
                        name,
                        colorRgb,
                        colorPattern == null
                                ? List.of()
                                : List.copyOf(
                                        colorPattern
                                ),
                        0
                )
        );
    }

    public static void setDisasterDeathCount(
            String name,
            Integer colorRgb,
            int deaths
    ) {
        DisasterDeathLine existing =
                name == null
                        ? null
                        : disasterDeaths.get(
                                name.toLowerCase(
                                        Locale.ROOT
                                )
                        );

        setDisasterDeathCount(
                name,
                colorRgb,
                existing == null
                        ? List.of()
                        : existing.colorPattern(),
                deaths
        );
    }

    public static void setDisasterDeathCount(
            String name,
            Integer colorRgb,
            List<Integer> colorPattern,
            int deaths
    ) {
        if (name == null || name.isBlank()) {
            return;
        }

        String key =
                name.toLowerCase(Locale.ROOT);

        disasterDeaths.put(
                key,
                new DisasterDeathLine(
                        name,
                        colorRgb,
                        colorPattern == null
                                ? List.of()
                                : List.copyOf(
                                        colorPattern
                                ),
                        Math.max(
                                0,
                                deaths
                        )
                )
        );
    }

    public static void addPrediction(
            int wave,
            EndPlayerPredictor.Prediction prediction
    ) {
        liveGuesses.removeIf(
                guess -> guess.wave() == wave
        );

        liveGuesses.add(
                new Guess(
                        wave,
                        prediction == null
                                ? null
                                : prediction.predictedPlayers(),
                        prediction == null
                                ? 0
                                : prediction.trainingRows(),
                        prediction == null
                                ? null
                                : prediction.exactMatchWaves(),
                        prediction == null
                                ? null
                                : prediction.deathsThisWave(),
                        prediction == null
                                ? null
                                : prediction.deathsThisWaveStdev(),
                        prediction == null
                                || prediction.deathsThisWaveByDisaster() == null
                                ? Map.of()
                                : Map.copyOf(
                                        prediction.deathsThisWaveByDisaster()
                                ),
                        prediction == null
                                ? null
                                : prediction.fallDeathsThisWave(),
                        prediction == null
                                ? null
                                : prediction.voidDeathsThisWave(),
                        prediction == null
                                ? null
                                : prediction.miscDeathsThisWave(),
                        prediction == null
                                || prediction.deathsThisWaveSampleSizes() == null
                                ? Map.of()
                                : Map.copyOf(
                                        prediction.deathsThisWaveSampleSizes()
                                ),
                        prediction == null
                                || prediction.endTrainingRows() == null
                                ? 0
                                : prediction.endTrainingRows(),
                        prediction != null
                                && (
                                prediction.modelId()
                                        .startsWith(
                                                "exact_history_"
                                        )
                                        || prediction.modelId()
                                        .startsWith(
                                                "disaster_chat_death_history_"
                                        )
                        ),
                        prediction != null
                                && prediction.modelId()
                                .startsWith(
                                        "exact_history_"
                                )
                )
        );

        liveGuesses.sort(
                (a, b) ->
                        Integer.compare(
                                a.wave(),
                                b.wave()
                        )
        );
    }

    public static void setCommunityPrediction(
            String username,
            int wave,
            double guess
    ) {
        if (username == null
                || username.isBlank()
                || wave < 1
                || wave > 3
                || !Double.isFinite(guess)) {
            return;
        }

        String key =
                username.toLowerCase(
                        Locale.ROOT
                );

        communityGuesses.put(
                key,
                new CommunityGuessLine(
                        username,
                        wave,
                        guess
                )
        );
    }

    public static void setCommunityFinalLosses(
            Map<String, Double> losses
    ) {
        if (losses == null
                || losses.isEmpty()) {
            finalCommunityLosses =
                    List.of();
            return;
        }

        List<CommunityLossLine> lines =
                new ArrayList<>();

        for (Map.Entry<String, Double> entry :
                losses.entrySet()) {

            if (entry.getKey() == null
                    || entry.getKey().isBlank()
                    || entry.getValue() == null) {
                continue;
            }

            lines.add(
                    new CommunityLossLine(
                            entry.getKey(),
                            entry.getValue()
                    )
            );
        }

        lines.sort(
                (a, b) -> {
                    int scoreCompare =
                            Double.compare(
                                    a.loss(),
                                    b.loss()
                            );

                    if (scoreCompare != 0) {
                        return scoreCompare;
                    }

                    return a.username()
                            .compareToIgnoreCase(
                                    b.username()
                            );
                }
        );

        finalCommunityLosses =
                List.copyOf(
                        lines
                );
    }

    public static void setGamePercentile(
            EndPlayerPredictor.PercentileResult result
    ) {
        if (result == null
                || !Double.isFinite(result.percentile())
                || result.compatibleGames() <= 0) {
            currentGamePercentile = null;
            currentGamePercentileSampleSize = 0;
            return;
        }

        currentGamePercentile =
                Math.max(
                        0.0,
                        Math.min(
                                100.0,
                                result.percentile()
                        )
                );

        currentGamePercentileSampleSize =
                result.compatibleGames();
    }

    public static void showFinalSummary(
            List<Integer> predictions,
            Integer actual,
            Double loss
    ) {
        liveGuesses.clear();

        finalPredictions =
                predictions == null
                        ? List.of()
                        : new ArrayList<>(predictions);

        finalActual = actual;
        finalLoss = loss;
        finalSummaryVisible = true;
        finalSummaryShownAtMs =
                System.currentTimeMillis();
    }

    public static boolean isFinalSummaryVisible() {
        return finalSummaryVisible;
    }

    public static void clearAll() {
        liveGuesses.clear();
        disasterDeaths.clear();
        communityGuesses.clear();
        finalCommunityLosses = List.of();
        recorderAlive = true;

        finalSummaryVisible = false;
        finalSummaryShownAtMs = 0L;
        finalPredictions = List.of();
        finalActual = null;
        finalLoss = null;
        currentGamePercentile = null;
        currentGamePercentileSampleSize = 0;
    }

    private static void render(
            GuiGraphicsExtractor graphics,
            DeltaTracker deltaTracker
    ) {
        if (finalSummaryVisible) {
            long ageMs =
                    System.currentTimeMillis()
                            - finalSummaryShownAtMs;

            if (finalSummaryShownAtMs > 0
                    && ageMs >= FINAL_SUMMARY_DURATION_MS) {
                clearAll();
                return;
            }

            if (ModelDataManager.isOverlay2Enabled()) {
                renderFinalSummary(graphics);

                int statusY =
                        TOP
                                + 2 * FINAL_BOX_HEIGHT
                                + GAP
                                + 5;

                if (ModelDataManager
                        .isGamePercentileOverlayEnabled()
                        && currentGamePercentile != null) {

                    renderGamePercentile(
                            graphics,
                            statusY
                    );
                    statusY += 23;
                }

                if (ModelDataManager
                        .isRecorderAliveOverlayEnabled()) {

                    renderRecorderAliveStatus(
                            graphics,
                            statusY
                    );
                }
            }

            /*
             * Keep the final per-disaster death overlay visible for the same
             * five-second post-game window. The live R1/R2/R3 guesses are
             * represented by the final-summary boxes above.
             */
            if (ModelDataManager.isDisasterDeathsOverlayEnabled()
                    && !disasterDeaths.isEmpty()) {

                renderDisasterDeaths(
                        graphics,
                        TOP
                );
            }

            if (!finalCommunityLosses.isEmpty()) {
                renderCommunityFinalLeaderboard(
                        graphics,
                        TOP
                );
            }

            return;
        }

        boolean predictionOverlayVisible =
                ModelDataManager.isOverlay1Enabled()
                        && !liveGuesses.isEmpty();

        if (predictionOverlayVisible) {
            List<LiveBox> boxes =
                    new ArrayList<>();

            for (Guess guess :
                    liveGuesses) {

                List<String> lines =
                        new ArrayList<>();

                int sizeBreakdownLineIndex = -1;
                List<ColoredDedsSegment> sizeBreakdown =
                        List.of();

                int dedsBreakdownLineIndex = -1;
                List<ColoredDedsSegment> dedsBreakdown =
                        List.of();

                lines.add(
                        "W:" + guess.wave()
                );

                lines.add(
                        guess.predictedPlayers() == null
                                ? "G:N/A"
                                : "G:"
                                + guess.predictedPlayers()
                );

                boolean currentWave =
                        !liveGuesses.isEmpty()
                                && guess.wave()
                                == liveGuesses.get(
                                liveGuesses.size() - 1
                        ).wave();

                if (guess.deathDiagnosticsModel()
                        && currentWave) {

                    if (guess.exactHistoryModel()) {
                        lines.add(
                                "MATCHING="
                                        + (
                                        guess.exactMatchWaves() == null
                                                ? 0
                                                : guess.exactMatchWaves()
                                )
                        );
                    }

                    if (guess.exactHistoryModel()) {
                        lines.add(
                                "SIZE="
                                        + guess.sampleSize()
                        );
                    } else {
                        sizeBreakdown =
                                buildSizeBreakdown(
                                        guess
                                );

                        sizeBreakdownLineIndex =
                                lines.size();

                        lines.add(
                                plainSizeBreakdown(
                                        sizeBreakdown
                                )
                        );
                    }

                    lines.add(
                            guess.deathsThisWave() == null
                                    ? "DEDS=N/A"
                                    : String.format(
                                            Locale.ROOT,
                                            "DEDS=%.2f",
                                            guess.deathsThisWave()
                                    )
                    );

                    if (guess.deathsThisWaveByDisaster() != null
                            && !guess.deathsThisWaveByDisaster().isEmpty()) {

                        dedsBreakdown =
                                buildDedsBreakdown(
                                        guess.deathsThisWaveByDisaster()
                                );

                        if (!dedsBreakdown.isEmpty()) {
                            dedsBreakdownLineIndex =
                                    lines.size();

                            lines.add(
                                    plainDedsBreakdown(
                                            dedsBreakdown
                                    )
                            );
                        }
                    }

                    if (guess.fallDeathsThisWave() != null
                            || guess.voidDeathsThisWave() != null
                            || guess.miscDeathsThisWave() != null) {

                        double combinedMisc =
                                (guess.fallDeathsThisWave() == null
                                        ? 0.0
                                        : guess.fallDeathsThisWave())
                                        + (guess.voidDeathsThisWave() == null
                                        ? 0.0
                                        : guess.voidDeathsThisWave())
                                        + (guess.miscDeathsThisWave() == null
                                        ? 0.0
                                        : guess.miscDeathsThisWave());

                        /*
                         * Compact diagnostic requested by the experiment:
                         *
                         *   Mi = derived Misc + Void + Fall
                         *
                         * Underlying CSV/model accounting stays separate.
                         */
                        lines.add(
                                String.format(
                                        Locale.ROOT,
                                        "Mi=%.2f",
                                        combinedMisc
                                )
                        );
                    }

                    lines.add(
                            guess.deathsThisWaveStdev() == null
                                    ? "STDEV=N/A"
                                    : String.format(
                                            Locale.ROOT,
                                            "STDEV=%.2f",
                                            guess.deathsThisWaveStdev()
                                    )
                    );
                }

                boxes.add(
                        new LiveBox(
                                lines,
                                sizeBreakdownLineIndex,
                                sizeBreakdown,
                                dedsBreakdownLineIndex,
                                dedsBreakdown
                        )
                );
            }

            renderLiveBoxes(
                    graphics,
                    boxes
            );

            int tallestLiveBox =
                    boxes.stream()
                            .mapToInt(
                                    PredictionOverlay::liveBoxHeight
                            )
                            .max()
                            .orElse(
                                    LIVE_BOX_MIN_HEIGHT
                            );

            int statusY =
                    TOP
                            + tallestLiveBox
                            + 5;

            if (ModelDataManager
                    .isGamePercentileOverlayEnabled()
                    && currentGamePercentile != null) {

                renderGamePercentile(
                        graphics,
                        statusY
                );
                statusY += 23;
            }

            if (ModelDataManager
                    .isRecorderAliveOverlayEnabled()) {

                renderRecorderAliveStatus(
                        graphics,
                        statusY
                );
            }
        }

        if (!communityGuesses.isEmpty()) {
            renderCommunityLiveLeaderboard(
                    graphics,
                    TOP
            );
        }

        if (ModelDataManager.isDisasterDeathsOverlayEnabled()
                && !disasterDeaths.isEmpty()) {

            /*
             * Keep the death-attribution panel independent from the centered
             * prediction boxes. It owns the top-right corner so it stays easy
             * to read even when several prediction boxes are visible.
             */
            renderDisasterDeaths(
                    graphics,
                    TOP
            );
        }
    }


    private static int clamp(
            int value,
            int min,
            int max
    ) {
        return Math.max(
                min,
                Math.min(
                        max,
                        value
                )
        );
    }

    private static int liveBoxHeight(
            LiveBox box
    ) {
        if (box == null
                || box.lines() == null
                || box.lines().isEmpty()) {
            return LIVE_BOX_MIN_HEIGHT;
        }

        /*
         * Tight text-fit height:
         *
         *   2-line compact box (W:x / G:y) = 24 px
         *
         * The old 9 + 11*n formula left visibly excessive vertical padding
         * once the labels were shortened to W:/G:.
         */
        return clamp(
                4
                        + 10
                        * box.lines().size(),
                LIVE_BOX_MIN_HEIGHT,
                LIVE_BOX_MAX_HEIGHT
        );
    }

    private static int desiredWidth(
            Font font,
            List<String> lines,
            int minWidth,
            int maxWidth
    ) {
        int widest = 0;

        for (String line : lines) {
            widest =
                    Math.max(
                            widest,
                            font.width(line)
                    );
        }

        return clamp(
                widest + 8,
                minWidth,
                maxWidth
        );
    }

    private static void renderFinalSummary(
            GuiGraphicsExtractor graphics
    ) {
        List<FinalBox> boxes =
                new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            Integer prediction =
                    i < finalPredictions.size()
                            ? finalPredictions.get(i)
                            : null;

            boxes.add(
                    new FinalBox(
                            "Wave " + (i + 1),
                            prediction == null
                                    ? "Guess: N/A"
                                    : "Guess: " + prediction
                    )
            );
        }

        boxes.add(
                new FinalBox(
                        "Actual excl.",
                        finalActual == null
                                ? "Players: N/A"
                                : "Players: "
                                + finalActual
                )
        );

        boxes.add(
                new FinalBox(
                        "TTR loss",
                        finalLoss == null
                                ? "N/A"
                                : String.format(
                                        Locale.ROOT,
                                        "%.2f",
                                        finalLoss
                                )
                )
        );

        renderFinalBoxes(
                graphics,
                boxes
        );
    }

    private static void renderLiveBoxes(
            GuiGraphicsExtractor graphics,
            List<LiveBox> boxes
    ) {
        Minecraft minecraft =
                Minecraft.getInstance();

        if (minecraft.font == null
                || boxes.isEmpty()) {
            return;
        }

        int availableWidth =
                Math.max(
                        72,
                        graphics.guiWidth() - 8
                );

        List<Integer> widths =
                new ArrayList<>();

        int totalWidth = 0;

        for (LiveBox box : boxes) {
            int width =
                    desiredWidth(
                            minecraft.font,
                            box.lines(),
                            LIVE_BOX_MIN_WIDTH,
                            LIVE_BOX_MAX_WIDTH
                    );

            widths.add(width);
            totalWidth += width;
        }

        totalWidth += Math.max(
                0,
                boxes.size() - 1
        ) * GAP;

        if (totalWidth > availableWidth) {
            int compactWidth =
                    Math.max(
                            28,
                            (availableWidth
                                    - Math.max(
                                    0,
                                    boxes.size() - 1
                            ) * GAP)
                                    / boxes.size()
                    );

            widths.clear();
            totalWidth = 0;

            for (int i = 0;
                 i < boxes.size();
                 i++) {
                widths.add(compactWidth);
                totalWidth += compactWidth;
            }

            totalWidth += Math.max(
                    0,
                    boxes.size() - 1
            ) * GAP;
        }

        int startX =
                Math.max(
                        4,
                        (graphics.guiWidth()
                                - totalWidth)
                                / 2
                );

        int x = startX;

        for (int i = 0;
             i < boxes.size();
             i++) {

            int width =
                    widths.get(i);

            LiveBox box =
                    boxes.get(i);

            int height =
                    liveBoxHeight(
                            box
                    );

            graphics.fill(
                    x,
                    TOP,
                    x + width,
                    TOP + height,
                    BACKGROUND
            );

            graphics.outline(
                    x,
                    TOP,
                    width,
                    height,
                    OUTLINE
            );

            List<String> lines =
                    box.lines();

            for (int lineIndex = 0;
                 lineIndex < lines.size();
                 lineIndex++) {

                int lineY =
                        TOP + 3
                                + 10 * lineIndex;

                if (lineIndex
                        == box.sizeBreakdownLineIndex()
                        && box.sizeBreakdown() != null
                        && !box.sizeBreakdown().isEmpty()) {

                    renderSizeBreakdown(
                            graphics,
                            minecraft,
                            box.sizeBreakdown(),
                            x + 5,
                            lineY
                    );
                    continue;
                }

                if (lineIndex
                        == box.dedsBreakdownLineIndex()
                        && box.dedsBreakdown() != null
                        && !box.dedsBreakdown().isEmpty()) {

                    renderDedsBreakdown(
                            graphics,
                            minecraft,
                            box.dedsBreakdown(),
                            x + 5,
                            lineY
                    );
                    continue;
                }

                graphics.text(
                        minecraft.font,
                        lines.get(lineIndex),
                        x + 5,
                        lineY,
                        lineIndex == 0
                                ? MUTED_TEXT
                                : TEXT,
                        false
                );
            }

            x += width + GAP;
        }
    }


    private static List<ColoredDedsSegment>
    buildDedsBreakdown(
            Map<String, Double> byDisaster
    ) {
        List<ColoredDedsSegment> out =
                new ArrayList<>();

        /*
         * Show per-disaster DEDS in the same left-to-right order as the
         * game/disaster panel instead of whatever order the model map used.
         * Also keep the row visually compact: just color-coded decimal values
         * separated by slashes, with no repeated "X=" labels.
         */
        List<String> orderedKeys =
                orderedVisibleDisasterKeys(byDisaster.keySet());

        for (int i = 0;
             i < orderedKeys.size();
             i++) {

            String disasterKey =
                    orderedKeys.get(i);

            Double value =
                    byDisaster.get(disasterKey);

            DisasterDeathLine line =
                    findDisasterDeathLine(
                            disasterKey
                    );

            String displayName =
                    line == null
                            ? disasterKey
                            : line.name();

            int initialIndex =
                    initialCharacterIndex(
                            displayName
                    );

            int color =
                    dedsSegmentColor(
                            line,
                            initialIndex
                    );

            out.add(
                    new ColoredDedsSegment(
                            compactDedsValue(
                                    value == null
                                            ? 0.0
                                            : value
                            ),
                            color
                    )
            );

            if (i + 1 < orderedKeys.size()) {
                out.add(
                        new ColoredDedsSegment(
                                "/",
                                TEXT
                        )
                );
            }
        }

        return List.copyOf(out);
    }

    private static String compactDedsValue(double value) {
        long integerValue = Math.round(value);
        if (Math.abs(value - integerValue) < 1.0e-9) {
            return Long.toString(integerValue);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }


    private static List<String> orderedVisibleDisasterKeys(
            Set<String> keys
    ) {
        List<String> ordered =
                new ArrayList<>();

        if (keys == null || keys.isEmpty()) {
            return ordered;
        }

        for (DisasterDeathLine line :
                disasterDeaths.values()) {

            String normalized =
                    normalizeDisasterKey(
                            line.name()
                    );

            if (keys.contains(normalized)
                    && !HIDDEN_PASSIVE_DEDS_DISASTERS.contains(
                    normalized
            )) {
                ordered.add(normalized);
            }
        }

        for (String key :
                keys) {
            if (key == null
                    || key.isBlank()
                    || HIDDEN_PASSIVE_DEDS_DISASTERS.contains(
                    key
            )
                    || ordered.contains(key)) {
                continue;
            }
            ordered.add(key);
        }

        return ordered;
    }

    private static List<ColoredDedsSegment>
    buildSizeBreakdown(
            Guess guess
    ) {
        List<ColoredDedsSegment> out =
                new ArrayList<>();

        out.add(
                new ColoredDedsSegment(
                        "SIZE=",
                        TEXT
                )
        );

        boolean wroteDisasterSize = false;

        if (guess.deathsThisWaveByDisaster() != null) {
            List<String> orderedDisasters =
                    orderedVisibleDisasterKeys(
                            guess.deathsThisWaveByDisaster().keySet()
                    );

            for (String disaster :
                    orderedDisasters) {

                if (wroteDisasterSize) {
                    out.add(
                            new ColoredDedsSegment(
                                    "/",
                                    TEXT
                            )
                    );
                }

                DisasterDeathLine line =
                        findDisasterDeathLine(
                                disaster
                        );

                String displayName =
                        line == null
                                ? disaster
                                : line.name();

                int initialIndex =
                        initialCharacterIndex(
                                displayName
                        );

                int color =
                        dedsSegmentColor(
                                line,
                                initialIndex
                        );

                out.add(
                        new ColoredDedsSegment(
                                Integer.toString(
                                        guess.deathsThisWaveSampleSizes()
                                                .getOrDefault(
                                                        disaster,
                                                        0
                                                )
                                ),
                                color
                        )
                );

                wroteDisasterSize = true;
            }
        }

        /*
         * The final number is the minimum sample count used anywhere by END,
         * not one particular disaster, so keep it neutral.
         */
        if (wroteDisasterSize) {
            out.add(
                    new ColoredDedsSegment(
                            "/",
                            TEXT
                    )
            );
        }

        out.add(
                new ColoredDedsSegment(
                        Integer.toString(
                                Math.max(
                                        0,
                                        guess.endTrainingRows()
                                )
                        ),
                        TEXT
                )
        );

        return List.copyOf(
                out
        );
    }

    private static String plainSizeBreakdown(
            List<ColoredDedsSegment> segments
    ) {
        StringBuilder out =
                new StringBuilder();

        for (ColoredDedsSegment segment :
                segments) {
            out.append(
                    segment.text()
            );
        }

        return out.toString();
    }

    private static void renderSizeBreakdown(
            GuiGraphicsExtractor graphics,
            Minecraft minecraft,
            List<ColoredDedsSegment> segments,
            int x,
            int y
    ) {
        int drawX = x;

        for (ColoredDedsSegment segment :
                segments) {

            graphics.text(
                    minecraft.font,
                    segment.text(),
                    drawX,
                    y,
                    segment.color(),
                    false
            );

            drawX +=
                    minecraft.font.width(
                            segment.text()
                    );
        }
    }

    private static String plainDedsBreakdown(
            List<ColoredDedsSegment> segments
    ) {
        StringBuilder out =
                new StringBuilder();

        for (ColoredDedsSegment segment :
                segments) {
            out.append(segment.text());
        }

        return out.toString();
    }

    private static void renderDedsBreakdown(
            GuiGraphicsExtractor graphics,
            Minecraft minecraft,
            List<ColoredDedsSegment> segments,
            int x,
            int y
    ) {
        int drawX = x;

        for (int i = 0;
             i < segments.size();
             i++) {

            ColoredDedsSegment segment =
                    segments.get(i);

            graphics.text(
                    minecraft.font,
                    segment.text(),
                    drawX,
                    y,
                    segment.color(),
                    false
            );

            drawX +=
                    minecraft.font.width(
                            segment.text()
                    );

        }
    }

    private static DisasterDeathLine findDisasterDeathLine(
            String normalizedKey
    ) {
        if (normalizedKey == null
                || normalizedKey.isBlank()) {
            return null;
        }

        for (DisasterDeathLine line :
                disasterDeaths.values()) {

            if (normalizeDisasterKey(
                    line.name()
            ).equals(normalizedKey)) {
                return line;
            }
        }

        return null;
    }

    private static String normalizeDisasterKey(
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

    private static int initialCharacterIndex(
            String displayName
    ) {
        if (displayName == null
                || displayName.isBlank()) {
            return 0;
        }

        /*
         * Treat "The Floor is Lava" as F rather than T; otherwise use the
         * first visible character of the disaster name.
         */
        if (displayName.regionMatches(
                true,
                0,
                "The ",
                0,
                4
        )
                && displayName.length() > 4) {
            return 4;
        }

        return 0;
    }

    private static int dedsSegmentColor(
            DisasterDeathLine line,
            int characterIndex
    ) {
        if (line == null) {
            return TEXT;
        }

        List<Integer> pattern =
                line.colorPattern();

        if (pattern != null
                && characterIndex >= 0
                && characterIndex < pattern.size()) {

            Integer rgb =
                    pattern.get(
                            characterIndex
                    );

            if (rgb != null
                    && rgb >= 0) {
                return 0xFF000000 | rgb;
            }
        }

        return line.colorRgb() == null
                ? TEXT
                : 0xFF000000
                | line.colorRgb();
    }

    private static void renderColoredDisasterName(
            GuiGraphicsExtractor graphics,
            Minecraft minecraft,
            DisasterDeathLine line,
            int x,
            int y
    ) {
        String name =
                line.name();

        List<Integer> pattern =
                line.colorPattern();

        if (pattern == null
                || pattern.size() < name.length()) {

            int fallback =
                    line.colorRgb() == null
                            ? TEXT
                            : 0xFF000000
                            | line.colorRgb();

            graphics.text(
                    minecraft.font,
                    name,
                    x,
                    y,
                    fallback,
                    false
            );
            return;
        }

        int segmentStart = 0;
        int currentRgb =
                pattern.get(0) == null
                        || pattern.get(0) < 0
                        ? (
                        line.colorRgb() == null
                                ? TEXT & 0x00FFFFFF
                                : line.colorRgb()
                )
                        : pattern.get(0);

        int drawX = x;

        for (int i = 1;
             i <= name.length();
             i++) {

            int nextRgb =
                    i < name.length()
                            ? (
                            pattern.get(i) == null
                                    || pattern.get(i) < 0
                                    ? currentRgb
                                    : pattern.get(i)
                    )
                            : Integer.MIN_VALUE;

            if (i == name.length()
                    || nextRgb != currentRgb) {

                String segment =
                        name.substring(
                                segmentStart,
                                i
                        );

                graphics.text(
                        minecraft.font,
                        segment,
                        drawX,
                        y,
                        0xFF000000
                                | currentRgb,
                        false
                );

                drawX +=
                        minecraft.font.width(
                                segment
                        );

                segmentStart = i;
                currentRgb = nextRgb;
            }
        }
    }

    private static String compactNumber(
            double value
    ) {
        long integer =
                Math.round(
                        value
                );

        if (Math.abs(
                value - integer
        ) < 1.0e-9) {
            return Long.toString(
                    integer
            );
        }

        return String.format(
                Locale.ROOT,
                "%.2f",
                value
        );
    }

    private static void renderCommunityLiveLeaderboard(
            GuiGraphicsExtractor graphics,
            int y
    ) {
        List<String> lines =
                new ArrayList<>();

        lines.add(
                "PREDICTIONS"
        );

        for (CommunityGuessLine guess :
                communityGuesses.values()) {

            lines.add(
                    guess.username()
                            + ": "
                            + compactNumber(
                            guess.guess()
                    )
            );
        }

        renderLeftLeaderboardBox(
                graphics,
                y,
                lines
        );
    }

    private static void renderCommunityFinalLeaderboard(
            GuiGraphicsExtractor graphics,
            int y
    ) {
        List<String> lines =
                new ArrayList<>();

        lines.add(
                "PRED TTR"
        );

        for (CommunityLossLine loss :
                finalCommunityLosses) {

            lines.add(
                    loss.username()
                            + ": "
                            + String.format(
                            Locale.ROOT,
                            "%.2f",
                            loss.loss()
                    )
            );
        }

        renderLeftLeaderboardBox(
                graphics,
                y,
                lines
        );
    }

    private static void renderLeftLeaderboardBox(
            GuiGraphicsExtractor graphics,
            int y,
            List<String> lines
    ) {
        Minecraft minecraft =
                Minecraft.getInstance();

        if (minecraft.font == null
                || lines == null
                || lines.isEmpty()) {
            return;
        }

        int width =
                desiredWidth(
                        minecraft.font,
                        lines,
                        LEADERBOARD_MIN_WIDTH,
                        Math.min(
                                LEADERBOARD_MAX_WIDTH,
                                Math.max(
                                        LEADERBOARD_MIN_WIDTH,
                                        graphics.guiWidth() / 3
                                )
                        )
                );

        int height =
                9
                        + 11
                        * lines.size();

        int x = 4;

        graphics.fill(
                x,
                y,
                x + width,
                y + height,
                BACKGROUND
        );

        graphics.outline(
                x,
                y,
                width,
                height,
                OUTLINE
        );

        for (int i = 0;
             i < lines.size();
             i++) {

            graphics.text(
                    minecraft.font,
                    lines.get(i),
                    x + 5,
                    y + 4
                            + 11 * i,
                    i == 0
                            ? MUTED_TEXT
                            : TEXT,
                    false
            );
        }
    }

    private static void renderDisasterDeaths(
            GuiGraphicsExtractor graphics,
            int y
    ) {
        Minecraft minecraft =
                Minecraft.getInstance();

        if (minecraft.font == null) {
            return;
        }

        List<Map.Entry<String, DisasterDeathLine>> visible =
                new ArrayList<>();

        for (Map.Entry<String, DisasterDeathLine> entry :
                disasterDeaths.entrySet()) {

            String normalizedName =
                    normalizeDisasterKey(
                            entry.getValue().name()
                    );

            if (HIDDEN_DEATH_OVERLAY_DISASTERS.contains(
                    normalizedName
            )) {
                continue;
            }

            if (entry.getValue().deaths() >= 1) {
                visible.add(entry);
            }
        }

        if (visible.isEmpty()) {
            return;
        }

        int rowHeight = 11;
        int height =
                18
                        + visible.size()
                        * rowHeight;

        int widest =
                minecraft.font.width("DEATHS");

        for (Map.Entry<String, DisasterDeathLine> entry :
                visible) {

            DisasterDeathLine line =
                    entry.getValue();

            widest =
                    Math.max(
                            widest,
                            minecraft.font.width(
                                    line.name()
                                            + ": "
                                            + line.deaths()
                            )
                    );
        }

        int width =
                clamp(
                        widest + 12,
                        DISASTER_BOX_MIN_WIDTH,
                        Math.min(
                                DISASTER_BOX_MAX_WIDTH,
                                Math.max(
                                        DISASTER_BOX_MIN_WIDTH,
                                        graphics.guiWidth() - 8
                                )
                        )
                );

        /*
         * Top-right anchor. Width remains content/window-responsive; only the
         * anchor changes.
         */
        int x =
                Math.max(
                        4,
                        graphics.guiWidth()
                                - width
                                - 4
                );

        graphics.fill(
                x,
                y,
                x + width,
                y + height,
                BACKGROUND
        );

        graphics.outline(
                x,
                y,
                width,
                height,
                OUTLINE
        );

        graphics.text(
                minecraft.font,
                "DEATHS",
                x + 5,
                y + 4,
                MUTED_TEXT,
                false
        );

        int row = 0;

        for (Map.Entry<String, DisasterDeathLine> entry :
                visible) {

            DisasterDeathLine line =
                    entry.getValue();

            int lineY =
                    y + 16
                            + row * rowHeight;

            renderColoredDisasterName(
                    graphics,
                    minecraft,
                    line,
                    x + 5,
                    lineY
            );

            int countX =
                    x + 7
                            + minecraft.font.width(
                            line.name()
                    );

            graphics.text(
                    minecraft.font,
                    ": " + line.deaths(),
                    countX,
                    lineY,
                    TEXT,
                    false
            );

            row++;
        }
    }

    private static void renderFinalBoxes(
            GuiGraphicsExtractor graphics,
            List<FinalBox> boxes
    ) {
        Minecraft minecraft =
                Minecraft.getInstance();

        if (minecraft.font == null
                || boxes.isEmpty()) {
            return;
        }

        int boxesPerRow = Math.min(3, boxes.size());

        for (int row = 0;
             row * boxesPerRow < boxes.size();
             row++) {

            int rowStart =
                    row * boxesPerRow;

            int itemsThisRow =
                    Math.min(
                            boxesPerRow,
                            boxes.size()
                                    - rowStart
                    );

            List<Integer> widths =
                    new ArrayList<>();

            int rowWidth = 0;

            for (int col = 0;
                 col < itemsThisRow;
                 col++) {

                FinalBox box =
                        boxes.get(
                                rowStart + col
                        );

                int width =
                        desiredWidth(
                                minecraft.font,
                                List.of(
                                        box.title(),
                                        box.value()
                                ),
                                FINAL_BOX_MIN_WIDTH,
                                FINAL_BOX_MAX_WIDTH
                        );

                widths.add(width);
                rowWidth += width;
            }

            rowWidth += Math.max(
                    0,
                    itemsThisRow - 1
            ) * GAP;

            int availableWidth =
                    Math.max(
                            64,
                            graphics.guiWidth() - 8
                    );

            if (rowWidth > availableWidth) {
                int compactWidth =
                        Math.max(
                                54,
                                (availableWidth
                                        - Math.max(
                                        0,
                                        itemsThisRow - 1
                                ) * GAP)
                                        / itemsThisRow
                        );

                widths.clear();
                rowWidth = 0;

                for (int col = 0;
                     col < itemsThisRow;
                     col++) {
                    widths.add(compactWidth);
                    rowWidth += compactWidth;
                }

                rowWidth += Math.max(
                        0,
                        itemsThisRow - 1
                ) * GAP;
            }

            int startX =
                    Math.max(
                            4,
                            (graphics.guiWidth()
                                    - rowWidth)
                                    / 2
                    );

            int y =
                    TOP
                            + row * (
                            FINAL_BOX_HEIGHT + GAP
                    );

            int x = startX;

            for (int col = 0;
                 col < itemsThisRow;
                 col++) {

                int width =
                        widths.get(col);

                FinalBox box =
                        boxes.get(
                                rowStart + col
                        );

                graphics.fill(
                        x,
                        y,
                        x + width,
                        y + FINAL_BOX_HEIGHT,
                        BACKGROUND
                );

                graphics.outline(
                        x,
                        y,
                        width,
                        FINAL_BOX_HEIGHT,
                        OUTLINE
                );

                graphics.text(
                        minecraft.font,
                        box.title(),
                        x + 6,
                        y + 5,
                        MUTED_TEXT,
                        false
                );

                graphics.text(
                        minecraft.font,
                        box.value(),
                        x + 6,
                        y + 18,
                        TEXT,
                        false
                );

                x += width + GAP;
            }
        }
    }

    private static void renderGamePercentile(
            GuiGraphicsExtractor graphics,
            int y
    ) {
        if (currentGamePercentile == null) {
            return;
        }

        Minecraft minecraft =
                Minecraft.getInstance();

        if (minecraft.font == null) {
            return;
        }

        int roundedPercentile =
                (int) Math.round(
                        currentGamePercentile
                );

        String label =
                "BETTER THAN "
                        + roundedPercentile
                        + "% OF GAMES";

        int width =
                minecraft.font.width(label)
                        + 12;

        int x =
                (graphics.guiWidth() - width) / 2;

        int height = 20;

        graphics.fill(
                x,
                y,
                x + width,
                y + height,
                BACKGROUND
        );

        graphics.outline(
                x,
                y,
                width,
                height,
                OUTLINE
        );

        graphics.text(
                minecraft.font,
                label,
                x + 6,
                y + 6,
                TEXT,
                false
        );
    }

    private static void renderRecorderAliveStatus(
            GuiGraphicsExtractor graphics,
            int y
    ) {
        Minecraft minecraft =
                Minecraft.getInstance();

        if (minecraft.font == null) {
            return;
        }

        String text =
                "RECORDER_ALIVE="
                        + (
                        recorderAlive
                                ? "YES"
                                : "NO"
                );

        int width =
                minecraft.font.width(text)
                        + 12;

        int x =
                Math.max(
                        4,
                        (graphics.guiWidth()
                                - width)
                                / 2
                );

        int statusBackground =
                recorderAlive
                        ? BACKGROUND
                        : DEAD_BACKGROUND;

        int statusOutline =
                recorderAlive
                        ? OUTLINE
                        : DEAD_OUTLINE;

        graphics.fill(
                x,
                y,
                x + width,
                y + 16,
                statusBackground
        );

        graphics.outline(
                x,
                y,
                width,
                16,
                statusOutline
        );

        graphics.text(
                minecraft.font,
                text,
                x + 6,
                y + 4,
                TEXT,
                false
        );
    }

    private record Guess(
            int wave,
            Integer predictedPlayers,
            int sampleSize,
            Integer exactMatchWaves,
            Double deathsThisWave,
            Double deathsThisWaveStdev,
            Map<String, Double> deathsThisWaveByDisaster,
            Double fallDeathsThisWave,
            Double voidDeathsThisWave,
            Double miscDeathsThisWave,
            Map<String, Integer> deathsThisWaveSampleSizes,
            int endTrainingRows,
            boolean deathDiagnosticsModel,
            boolean exactHistoryModel
    ) {}

    private record LiveBox(
            List<String> lines,
            int sizeBreakdownLineIndex,
            List<ColoredDedsSegment> sizeBreakdown,
            int dedsBreakdownLineIndex,
            List<ColoredDedsSegment> dedsBreakdown
    ) {}

    private record ColoredDedsSegment(
            String text,
            int color
    ) {}

    private record CommunityGuessLine(
            String username,
            int wave,
            double guess
    ) {}

    private record CommunityLossLine(
            String username,
            double loss
    ) {}

    private record FinalBox(
            String title,
            String value
    ) {}

    private record DisasterDeathLine(
            String name,
            Integer colorRgb,
            List<Integer> colorPattern,
            int deaths
    ) {}
}
