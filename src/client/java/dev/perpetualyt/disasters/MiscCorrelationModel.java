package dev.perpetualyt.disasters;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/*
 * Additive correlation acknowledger for DERIVED Misc deaths.
 *
 * Why regression coefficients rather than Pearson r?
 * ---------------------------------------------------
 * The live model has to ADD these values to an expected death count.
 * A Pearson correlation coefficient is unitless and cannot sensibly be
 * summed into "deaths". Instead this cache fits ridge-regularized additive
 * coefficients measured directly in expected Misc deaths per wave.
 *
 * Age buckets:
 *   age 0 = wave in which the disaster spawned
 *   age 1 = one wave after it spawned
 *   age 2 = two waves after it spawned
 *
 * The target is residualized by wave number first:
 *
 *   residual_misc = observed_misc - mean_misc_for_that_wave_number
 *
 * Then all disaster/age indicators are fit JOINTLY:
 *
 *   residual_misc ~= sum(beta[disaster, age])
 *
 * This helps avoid simply assigning the same co-occurring Misc death to every
 * disaster independently.
 *
 * Cache cadence:
 *   only reliable schema-6 complete games are used;
 *   training advances in exact 20-game milestones (20, 40, 60, ...).
 */
public final class MiscCorrelationModel {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    DisastersTrackerClient.MOD_ID
            );

    private static final Gson GSON =
            new GsonBuilder()
                    .setPrettyPrinting()
                    .create();

    private static final int CACHE_VERSION = 1;
    private static final int GAME_BATCH_SIZE = 20;
    private static final int MIN_SCHEMA_VERSION = 6;
    private static final double RIDGE_LAMBDA = 1.0;

    private static final Path CACHE_FILE =
            ModelDataManager
                    .getLiveGamesCsvPath()
                    .getParent()
                    .resolve(
                            "misc_correlation_cache.json"
                    );

    private static CacheFile cache = null;
    private static long lastGamesFileModifiedMs =
            Long.MIN_VALUE;

    private static CsvTable lastGamesTable = null;

    private MiscCorrelationModel() {}

    public static synchronized void invalidateCache() {
        cache = null;
        lastGamesFileModifiedMs = Long.MIN_VALUE;
        lastGamesTable = null;

        try {
            Files.deleteIfExists(CACHE_FILE);
        } catch (IOException exception) {
            LOGGER.error(
                    "Could not delete stale Misc-correlation cache {}",
                    CACHE_FILE,
                    exception
            );
        }
    }

    public static synchronized Adjustment adjustmentForSeenDisasters(
            String currentPlayerCountBasis,
            int targetWave,
            List<List<String>> observedWaves
    ) {
        if (targetWave < 1
                || targetWave > 3
                || observedWaves == null
                || observedWaves.isEmpty()) {

            return Adjustment.none();
        }

        CacheEntry entry =
                ensureFreshEntry(
                        currentPlayerCountBasis
                );

        if (entry == null
                || entry.coefficients() == null
                || entry.coefficients().isEmpty()) {

            return Adjustment.none();
        }

        Map<String, Integer> startWaves =
                observedStartWaves(
                        observedWaves
                );

        double sum = 0.0;
        int used = 0;
        int minimumSupport =
                Integer.MAX_VALUE;

        for (Map.Entry<String, Integer> started :
                startWaves.entrySet()) {

            int age =
                    targetWave
                            - started.getValue();

            if (age < 0
                    || age > 2) {
                continue;
            }

            String feature =
                    featureKey(
                            started.getKey(),
                            age
                    );

            Double coefficient =
                    entry.coefficients()
                            .get(
                                    feature
                            );

            if (coefficient == null) {
                continue;
            }

            sum +=
                    coefficient;

            used++;

            int support =
                    entry.supports() == null
                            ? 0
                            : entry.supports()
                            .getOrDefault(
                                    feature,
                                    0
                            );

            minimumSupport =
                    Math.min(
                            minimumSupport,
                            support
                    );
        }

        return new Adjustment(
                sum,
                used,
                minimumSupport
                        == Integer.MAX_VALUE
                        ? 0
                        : minimumSupport,
                entry.trainedGameCount()
        );
    }

    private static CacheEntry ensureFreshEntry(
            String currentPlayerCountBasis
    ) {
        Path gamesFile =
                ModelDataManager
                        .getLiveGamesCsvPath();

        if (!Files.exists(
                gamesFile
        )) {
            return null;
        }

        String basis =
                basisKey(
                        currentPlayerCountBasis
                );

        try {
            ensureCacheLoaded();

            long modified =
                    Files.getLastModifiedTime(
                            gamesFile
                    ).toMillis();

            if (lastGamesTable == null
                    || modified
                    != lastGamesFileModifiedMs) {

                lastGamesTable =
                        readCsv(
                                gamesFile
                        );

                lastGamesFileModifiedMs =
                        modified;
            }

            List<List<String>> eligible =
                    eligibleGames(
                            lastGamesTable,
                            basis
                    );

            int milestone =
                    (
                            eligible.size()
                                    / GAME_BATCH_SIZE
                    )
                            * GAME_BATCH_SIZE;

            if (milestone
                    < GAME_BATCH_SIZE) {
                return null;
            }

            CacheEntry existing =
                    cache.entries()
                            .get(
                                    basis
                            );

            if (existing != null
                    && existing.trainedGameCount()
                    == milestone) {

                return existing;
            }

            List<List<String>> trainingRows =
                    new ArrayList<>(
                            eligible.subList(
                                    0,
                                    milestone
                            )
                    );

            CacheEntry fitted =
                    fit(
                            lastGamesTable,
                            basis,
                            trainingRows
                    );

            Map<String, CacheEntry> updated =
                    new LinkedHashMap<>(
                            cache.entries()
                    );

            updated.put(
                    basis,
                    fitted
            );

            cache =
                    new CacheFile(
                            CACHE_VERSION,
                            updated
                    );

            saveCache();

            LOGGER.info(
                    "Refit Misc-correlation cache basis={} milestoneGames={} features={}",
                    basis,
                    milestone,
                    fitted.coefficients()
                            .size()
            );

            return fitted;

        } catch (Exception exception) {
            LOGGER.error(
                    "Could not load/refit Misc-correlation cache",
                    exception
            );

            return null;
        }
    }

    private static CacheEntry fit(
            CsvTable table,
            String basis,
            List<List<String>> rows
    ) {
        List<Observation> observations =
                new ArrayList<>();

        Map<Integer, List<Double>> miscByWave =
                new LinkedHashMap<>();

        miscByWave.put(
                1,
                new ArrayList<>()
        );
        miscByWave.put(
                2,
                new ArrayList<>()
        );
        miscByWave.put(
                3,
                new ArrayList<>()
        );

        Set<String> vocabulary =
                new LinkedHashSet<>();

        for (List<String> row :
                rows) {

            Map<String, Integer> startWaves =
                    historicalStartWaves(
                            table,
                            row
                    );

            for (int wave = 1;
                 wave <= 3;
                 wave++) {

                Double misc =
                        parseDouble(
                                table.get(
                                        row,
                                        "wave_"
                                                + wave
                                                + "_misc"
                                )
                        );

                if (misc == null) {
                    continue;
                }

                List<String> features =
                        new ArrayList<>();

                for (Map.Entry<String, Integer> started :
                        startWaves.entrySet()) {

                    int age =
                            wave
                                    - started
                                    .getValue();

                    if (age < 0
                            || age > 2) {
                        continue;
                    }

                    String feature =
                            featureKey(
                                    started.getKey(),
                                    age
                            );

                    features.add(
                            feature
                    );

                    vocabulary.add(
                            feature
                    );
                }

                miscByWave.get(
                        wave
                ).add(
                        misc
                );

                observations.add(
                        new Observation(
                                wave,
                                misc,
                                features
                        )
                );
            }
        }

        if (observations.isEmpty()
                || vocabulary.isEmpty()) {

            return new CacheEntry(
                    basis,
                    rows.size(),
                    Instant.now()
                            .toString(),
                    RIDGE_LAMBDA,
                    Map.of(),
                    Map.of()
            );
        }

        Map<Integer, Double> waveMeans =
                new HashMap<>();

        for (int wave = 1;
             wave <= 3;
             wave++) {

            List<Double> values =
                    miscByWave.get(
                            wave
                    );

            waveMeans.put(
                    wave,
                    values == null
                            || values.isEmpty()
                            ? 0.0
                            : values.stream()
                            .mapToDouble(
                                    Double::doubleValue
                            )
                            .average()
                            .orElse(
                                    0.0
                            )
            );
        }

        List<String> featureNames =
                new ArrayList<>(
                        vocabulary
                );

        featureNames.sort(
                String::compareTo
        );

        Map<String, Integer> featureIndex =
                new HashMap<>();

        for (int i = 0;
             i < featureNames.size();
             i++) {

            featureIndex.put(
                    featureNames.get(i),
                    i
            );
        }

        int dimensions =
                featureNames.size();

        double[][] normal =
                new double[
                        dimensions
                        ][
                        dimensions
                        ];

        double[] rhs =
                new double[
                        dimensions
                        ];

        int[] supports =
                new int[
                        dimensions
                        ];

        for (Observation observation :
                observations) {

            double residual =
                    observation.misc()
                            - waveMeans
                            .getOrDefault(
                                    observation.wave(),
                                    0.0
                            );

            List<Integer> activeIndexes =
                    new ArrayList<>();

            for (String feature :
                    observation.features()) {

                Integer index =
                        featureIndex.get(
                                feature
                        );

                if (index == null) {
                    continue;
                }

                activeIndexes.add(
                        index
                );

                supports[index]++;

                rhs[index] +=
                        residual;
            }

            for (Integer i :
                    activeIndexes) {

                for (Integer j :
                        activeIndexes) {

                    normal[i][j] +=
                            1.0;
                }
            }
        }

        for (int i = 0;
             i < dimensions;
             i++) {

            normal[i][i] +=
                    RIDGE_LAMBDA;
        }

        double[] coefficients =
                solveLinearSystem(
                        normal,
                        rhs
                );

        if (coefficients == null) {
            coefficients =
                    new double[
                            dimensions
                            ];
        }

        Map<String, Double> coefficientMap =
                new LinkedHashMap<>();

        Map<String, Integer> supportMap =
                new LinkedHashMap<>();

        for (int i = 0;
             i < dimensions;
             i++) {

            coefficientMap.put(
                    featureNames.get(i),
                    coefficients[i]
            );

            supportMap.put(
                    featureNames.get(i),
                    supports[i]
            );
        }

        return new CacheEntry(
                basis,
                rows.size(),
                Instant.now()
                        .toString(),
                RIDGE_LAMBDA,
                Map.copyOf(
                        coefficientMap
                ),
                Map.copyOf(
                        supportMap
                )
        );
    }

    private static List<List<String>> eligibleGames(
            CsvTable table,
            String basis
    ) {
        List<List<String>> eligible =
                new ArrayList<>();

        for (List<String> row :
                table.rows()) {

            Integer schema =
                    parseInt(
                            table.get(
                                    row,
                                    "schema_version"
                            )
                    );

            if (schema == null
                    || schema
                    < MIN_SCHEMA_VERSION) {
                continue;
            }

            if (!parseBoolean(
                    table.get(
                            row,
                            "complete_game"
                    )
            )) {
                continue;
            }

            if (!parseBoolean(
                    table.get(
                            row,
                            "observed_from_start"
                    )
            )) {
                continue;
            }

            Integer waveCount =
                    parseInt(
                            table.get(
                                    row,
                                    "wave_count"
                            )
                    );

            if (waveCount == null
                    || waveCount < 3) {
                continue;
            }

            if (!basis.equals(
                    basisKey(
                            table.get(
                                    row,
                                    "player_count_basis"
                            )
                    )
            )) {
                continue;
            }

            boolean usable = true;

            for (int wave = 1;
                 wave <= 3;
                 wave++) {

                if (parseDouble(
                        table.get(
                                row,
                                "wave_"
                                        + wave
                                        + "_misc"
                        )
                ) == null) {

                    usable = false;
                    break;
                }
            }

            if (usable) {
                eligible.add(
                        row
                );
            }
        }

        return eligible;
    }

    private static Map<String, Integer> historicalStartWaves(
            CsvTable table,
            List<String> row
    ) {
        Map<String, Integer> starts =
                new LinkedHashMap<>();

        for (int wave = 1;
             wave <= 3;
             wave++) {

            for (String disaster :
                    parseCombo(
                            table.get(
                                    row,
                                    "wave_"
                                            + wave
                                            + "_disasters"
                            )
                    )) {

                starts.putIfAbsent(
                        disaster,
                        wave
                );
            }
        }

        return starts;
    }

    private static Map<String, Integer> observedStartWaves(
            List<List<String>> observedWaves
    ) {
        Map<String, Integer> starts =
                new LinkedHashMap<>();

        int limit =
                Math.min(
                        3,
                        observedWaves.size()
                );

        for (int wave = 1;
             wave <= limit;
             wave++) {

            List<String> disasters =
                    observedWaves.get(
                            wave - 1
                    );

            if (disasters == null) {
                continue;
            }

            for (String raw :
                    disasters) {

                String normalized =
                        normalizeToken(
                                raw
                        );

                if (!normalized.isBlank()) {
                    starts.putIfAbsent(
                            normalized,
                            wave
                    );
                }
            }
        }

        return starts;
    }

    private static String featureKey(
            String disaster,
            int age
    ) {
        return normalizeToken(
                disaster
        )
                + "#"
                + age;
    }

    private static Set<String> parseCombo(
            String value
    ) {
        Set<String> out =
                new LinkedHashSet<>();

        if (value == null
                || value.isBlank()) {
            return out;
        }

        for (String part :
                value.split(
                        "\\|"
                )) {

            String normalized =
                    normalizeToken(
                            part
                    );

            if (!normalized.isBlank()) {
                out.add(
                        normalized
                );
            }
        }

        return out;
    }

    private static String normalizeToken(
            String value
    ) {
        if (value == null) {
            return "";
        }

        return value.toLowerCase(
                        Locale.ROOT
                )
                .replaceAll(
                        "[^a-z0-9]+",
                        "_"
                )
                .replaceAll(
                        "^_+|_+$",
                        ""
                );
    }

    private static String basisKey(
            String value
    ) {
        return value != null
                && value.toLowerCase(
                        Locale.ROOT
                ).contains(
                        "excluding_recorder"
                )
                ? "excluding_recorder"
                : "including_recorder";
    }

    private static boolean parseBoolean(
            String value
    ) {
        return value != null
                && (
                "true".equalsIgnoreCase(
                        value.strip()
                )
                        || "1".equals(
                        value.strip()
                )
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
        } catch (NumberFormatException exception) {
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
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static double[] solveLinearSystem(
            double[][] matrix,
            double[] vector
    ) {
        int n =
                vector.length;

        if (n == 0) {
            return new double[0];
        }

        double[][] a =
                new double[
                        n
                        ][
                        n + 1
                        ];

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

            double bestValue =
                    Math.abs(
                            a[pivot][pivot]
                    );

            for (int row =
                         pivot + 1;
                 row < n;
                 row++) {

                double value =
                        Math.abs(
                                a[row][pivot]
                        );

                if (value > bestValue) {
                    bestValue =
                            value;

                    bestRow =
                            row;
                }
            }

            if (bestValue < 1.0e-12) {
                return null;
            }

            if (bestRow != pivot) {
                double[] swap =
                        a[pivot];

                a[pivot] =
                        a[bestRow];

                a[bestRow] =
                        swap;
            }

            double divisor =
                    a[pivot][pivot];

            for (int column =
                         pivot;
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

                if (Math.abs(
                        factor
                ) < 1.0e-15) {
                    continue;
                }

                for (int column =
                             pivot;
                     column <= n;
                     column++) {

                    a[row][column] -=
                            factor
                                    * a[pivot][column];
                }
            }
        }

        double[] solution =
                new double[
                        n
                        ];

        for (int row = 0;
             row < n;
             row++) {

            solution[row] =
                    a[row][n];
        }

        return solution;
    }

    private static void ensureCacheLoaded() {
        if (cache != null) {
            return;
        }

        if (!Files.exists(
                CACHE_FILE
        )) {
            cache =
                    new CacheFile(
                            CACHE_VERSION,
                            Map.of()
                    );

            return;
        }

        try {
            CacheFile parsed =
                    GSON.fromJson(
                            Files.readString(
                                    CACHE_FILE,
                                    StandardCharsets.UTF_8
                            ),
                            CacheFile.class
                    );

            if (parsed == null
                    || parsed.version()
                    != CACHE_VERSION
                    || parsed.entries() == null) {

                cache =
                        new CacheFile(
                                CACHE_VERSION,
                                Map.of()
                        );

            } else {
                cache =
                        parsed;
            }

        } catch (Exception exception) {
            LOGGER.error(
                    "Could not read {}; rebuilding cache",
                    CACHE_FILE,
                    exception
            );

            cache =
                    new CacheFile(
                            CACHE_VERSION,
                            Map.of()
                    );
        }
    }

    private static void saveCache()
            throws IOException {

        Files.createDirectories(
                CACHE_FILE.getParent()
        );

        Path temporary =
                CACHE_FILE.resolveSibling(
                        CACHE_FILE.getFileName()
                                + ".tmp"
                );

        Files.writeString(
                temporary,
                GSON.toJson(
                        cache
                )
                        + System.lineSeparator(),
                StandardCharsets.UTF_8
        );

        try {
            Files.move(
                    temporary,
                    CACHE_FILE,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );

        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    temporary,
                    CACHE_FILE,
                    StandardCopyOption.REPLACE_EXISTING
            );
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
                        && i + 1
                        < line.length()
                        && line.charAt(
                        i + 1
                ) == '"') {

                    current.append(
                            '"'
                    );

                    i++;

                } else {
                    inQuotes =
                            !inQuotes;
                }

                continue;
            }

            if (c == ','
                    && !inQuotes) {

                cells.add(
                        current.toString()
                );

                current.setLength(
                        0
                );

                continue;
            }

            current.append(
                    c
            );
        }

        cells.add(
                current.toString()
        );

        return cells;
    }

    public record Adjustment(
            double additionalMiscDeaths,
            int usedCoefficients,
            int minimumSupport,
            int trainedGameCount
    ) {
        private static Adjustment none() {
            return new Adjustment(
                    0.0,
                    0,
                    0,
                    0
            );
        }
    }

    private record Observation(
            int wave,
            double misc,
            List<String> features
    ) {}

    private record CacheFile(
            int version,
            Map<String, CacheEntry> entries
    ) {}

    private record CacheEntry(
            String basis,
            int trainedGameCount,
            String fittedAt,
            double ridgeLambda,
            Map<String, Double> coefficients,
            Map<String, Integer> supports
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
                    index.get(
                            column
                    );

            if (position == null
                    || position < 0
                    || position
                    >= row.size()) {

                return "";
            }

            String value =
                    row.get(
                            position
                    );

            return value == null
                    ? ""
                    : value.strip();
        }
    }
}
