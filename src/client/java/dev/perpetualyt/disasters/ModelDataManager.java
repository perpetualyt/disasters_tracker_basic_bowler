package dev.perpetualyt.disasters;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModelDataManager {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DisastersTrackerClient.MOD_ID);

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().create();

    private static final Path GAME_DIR =
            FabricLoader.getInstance().getGameDir();

    private static final Path OUTPUT_DIR =
            GAME_DIR.resolve("disasters-data");

    private static final Path CONFIG_DIR =
            GAME_DIR.resolve("config");

    private static final Path CONFIG_FILE =
            CONFIG_DIR.resolve("perphet.json");

    private static final Path LIVE_FILE =
            OUTPUT_DIR.resolve("games.csv");

    private static final Path MODEL_FILE =
            OUTPUT_DIR.resolve("model_dataset.csv");

    /*
     * Public/default model mode is live-only. Legacy seed data is no longer
     * bundled with the mod or used to rebuild model_dataset.csv.
     *
     * The field remains only for config-file backward compatibility with
     * older installs; it is always forced false.
     */
    private static boolean includeLegacyDataForModel = false;

    /*
     * Default recorder mode: OFF = recorder is stationary and is excluded
     * from model player counts while alive.
     *
     * ON behavior is intentionally minimal for now: keep total counts.
     */
    private static boolean recorderMoving = false;

    /*
     * Overlay 1: live wave-by-wave end-player guesses.
     * Overlay 2: final five-box score summary.
     */
    private static boolean overlay1Enabled = true;
    private static boolean overlay2Enabled = true;
    private static boolean disasterDeathsOverlayEnabled = true;
    private static boolean recorderAliveOverlayEnabled = true;
    private static boolean gamePercentileOverlayEnabled = true;

    /*
     * Visible-player !dpred / !disasterpred acceptance window measured from
     * the moment the new wave/disaster roll is detected.
     */
    private static double predictionWindowSeconds = 5.0;

    /*
     * OFF: death-average models average raw death counts.
     * ON:  death-average models average per-wave mortality fractions, then
     *      convert the mean/stdev back into player counts using the current
     *      number of players alive.
     */
    private static boolean percentageBasedAverages = false;

    /*
     * Disaster-chat-death model sample scope.
     *
     * OFF:
     *   pool all per-wave samples across W1/W2/W3 and population-normalize.
     *
     * ON:
     *   use deaths from the SAME wave number whenever the disaster was
     *   cumulatively active/relevant in that historical wave.
     *
     * HYPERSPECIFIC:
     *   use deaths from the SAME wave number while conditioning on the
     *   disaster's historical START wave.
     *
     * MAP_HYPERSPECIFIC:
     *   HYPERSPECIFIC, but the entire Chat-DEDS historical sample pool is
     *   restricted to the current map.
     *
     * AUTO:
     *   ON for ordinary disasters; HYPERSPECIFIC for Flood and
     *   The Floor Is Lava.
     */
    private static WaveSpecificMode waveSpecificMode =
            WaveSpecificMode.ON;

    public enum WaveSpecificMode {
        OFF("OFF"),
        ON("ON"),
        HYPERSPECIFIC("HYPER"),
        MAP_HYPERSPECIFIC("HYPER-HYPER"),
        AUTO("AUTO");

        private final String displayName;

        WaveSpecificMode(
                String displayName
        ) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public WaveSpecificMode next() {
            WaveSpecificMode[] values =
                    values();

            return values[
                    (ordinal() + 1)
                            % values.length
            ];
        }

        public static WaveSpecificMode fromConfig(
                String value
        ) {
            if (value == null
                    || value.isBlank()) {
                return ON;
            }

            String normalized =
                    value.strip()
                            .toUpperCase();

            if ("HYPER".equals(normalized)) {
                return HYPERSPECIFIC;
            }

            if ("HYPER-HYPER".equals(normalized)
                    || "HYPER_HYPER".equals(normalized)
                    || "HYPERHYPER".equals(normalized)
                    || "MAP_HYPER".equals(normalized)
                    || "MAP-HYPER".equals(normalized)) {
                return MAP_HYPERSPECIFIC;
            }

            for (WaveSpecificMode mode :
                    values()) {

                if (mode.name().equals(
                        normalized
                )
                        || mode.displayName
                        .equalsIgnoreCase(
                                value.strip()
                        )) {

                    return mode;
                }
            }

            return ON;
        }
    }

    /*
     * Prediction model used by the live overlays.
     * Default stays on the existing empirical kNN model.
     */
    private static PredictionModel predictionModel =
            PredictionModel.KNN;

    public enum PredictionModel {
        KNN(
                "KNN",
                "empirical_knn_v1"
        ),
        WAVE_AVERAGE_DEATHS(
                "Wave avg deaths",
                "wave_avg_deaths_v1"
        ),
        EXACT_COMBO_AVERAGE_DEATHS(
                "Exact history avg deaths",
                "exact_history_avg_deaths_v3"
        ),
        DISASTER_PERCENT_CORRELATION(
                "Disaster % correlation",
                "disaster_percent_ridge_v1"
        ),
        DISASTER_CHAT_DEATH_HISTORY(
                "Disaster chat deaths",
                "disaster_chat_death_history_v1"
        );

        private final String displayName;
        private final String modelId;

        PredictionModel(
                String displayName,
                String modelId
        ) {
            this.displayName = displayName;
            this.modelId = modelId;
        }

        public String displayName() {
            return displayName;
        }

        public String modelId() {
            return modelId;
        }

        public PredictionModel next() {
            PredictionModel[] values = values();

            return values[
                    (ordinal() + 1)
                            % values.length
            ];
        }

        public static PredictionModel fromConfig(
                String value
        ) {
            if (value == null
                    || value.isBlank()) {
                return KNN;
            }

            String normalized =
                    value.strip()
                            .toUpperCase();

            for (PredictionModel model :
                    values()) {

                if (model.name().equals(
                        normalized
                )
                        || model.modelId
                        .equalsIgnoreCase(
                                value.strip()
                        )) {

                    return model;
                }
            }

            return KNN;
        }
    }

    private static final List<String> MODEL_COLUMNS = List.of(
            "model_schema_version",
            "data_source",
            "source_game_id",
            "recorded_time",
            "map",
            "complete_game",
            "observed_from_start",

            "recorder_moving",
            "player_count_basis",

            "starting_players",
            "before_wave_1_losses",

            "wave_1_time_left",
            "wave_1_players_at_roll",
            "wave_1_disasters",
            "wave_1_players_after",
            "wave_1_deaths",
            "wave_1_survival_fraction",
            "wave_1_mortality_fraction",

            "wave_2_time_left",
            "wave_2_players_at_roll",
            "wave_2_disasters",
            "wave_2_players_after",
            "wave_2_deaths",
            "wave_2_survival_fraction",
            "wave_2_mortality_fraction",

            "wave_3_time_left",
            "wave_3_players_at_roll",
            "wave_3_disasters",
            "wave_3_players_after",
            "wave_3_deaths",
            "wave_3_survival_fraction",
            "wave_3_mortality_fraction",

            "end_players",
            "total_deaths",
            "end_survival_fraction",

            "lobby_capacity",
            "hypixel_header",
            "disaster_count",
            "all_disasters",
            "data_quality_notes"
    );

    private ModelDataManager() {}

    public static void initialize() {
        try {
            Files.createDirectories(OUTPUT_DIR);
            Files.createDirectories(CONFIG_DIR);

            ensureConfig();
            loadConfig();
            refreshModelDataset();

            LOGGER.info(
                    "Model data mode: live only"
            );

            LOGGER.info(
                    "Recorder moving: {}",
                    recorderMoving
            );

            LOGGER.info(
                    "Prediction model: {}",
                    predictionModel.modelId()
            );

            LOGGER.info(
                    "Percentage-based death averages: {}",
                    percentageBasedAverages
            );

            LOGGER.info(
                    "Disaster chat wave mode: {}",
                    waveSpecificMode
            );

        } catch (IOException exception) {
            LOGGER.error(
                    "Could not initialize Perphet model data",
                    exception
            );
        }
    }

    /**
     * Backward-compatible API retained for older config-screen code/plugins.
     * Legacy model data is no longer supported, so this is always false.
     */
    public static boolean includeLegacyDataForModel() {
        return false;
    }

    /**
     * Backward-compatible no-op. model_dataset.csv is always rebuilt from
     * live games.csv only.
     */
    public static void setIncludeLegacyDataForModel(
            boolean value
    ) {
        includeLegacyDataForModel = false;
        saveConfig();
        refreshModelDataset();
    }

    public static boolean isRecorderMoving() {
        return recorderMoving;
    }

    public static void setRecorderMoving(
            boolean value
    ) {
        recorderMoving = value;
        saveConfig();
    }

    public static boolean isOverlay1Enabled() {
        return overlay1Enabled;
    }

    public static void setOverlay1Enabled(
            boolean value
    ) {
        overlay1Enabled = value;
        saveConfig();
    }

    public static boolean isOverlay2Enabled() {
        return overlay2Enabled;
    }

    public static void setOverlay2Enabled(
            boolean value
    ) {
        overlay2Enabled = value;
        saveConfig();
    }

    public static boolean isDisasterDeathsOverlayEnabled() {
        return disasterDeathsOverlayEnabled;
    }

    public static void setDisasterDeathsOverlayEnabled(
            boolean value
    ) {
        disasterDeathsOverlayEnabled = value;
        saveConfig();
    }

    public static boolean isRecorderAliveOverlayEnabled() {
        return recorderAliveOverlayEnabled;
    }

    public static void setRecorderAliveOverlayEnabled(
            boolean value
    ) {
        recorderAliveOverlayEnabled = value;
        saveConfig();
    }

    public static boolean isGamePercentileOverlayEnabled() {
        return gamePercentileOverlayEnabled;
    }

    public static void setGamePercentileOverlayEnabled(
            boolean value
    ) {
        gamePercentileOverlayEnabled = value;
        saveConfig();
    }

    public static double getPredictionWindowSeconds() {
        return predictionWindowSeconds;
    }

    public static void setPredictionWindowSeconds(
            double value
    ) {
        if (!Double.isFinite(
                value
        )) {
            return;
        }

        predictionWindowSeconds =
                Math.max(
                        0.0,
                        Math.min(
                                120.0,
                                value
                        )
                );

        saveConfig();
    }

    public static boolean isPercentageBasedAverages() {
        return percentageBasedAverages;
    }

    public static void setPercentageBasedAverages(
            boolean value
    ) {
        percentageBasedAverages = value;
        saveConfig();
    }

    /*
     * Boolean compatibility API retained for old callers/config semantics.
     * Any mode except OFF is wave-specific in the broad sense.
     */
    public static boolean isWaveSpecific() {
        return waveSpecificMode
                != WaveSpecificMode.OFF;
    }

    public static void setWaveSpecific(
            boolean value
    ) {
        setWaveSpecificMode(
                value
                        ? WaveSpecificMode.ON
                        : WaveSpecificMode.OFF
        );
    }

    public static WaveSpecificMode getWaveSpecificMode() {
        return waveSpecificMode;
    }

    public static void setWaveSpecificMode(
            WaveSpecificMode value
    ) {
        waveSpecificMode =
                value == null
                        ? WaveSpecificMode.ON
                        : value;

        saveConfig();
    }

    public static void cycleWaveSpecificMode() {
        setWaveSpecificMode(
                waveSpecificMode.next()
        );
    }

    public static PredictionModel getPredictionModel() {
        return predictionModel;
    }

    public static void setPredictionModel(
            PredictionModel value
    ) {
        predictionModel =
                value == null
                        ? PredictionModel.KNN
                        : value;

        saveConfig();
    }

    public static void cyclePredictionModel() {
        setPredictionModel(
                predictionModel.next()
        );
    }

    public static Path getModelDatasetPath() {
        return MODEL_FILE;
    }

    public static Path getLiveGamesCsvPath() {
        return LIVE_FILE;
    }

    public static Path getConfigPath() {
        return CONFIG_FILE;
    }

    public static void saveConfig() {
        try {
            Files.createDirectories(CONFIG_DIR);

            Files.writeString(
                    CONFIG_FILE,
                    GSON.toJson(
                            new Config(
                                    false,
                                    recorderMoving,
                                    overlay1Enabled,
                                    overlay2Enabled,
                                    disasterDeathsOverlayEnabled,
                                    recorderAliveOverlayEnabled,
                                    gamePercentileOverlayEnabled,
                                    predictionWindowSeconds,
                                    percentageBasedAverages,
                                    waveSpecificMode.name(),
                                    predictionModel.name()
                            )
                    ) + System.lineSeparator(),
                    StandardCharsets.UTF_8
            );

        } catch (IOException exception) {
            LOGGER.error(
                    "Could not save Perphet config",
                    exception
            );
        }
    }

    /*
     * Rebuild model_dataset.csv from live games.csv only.
     *
     * The old bundled legacy seed is intentionally no longer part of the
     * public project or the runtime model.
     */
    public static void refreshModelDataset() {
        try {
            Files.createDirectories(OUTPUT_DIR);

            List<Map<String, String>> rows =
                    new ArrayList<>();

            if (Files.exists(LIVE_FILE)) {
                rows.addAll(
                        transformLiveRows(
                                readCsv(LIVE_FILE)
                        )
                );
            }

            writeModelCsvAtomically(rows);

            LOGGER.info(
                    "Rebuilt model dataset from live games.csv: {} rows",
                    rows.size()
            );

        } catch (IOException exception) {
            LOGGER.error(
                    "Could not rebuild model dataset",
                    exception
            );
        }
    }

    private static void ensureConfig()
            throws IOException {

        if (Files.exists(CONFIG_FILE)) {
            return;
        }

        Files.writeString(
                CONFIG_FILE,
                GSON.toJson(
                        new Config(
                                false,
                                false,
                                true,
                                true,
                                true,
                                true,
                                true,
                                5.0,
                                false,
                                WaveSpecificMode.ON.name(),
                                PredictionModel.KNN.name()
                        )
                ) + System.lineSeparator(),
                StandardCharsets.UTF_8
        );
    }

    private static void loadConfig() {
        try {
            String json =
                    Files.readString(
                            CONFIG_FILE,
                            StandardCharsets.UTF_8
                    );

            JsonObject object =
                    JsonParser.parseString(json)
                            .getAsJsonObject();

            /*
             * v0.1.0-beta.1+: live-only model data. Ignore any legacy-data toggle
             * left behind by older config files.
             */
            includeLegacyDataForModel = false;

            if (object.has("recorderMoving")) {
                recorderMoving =
                        object.get(
                                "recorderMoving"
                        ).getAsBoolean();
            } else {
                recorderMoving = false;
            }

            if (object.has("overlay1Enabled")) {
                overlay1Enabled =
                        object.get(
                                "overlay1Enabled"
                        ).getAsBoolean();
            } else {
                overlay1Enabled = true;
            }

            if (object.has("overlay2Enabled")) {
                overlay2Enabled =
                        object.get(
                                "overlay2Enabled"
                        ).getAsBoolean();
            } else {
                overlay2Enabled = true;
            }

            if (object.has("disasterDeathsOverlayEnabled")) {
                disasterDeathsOverlayEnabled =
                        object.get(
                                "disasterDeathsOverlayEnabled"
                        ).getAsBoolean();
            } else {
                disasterDeathsOverlayEnabled = true;
            }

            if (object.has("recorderAliveOverlayEnabled")) {
                recorderAliveOverlayEnabled =
                        object.get(
                                "recorderAliveOverlayEnabled"
                        ).getAsBoolean();
            } else {
                recorderAliveOverlayEnabled = true;
            }

            if (object.has("gamePercentileOverlayEnabled")) {
                gamePercentileOverlayEnabled =
                        object.get(
                                "gamePercentileOverlayEnabled"
                        ).getAsBoolean();
            } else {
                gamePercentileOverlayEnabled = true;
            }

            if (object.has("predictionWindowSeconds")) {
                double loaded =
                        object.get(
                                "predictionWindowSeconds"
                        ).getAsDouble();

                predictionWindowSeconds =
                        Double.isFinite(
                                loaded
                        )
                                ? Math.max(
                                0.0,
                                Math.min(
                                        120.0,
                                        loaded
                                )
                        )
                                : 5.0;

            } else {
                predictionWindowSeconds = 5.0;
            }

            if (object.has("percentageBasedAverages")) {
                percentageBasedAverages =
                        object.get(
                                "percentageBasedAverages"
                        ).getAsBoolean();
            } else {
                percentageBasedAverages = false;
            }

            if (object.has("waveSpecificMode")) {
                waveSpecificMode =
                        WaveSpecificMode.fromConfig(
                                object.get(
                                        "waveSpecificMode"
                                ).getAsString()
                        );

            } else if (object.has("waveSpecific")) {
                /*
                 * Backward compatibility:
                 * old true -> ON
                 * old false -> OFF
                 */
                waveSpecificMode =
                        object.get(
                                "waveSpecific"
                        ).getAsBoolean()
                                ? WaveSpecificMode.ON
                                : WaveSpecificMode.OFF;

            } else {
                waveSpecificMode =
                        WaveSpecificMode.ON;
            }

            if (object.has("predictionModel")) {
                predictionModel =
                        PredictionModel.fromConfig(
                                object.get(
                                        "predictionModel"
                                ).getAsString()
                        );
            } else {
                predictionModel =
                        PredictionModel.KNN;
            }

            saveConfig();

        } catch (Exception exception) {
            LOGGER.error(
                    "Could not read {}; keeping defaults",
                    CONFIG_FILE,
                    exception
            );

            includeLegacyDataForModel = false;
            recorderMoving = false;
            overlay1Enabled = true;
            overlay2Enabled = true;
            disasterDeathsOverlayEnabled = true;
            recorderAliveOverlayEnabled = true;
            gamePercentileOverlayEnabled = true;
            predictionWindowSeconds = 5.0;
            percentageBasedAverages = false;
            waveSpecificMode =
                    WaveSpecificMode.ON;
            predictionModel =
                    PredictionModel.KNN;
        }
    }

    private static List<Map<String, String>>
    transformLegacyRows(
            CsvTable table
    ) {
        List<Map<String, String>> out =
                new ArrayList<>();

        for (List<String> row :
                table.rows()) {

            String gameIndex =
                    table.get(
                            row,
                            "game_index"
                    );

            if (gameIndex.isBlank()) {
                continue;
            }

            Map<String, String> model =
                    blankModelRow();

            model.put("model_schema_version", "3");
            model.put("data_source", "legacy");
            model.put("source_game_id", "legacy-" + gameIndex);
            model.put("recorded_time", table.get(row, "DATE"));
            model.put("map", table.get(row, "MAP"));
            model.put("observed_from_start", "");

            String recorderNotMoving =
                    table.get(
                            row,
                            "recorder_not_moving"
                    );

            if ("1".equals(recorderNotMoving)) {
                model.put("recorder_moving", "false");
            } else if ("0".equals(recorderNotMoving)) {
                model.put("recorder_moving", "true");
            }

            model.put(
                    "player_count_basis",
                    "excluding_recorder"
            );

            String starting =
                    table.get(
                            row,
                            "starting_players_excluding_recorder"
                    );

            String beforeWave1 =
                    table.get(
                            row,
                            "before_disaster_1_deaths_this_is_included_in_starting_player_count"
                    );

            if (beforeWave1.isBlank()) {
                /*
                 * The legacy sheet used this as a sparse exception field.
                 * Blank is treated as zero for the combined model.
                 */
                beforeWave1 = "0";
            }

            String after1 =
                    table.get(
                            row,
                            "after_1_players"
                    );

            String after2 =
                    table.get(
                            row,
                            "after_2_players"
                    );

            String end =
                    table.get(
                            row,
                            "end_players"
                    );

            String d1 =
                    table.get(
                            row,
                            "disaster_1"
                    );

            String d2 =
                    table.get(
                            row,
                            "disaster_2"
                    );

            String d3 =
                    table.get(
                            row,
                            "disaster_3"
                    );

            model.put("starting_players", starting);
            model.put("before_wave_1_losses", beforeWave1);

            /*
             * Legacy wave-1-at-roll is not always internally consistent
             * with its sparse pre-wave-loss field, so preserve starting
             * players as the historical denominator and carry pre-wave loss
             * separately instead of inventing a corrected roll count.
             */
            model.put("wave_1_players_at_roll", starting);
            model.put("wave_1_disasters", normalizeDisasterList(d1));
            model.put("wave_1_players_after", after1);
            model.put("wave_1_deaths", table.get(row, "1_DEATHS"));
            model.put("wave_1_survival_fraction", table.get(row, "1_SURVIVAL_FRACTION"));
            model.put(
                    "wave_1_mortality_fraction",
                    mortalityFromSurvival(
                            model.get("wave_1_survival_fraction")
                    )
            );

            model.put("wave_2_players_at_roll", after1);
            model.put("wave_2_disasters", normalizeDisasterList(d2));
            model.put("wave_2_players_after", after2);
            model.put("wave_2_deaths", table.get(row, "2_DEATHS"));
            model.put("wave_2_survival_fraction", table.get(row, "2_SURVIVAL_FRACTION"));
            model.put(
                    "wave_2_mortality_fraction",
                    mortalityFromSurvival(
                            model.get("wave_2_survival_fraction")
                    )
            );

            model.put("wave_3_players_at_roll", after2);
            model.put("wave_3_disasters", normalizeDisasterList(d3));
            model.put("wave_3_players_after", end);
            model.put("wave_3_deaths", table.get(row, "3_DEATHS"));
            model.put("wave_3_survival_fraction", table.get(row, "3_SURVIVAL_FRACTION"));
            model.put(
                    "wave_3_mortality_fraction",
                    mortalityFromSurvival(
                            model.get("wave_3_survival_fraction")
                    )
            );

            model.put("end_players", end);
            model.put("total_deaths", subtractStrings(starting, end));
            model.put("end_survival_fraction", table.get(row, "END_SURVIVAL_FRACTION"));

            List<String> disasters =
                    combinedDisasters(
                            d1,
                            d2,
                            d3
                    );

            model.put(
                    "disaster_count",
                    Integer.toString(
                            disasters.size()
                    )
            );

            model.put(
                    "all_disasters",
                    String.join(
                            " | ",
                            disasters
                    )
            );

            model.put(
                    "complete_game",
                    Boolean.toString(
                            !d1.isBlank()
                                    && !d2.isBlank()
                                    && !d3.isBlank()
                                    && !end.isBlank()
                    )
            );

            String quality =
                    table.get(
                            row,
                            "data_quality_notes"
                    );

            if (!"0".equals(beforeWave1)
                    && !beforeWave1.isBlank()) {

                String note =
                        "legacy pre-wave-1 loss field present; historical wave-1 denominator preserved";

                quality =
                        quality.isBlank()
                                ? note
                                : quality + "; " + note;
            }

            model.put(
                    "data_quality_notes",
                    quality
            );

            out.add(model);
        }

        return out;
    }

    private static List<Map<String, String>>
    transformLiveRows(
            CsvTable table
    ) {
        List<Map<String, String>> out =
                new ArrayList<>();

        for (List<String> row :
                table.rows()) {

            String gameId =
                    table.get(
                            row,
                            "game_id"
                    );

            if (gameId.isBlank()) {
                continue;
            }

            Map<String, String> model =
                    blankModelRow();

            model.put("model_schema_version", "3");
            model.put("data_source", "live");
            model.put("source_game_id", gameId);
            model.put("recorded_time", table.get(row, "start_timestamp"));
            model.put("map", table.get(row, "map"));
            model.put("observed_from_start", table.get(row, "observed_from_start"));

            String recorderMovingValue =
                    table.get(
                            row,
                            "recorder_moving"
                    );

            model.put(
                    "recorder_moving",
                    recorderMovingValue
            );

            String basis =
                    table.get(
                            row,
                            "player_count_basis"
                    );

            if (basis.isBlank()) {
                basis = "total_including_recorder";
            }

            model.put(
                    "player_count_basis",
                    basis
            );

            /*
             * Prefer v0.6 model-compatible fields.
             * Fall back to old raw-total fields for pre-v0.6 rows.
             */
            String starting =
                    firstNonBlank(
                            table.get(
                                    row,
                                    "game_start_players_model"
                            ),
                            table.get(
                                    row,
                                    "starting_players_total"
                            )
                    );

            String beforeWave1 =
                    firstNonBlank(
                            table.get(
                                    row,
                                    "before_wave_1_losses_model"
                            ),
                            subtractStrings(
                                    table.get(
                                            row,
                                            "starting_players_total"
                                    ),
                                    table.get(
                                            row,
                                            "wave_1_players_at_roll"
                                    )
                            )
                    );

            model.put("starting_players", starting);
            model.put("before_wave_1_losses", beforeWave1);

            putLiveWave(
                    model,
                    table,
                    row,
                    1
            );

            putLiveWave(
                    model,
                    table,
                    row,
                    2
            );

            putLiveWave(
                    model,
                    table,
                    row,
                    3
            );

            String endPlayers =
                    firstNonBlank(
                            table.get(
                                    row,
                                    "end_players_model"
                            ),
                            table.get(
                                    row,
                                    "end_players_total"
                            )
                    );

            model.put("end_players", endPlayers);

            model.put(
                    "total_deaths",
                    firstNonBlank(
                            table.get(
                                    row,
                                    "total_deaths_model"
                            ),
                            table.get(
                                    row,
                                    "total_deaths"
                            )
                    )
            );

            model.put(
                    "end_survival_fraction",
                    firstNonBlank(
                            table.get(
                                    row,
                                    "end_survival_fraction_model"
                            ),
                            table.get(
                                    row,
                                    "end_survival_fraction"
                            )
                    )
            );

            copy(model, "lobby_capacity", table, row, "lobby_capacity");
            copy(model, "hypixel_header", table, row, "hypixel_header");
            copy(model, "disaster_count", table, row, "disaster_count");
            copy(model, "all_disasters", table, row, "all_disasters");

            String waveCount =
                    table.get(
                            row,
                            "wave_count"
                    );

            boolean complete =
                    "true".equalsIgnoreCase(
                            table.get(
                                    row,
                                    "observed_from_start"
                            ).strip()
                    )
                            && "3".equals(
                                    waveCount.strip()
                            )
                            && !endPlayers.isBlank();

            model.put(
                    "complete_game",
                    Boolean.toString(
                            complete
                    )
            );

            if (table.get(
                    row,
                    "recorder_moving"
            ).isBlank()) {

                model.put(
                        "data_quality_notes",
                        "pre-v0.6 live row: recorder exclusion not measured"
                );
            }

            out.add(model);
        }

        return out;
    }

    private static void putLiveWave(
            Map<String, String> model,
            CsvTable table,
            List<String> row,
            int wave
    ) {
        String prefix =
                "wave_" + wave + "_";

        copy(
                model,
                prefix + "time_left",
                table,
                row,
                prefix + "time_left"
        );

        String atRoll =
                firstNonBlank(
                        table.get(
                                row,
                                prefix + "players_at_roll_model"
                        ),
                        table.get(
                                row,
                                prefix + "players_at_roll"
                        )
                );

        String after =
                firstNonBlank(
                        table.get(
                                row,
                                prefix + "players_after_model"
                        ),
                        table.get(
                                row,
                                prefix + "players_after"
                        )
                );

        model.put(
                prefix + "players_at_roll",
                atRoll
        );

        copy(
                model,
                prefix + "disasters",
                table,
                row,
                prefix + "disasters"
        );

        model.put(
                prefix + "players_after",
                after
        );

        String deaths =
                subtractStrings(
                        atRoll,
                        after
                );

        model.put(
                prefix + "deaths",
                deaths
        );

        String survival =
                fractionStrings(
                        after,
                        atRoll
                );

        model.put(
                prefix + "survival_fraction",
                survival
        );

        model.put(
                prefix + "mortality_fraction",
                mortalityFromSurvival(
                        survival
                )
        );
    }

    private static Map<String, String>
    blankModelRow() {
        Map<String, String> row =
                new LinkedHashMap<>();

        for (String column :
                MODEL_COLUMNS) {
            row.put(column, "");
        }

        return row;
    }

    private static void copy(
            Map<String, String> target,
            String targetColumn,
            CsvTable source,
            List<String> row,
            String sourceColumn
    ) {
        target.put(
                targetColumn,
                source.get(
                        row,
                        sourceColumn
                )
        );
    }

    private static String firstNonBlank(
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

    private static String mortalityFromSurvival(
            String survival
    ) {
        if (survival == null
                || survival.isBlank()) {
            return "";
        }

        try {
            double value =
                    Double.parseDouble(
                            survival.strip()
                    );

            return Double.toString(
                    1.0 - value
            );
        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    private static String subtractStrings(
            String a,
            String b
    ) {
        if (a == null
                || b == null
                || a.isBlank()
                || b.isBlank()) {
            return "";
        }

        try {
            int left =
                    Integer.parseInt(
                            a.strip()
                    );

            int right =
                    Integer.parseInt(
                            b.strip()
                    );

            return Integer.toString(
                    left - right
            );
        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    private static String fractionStrings(
            String numerator,
            String denominator
    ) {
        if (numerator == null
                || denominator == null
                || numerator.isBlank()
                || denominator.isBlank()) {
            return "";
        }

        try {
            double top =
                    Double.parseDouble(
                            numerator.strip()
                    );

            double bottom =
                    Double.parseDouble(
                            denominator.strip()
                    );

            if (bottom == 0.0) {
                return "";
            }

            return Double.toString(
                    top / bottom
            );

        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    private static List<String>
    combinedDisasters(
            String... waveStrings
    ) {
        List<String> out =
                new ArrayList<>();

        for (String wave :
                waveStrings) {

            if (wave == null
                    || wave.isBlank()) {
                continue;
            }

            for (String part :
                    wave.split(",")) {

                String cleaned =
                        part.strip();

                if (!cleaned.isBlank()) {
                    out.add(cleaned);
                }
            }
        }

        return out;
    }

    private static String normalizeDisasterList(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return "";
        }

        List<String> parts =
                new ArrayList<>();

        for (String part :
                value.split(",")) {

            String cleaned =
                    part.strip();

            if (!cleaned.isBlank()) {
                parts.add(cleaned);
            }
        }

        return String.join(
                " | ",
                parts
        );
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

            String line =
                    lines.get(i);

            if (line.isBlank()) {
                continue;
            }

            rows.add(
                    parseCsvLine(line)
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

    private static void writeModelCsvAtomically(
            List<Map<String, String>> rows
    ) throws IOException {

        StringBuilder csv =
                new StringBuilder();

        csv.append(
                String.join(
                        ",",
                        MODEL_COLUMNS
                )
        ).append(
                System.lineSeparator()
        );

        for (Map<String, String> row :
                rows) {

            List<String> cells =
                    new ArrayList<>();

            for (String column :
                    MODEL_COLUMNS) {

                cells.add(
                        csvEscape(
                                row.getOrDefault(
                                        column,
                                        ""
                                )
                        )
                );
            }

            csv.append(
                    String.join(
                            ",",
                            cells
                    )
            ).append(
                    System.lineSeparator()
            );
        }

        Path temp =
                OUTPUT_DIR.resolve(
                        "model_dataset.csv.tmp"
                );

        Files.writeString(
                temp,
                csv.toString(),
                StandardCharsets.UTF_8
        );

        try {
            Files.move(
                    temp,
                    MODEL_FILE,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(
                    temp,
                    MODEL_FILE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static String csvEscape(
            String value
    ) {
        if (value == null) {
            value = "";
        }

        return "\""
                + value.replace(
                        "\"",
                        "\"\""
                )
                + "\"";
    }

    private record Config(
            boolean includeLegacyDataForModel,
            boolean recorderMoving,
            boolean overlay1Enabled,
            boolean overlay2Enabled,
            boolean disasterDeathsOverlayEnabled,
            boolean recorderAliveOverlayEnabled,
            boolean gamePercentileOverlayEnabled,
            double predictionWindowSeconds,
            boolean percentageBasedAverages,
            String waveSpecificMode,
            String predictionModel
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
