package dev.perpetualyt.disasters;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DisastersTrackerClient implements ClientModInitializer {

    public static final String MOD_ID = "perphet";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Gson GSON = new Gson();

    private static final Comparator<PlayerScoreEntry> SCORE_DISPLAY_ORDER =
            Comparator.comparingInt(PlayerScoreEntry::value)
                    .reversed()
                    .thenComparing(
                            PlayerScoreEntry::owner,
                            String.CASE_INSENSITIVE_ORDER
                    );

    private static final Pattern TIME_PATTERN =
            Pattern.compile("^Time Left:\\s*(\\d+):(\\d{2})$", Pattern.CASE_INSENSITIVE);

    private static final Pattern ALIVE_PATTERN =
            Pattern.compile("^Players Alive:\\s*(\\d+)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern LOBBY_PLAYERS_PATTERN =
            Pattern.compile("^Players:\\s*(\\d+)/(\\d+)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern MAP_PATTERN =
            Pattern.compile("^Map:\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern HEADER_PATTERN =
            Pattern.compile("^\\d{2}/\\d{2}/\\d{2}(?:\\s+.*)?$");

    private static final Pattern NEXT_DISASTER_PATTERN =
            Pattern.compile("^Next disaster in \\d+s$", Pattern.CASE_INSENSITIVE);

    private static int tickCounter = 0;
    private static String lastFingerprint = "";

    /*
     * A brief scoreboard objective/title swap must not erase live prediction
     * boxes, but a permanent non-DISASTERS scoreboard must not leave stale
     * overlays around forever.
     */
    private static final long SIDEBAR_GAP_GRACE_MS = 2000L;
    private static long sidebarGapStartedAtMs = 0L;

    // Current match state
    private static boolean matchActive = false;
    private static boolean gameFinalized = false;

    private static String currentGameId = null;
    private static String gameStartTimestamp = null;
    private static String currentHeader = null;
    private static String currentMap = null;

    /*
     * Raw scoreboard counts include the recorder.
     * Model counts obey the recorder_moving toggle:
     *   OFF -> exclude recorder while recorder is alive
     *   ON  -> leave counts as raw totals for now
     */
    private static Integer gameStartPlayersRaw = null;
    private static Integer gameStartPlayersModel = null;

    /*
     * Health audit layer. Start total health is deterministic on the modeled
     * basis. Per-wave health comes from the tab-list health objective. The
     * 16 per-player slots follow the tab-list player order captured at game
     * start and remain fixed for the rest of the game.
     */
    private static final int TAB_HEALTH_SLOTS = 16;
    private static Integer gameStartTotalHealth = null;
    private static final List<String> tabHealthRoster = new ArrayList<>();

    private static Integer lobbyCapacity = null;
    private static Integer previousTimeLeft = null;

    private static Integer lastPlayersAliveRaw = null;
    private static Integer lastPlayersAliveModel = null;
    private static boolean lastRecorderAlive = true;

    /*
     * Recorder-death detection is driven by Hypixel's server chat messages.
     * We intentionally ignore any message containing a colon (normal player
     * chat such as "name: message") and, during an active match, treat a
     * remaining server-chat line containing the recorder username as the
     * recorder's elimination message. Once detected, model counts stop
     * subtracting one from the raw Players Alive scoreboard count.
     */
    private static boolean recorderDeathDetected = false;
    private static String recorderDeathMessage = null;
    private static String recorderDeathTimestamp = null;

    /*
     * Werewolf accounting:
     *
     * While the Werewolf disaster is active, one player is temporarily
     * removed from the model population. The raw Hypixel scoreboard is never
     * changed. When the exact red/green "WEREWOLF! The Werewolf has been
     * killed!" server-chat message arrives, the extra -1 is removed so that
     * the werewolf's actual death is not counted twice.
     */
    private static boolean werewolfAdjustmentActive = false;
    private static boolean werewolfKilledMessageDetected = false;

    /*
     * Username selected as the Werewolf for the current match.
     * This is learned from Hypixel's visible non-colon announcement:
     *
     *   WEREWOLF! <player> has become a Werewolf! Run away!
     */
    private static String werewolfPlayerName = null;

    /*
     * Internal-only identity relation. Needed because Hypixel continues to
     * include the Werewolf in Players Alive while it is active, but our
     * ranking excludes that transformed player.
     */
    private static boolean recorderIsWerewolfForGame = false;

    /*
     * Becoming the Werewolf counts as one Werewolf-attributed death in our
     * survivor-ranking semantics: that player leaves the normal survivor pool
     * and becomes an environmental hazard.
     *
     * Keep a pending flag because the selection chat announcement and the
     * scoreboard disaster registration can arrive in either order.
     */
    private static boolean werewolfTransformationDeathPending = false;
    private static boolean werewolfTransformationDeathCounted = false;
    private static String werewolfSelectionMessage = null;

    /*
     * Hypixel can send the selected-Werewolf announcement a fraction of a
     * second BEFORE the first in-game scoreboard snapshot flips matchActive.
     * Keep that exact announcement across beginGame/resetCurrentMatch so the
     * transformation +1 is not lost.
     */
    private static String pendingPreGameWerewolfPlayerName = null;
    private static String pendingPreGameWerewolfSelectionMessage = null;

    private static final Pattern DISASTER_PREDICTION_CHAT_PATTERN =
            Pattern.compile(
                    "^(?:.*\\s)?([A-Za-z0-9_]{1,16}):\\s*!(?:disasterpred|dpred)\\s*([0-9]+(?:\\.[0-9]+)?)(?=\\s|$).*$",
                    Pattern.CASE_INSENSITIVE
            );

    private static final Pattern WEREWOLF_SELECTED_PATTERN =
            Pattern.compile(
                    "^WEREWOLF! ([A-Za-z0-9_]{1,16}) has become a Werewolf! Run away!$"
            );

    /*
     * Player-vs-player death line. Only the Minecraft username immediately
     * after "was killed by" is treated as the killer; any trailing distance
     * or UI text is ignored.
     */
    private static final Pattern PLAYER_KILLED_BY_PATTERN =
            Pattern.compile(
                    "^([A-Za-z0-9_]{1,16}) was killed by ([A-Za-z0-9_]{1,16})(?:\\.|\\s|$).*$"
            );

    private static final String WEREWOLF_KILLED_TEXT =
            "WEREWOLF! The Werewolf has been killed!";

    private static final String WEREWOLF_LEFT_TEXT =
            "WEREWOLF! The Werewolf has left the game!";

    private static final int CHAT_RED_RGB = 0xFF5555;
    private static final int CHAT_GREEN_RGB = 0x55FF55;


    private static boolean observedFromStart = false;
    private static boolean recorderMovingForGame = false;
    private static String playerCountBasisForGame = "excluding_recorder_when_alive";

    private static final Set<String> seenDisasters = new LinkedHashSet<>();

    /*
     * Live chat-attribution state. When a disaster first appears on the
     * scoreboard, remember its visible name plus the legacy Minecraft color
     * code Hypixel uses for that disaster. Non-colon chat lines are then
     * counted only when the disaster name appears with the same color.
     */
    private static final Map<String, TrackedDisaster> trackedDisasters =
            new LinkedHashMap<>();

    /*
     * Current active-disaster set for chat aliases that should only apply
     * while a disaster is active. This differs from trackedDisasters, which
     * intentionally remembers every disaster that appeared in the match.
     */
    private static final Set<String> activeDisastersForChat =
            new LinkedHashSet<>();

    /*
     * Catch-all attribution requested by the experiment:
     * every active-game, non-colon text-chat line that does not match any
     * currently tracked disaster name+color is counted under "Chats".
     */
    private static int unmatchedChatMessages = 0;

    /*
     * Non-disaster environmental fall deaths. These are intentionally kept
     * separate from Chats so the CSV can distinguish ordinary falls/void-like
     * deaths from unattributed non-colon messages.
     */
    private static int fallChatDeaths = 0;

    /*
     * Non-disaster environmental deaths tracked separately from Chats.
     */
    private static int voidChatDeaths = 0;
    private static int miscChatDeaths = 0;

    private static final List<WaveState> waves = new ArrayList<>();

    /*
     * End-of-game predictions emitted immediately when each real disaster
     * wave rolls. Null means the model did not have enough compatible rows.
     */
    private static final List<Integer> endPlayerPredictions = new ArrayList<>();

    /*
     * Same model predictions before integer display rounding. TTR uses these
     * fractional values; UI/CSV prediction columns stay integer.
     */
    private static final List<Double> endPlayerPredictionRawValues =
            new ArrayList<>();

    /*
     * Optional player prediction contest. Chat input is read only; the mod
     * never sends chat/commands/network packets.
     */
    private static final Map<String, UserPredictionState> userPredictions =
            new LinkedHashMap<>();

    /*
     * Session-only completed-game stats logging switch.
     *
     * /dpred logs off
     * /disasterpred logs off
     *
     * disable persistence of completed games into games.csv/games.jsonl so
     * private/test/weird matches cannot pollute the model dataset. Raw debug
     * telemetry (scoreboard/chat JSONL and latest.log) is intentionally left
     * alone; this switch is specifically for structured game statistics.
     *
     * Once a current match is marked suppressed, turning logging back ON does
     * not resurrect that partially observed match. Logging resumes with the
     * next game.
     */
    private static boolean gameStatsLoggingEnabled = true;
    private static boolean suppressCurrentGameStats = false;

    private static ModelDataManager.PredictionModel
            predictionModelForGame =
            ModelDataManager.PredictionModel.KNN;

    private static boolean percentageBasedForGame = false;

    /*
     * Keep the legacy boolean for games.csv compatibility:
     * OFF=false; ON/HYPERSPECIFIC/MAP_HYPERSPECIFIC/AUTO=true.
     * The exact mode is frozen separately and encoded in prediction_model /
     * games.jsonl.
     */
    private static boolean waveSpecificForGame = true;

    private static ModelDataManager.WaveSpecificMode
            waveSpecificModeForGame =
            ModelDataManager.WaveSpecificMode.ON;

    // Pre-game lobby state
    private static String pendingMap = null;
    private static Integer pendingLobbyPlayersRaw = null;
    private static Integer pendingCapacity = null;

    // Output
    private static final Path OUTPUT_DIRECTORY =
            FabricLoader.getInstance().getGameDir().resolve("disasters-data");

    private static final Path RAW_OUTPUT_FILE =
            OUTPUT_DIRECTORY.resolve("scoreboard_raw.jsonl");

    private static final Path TIMELINE_OUTPUT_FILE =
            OUTPUT_DIRECTORY.resolve("hypixel_disasters.jsonl");

    private static final Path RECORDER_DEATH_OUTPUT_FILE =
            OUTPUT_DIRECTORY.resolve("recorder_death_detection.jsonl");

    private static final Path DISASTER_DEATH_OUTPUT_FILE =
            OUTPUT_DIRECTORY.resolve("disaster_chat_deaths.jsonl");

    private static final Path GAMES_JSONL_FILE =
            OUTPUT_DIRECTORY.resolve("games.jsonl");

    private static final Path GAMES_CSV_FILE =
            OUTPUT_DIRECTORY.resolve("games.csv");

    /*
     * Fixed one-column-per-disaster death-attribution schema.
     *
     * These are TOTAL attributed deaths over the whole game, not merely the
     * deaths between wave-roll timestamps. This captures lingering hazards
     * such as residual Floor Is Lava lava after the disaster becomes inactive.
     *
     * Solar Flare is included for forward compatibility even though it is
     * currently disabled.
     */
    private static final List<DisasterDeathCsvField>
            DISASTER_DEATH_CSV_FIELDS =
            List.of(
                    new DisasterDeathCsvField(
                            "Acid Rain",
                            "deaths_acid_rain"
                    ),
                    new DisasterDeathCsvField(
                            "Anvil Rain",
                            "deaths_anvil_rain"
                    ),
                    new DisasterDeathCsvField(
                            "Bat Swarm",
                            "deaths_bat_swarm"
                    ),
                    new DisasterDeathCsvField(
                            "Blackout",
                            "deaths_blackout"
                    ),
                    new DisasterDeathCsvField(
                            "Disco",
                            "deaths_disco"
                    ),
                    new DisasterDeathCsvField(
                            "Dragons",
                            "deaths_dragons"
                    ),
                    new DisasterDeathCsvField(
                            "Flood",
                            "deaths_flood"
                    ),
                    new DisasterDeathCsvField(
                            "Fragile Ground",
                            "deaths_fragile_ground"
                    ),
                    new DisasterDeathCsvField(
                            "Grounded",
                            "deaths_grounded"
                    ),
                    new DisasterDeathCsvField(
                            "Half Health",
                            "deaths_half_health"
                    ),
                    new DisasterDeathCsvField(
                            "Hot Potato",
                            "deaths_hot_potato"
                    ),
                    new DisasterDeathCsvField(
                            "Hypixel Says",
                            "deaths_hypixel_says"
                    ),
                    new DisasterDeathCsvField(
                            "Lightning",
                            "deaths_lightning"
                    ),
                    new DisasterDeathCsvField(
                            "Meteor Shower",
                            "deaths_meteor_shower"
                    ),
                    new DisasterDeathCsvField(
                            "Nuke",
                            "deaths_nuke"
                    ),
                    new DisasterDeathCsvField(
                            "Purge",
                            "deaths_purge"
                    ),
                    new DisasterDeathCsvField(
                            "Red Light, Green Light",
                            "deaths_red_light_green_light"
                    ),
                    new DisasterDeathCsvField(
                            "Sinkhole",
                            "deaths_sinkhole"
                    ),
                    new DisasterDeathCsvField(
                            "Solar Flare",
                            "deaths_solar_flare"
                    ),
                    new DisasterDeathCsvField(
                            "Stampede",
                            "deaths_stampede"
                    ),
                    new DisasterDeathCsvField(
                            "Swappage",
                            "deaths_swappage"
                    ),
                    new DisasterDeathCsvField(
                            "The Floor is Lava",
                            "deaths_the_floor_is_lava"
                    ),
                    new DisasterDeathCsvField(
                            "TNT Rain",
                            "deaths_tnt_rain"
                    ),
                    new DisasterDeathCsvField(
                            "Tornado",
                            "deaths_tornado"
                    ),
                    new DisasterDeathCsvField(
                            "Werewolf",
                            "deaths_werewolf"
                    ),
                    new DisasterDeathCsvField(
                            "Withers",
                            "deaths_withers"
                    ),
                    new DisasterDeathCsvField(
                            "Zombie Apocalypse",
                            "deaths_zombie_apocalypse"
                    )
            );

    /*
     * v0.4/v0.5 live CSV columns are kept as the raw-total audit layer.
     * Newer analysis/model columns are appended so old rows can be migrated
     * without changing their meaning.
     */
    private static final List<String> EXTRA_CSV_COLUMNS =
            buildExtraCsvColumns();

    private static List<String> buildExtraCsvColumns() {
        List<String> columns =
                new ArrayList<>(
                        List.of(
            "recorder_moving",
            "player_count_basis",
            "recorder_alive_at_game_start",
            "game_start_players_model",
            "before_wave_1_losses_total_raw",
            "before_wave_1_losses_model",

            "recorder_alive_wave_1_roll",
            "wave_1_players_at_roll_model",
            "wave_1_players_after_model",

            "recorder_alive_wave_2_roll",
            "wave_2_players_at_roll_model",
            "wave_2_players_after_model",

            "recorder_alive_wave_3_roll",
            "wave_3_players_at_roll_model",
            "wave_3_players_after_model",

            "recorder_alive_end",
            "end_players_model",
            "total_deaths_model",
            "end_survival_fraction_model",

            "prediction_model",
            "percentage_based_averages",
            "wave_specific",
            "prediction_wave_1_end_players",
            "prediction_wave_2_end_players",
            "prediction_wave_3_end_players",
            "ticket_to_ride_loss",
            "complete_game",
                            "disaster_chat_deaths",
                            "fall",
                            "void",
                            "misc",

                            "wave_1_fall",
                            "wave_1_void",
                            "wave_1_misc",
                            "wave_2_fall",
                            "wave_2_void",
                            "wave_2_misc",
                            "wave_3_fall",
                            "wave_3_void",
                            "wave_3_misc"
                    )
                );

        for (int wave = 1;
             wave <= 3;
             wave++) {

            columns.add(
                    "wave_"
                            + wave
                            + "_active_disasters"
            );

            for (DisasterDeathCsvField field :
                    DISASTER_DEATH_CSV_FIELDS) {

                columns.add(
                        "wave_"
                                + wave
                                + "_"
                                + field.columnName()
                );
            }
        }

        for (DisasterDeathCsvField field :
                DISASTER_DEATH_CSV_FIELDS) {

            columns.add(
                    field.columnName()
            );
        }

        /*
         * Schema 8 health audit columns are appended at the end so old rows
         * retain their positional meaning during migration.
         */
        columns.add("game_start_total_health");
        columns.add("wave_1_total_health");
        columns.add("wave_2_total_health");
        columns.add("wave_3_total_health");

        for (int wave = 1; wave <= 3; wave++) {
            for (int slot = 1; slot <= TAB_HEALTH_SLOTS; slot++) {
                columns.add(
                        String.format(
                                Locale.ROOT,
                                "wave_%d_player_%02d_health",
                                wave,
                                slot
                        )
                );
            }
        }

        return List.copyOf(columns);
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("Perphet v0.1.0-beta.1 initialized.");
        LOGGER.info("Game dataset: {}", GAMES_CSV_FILE);

        removeObsoleteLiveGamesCsvColumn(
                "werewolf_player"
        );

        migrateLiveGamesCsvIfNeeded();
        ModelDataManager.initialize();
        PredictionOverlay.initialize();
        registerClientCommands();

        /*
         * GAME receives server/system chat messages. We only inspect them;
         * we do not cancel, modify, or hide anything in Minecraft's chat.
         */
        ClientReceiveMessageEvents.GAME.register(
                DisastersTrackerClient::handleServerGameMessage
        );

        /*
         * Also inspect ordinary visible player-chat lines. The colon filter
         * below removes the normal "username: message" traffic before the
         * recorder-username test is applied.
         */
        ClientReceiveMessageEvents.CHAT.register(
                (message, playerChatMessage, sender, boundChatType, timeStamp) ->
                        handleServerGameMessage(message, false)
        );

        LOGGER.info("Model dataset: {}", ModelDataManager.getModelDatasetPath());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            tickCounter++;

            // 5 Hz scoreboard sampling.
            if (tickCounter % 4 != 0) {
                return;
            }

            captureSidebar(client);
        });
    }

    private static void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, buildContext) -> {
                    registerStatsLoggingCommand(
                            dispatcher,
                            "dpred"
                    );

                    registerStatsLoggingCommand(
                            dispatcher,
                            "disasterpred"
                    );
                }
        );
    }

    private static void registerStatsLoggingCommand(
            CommandDispatcher<FabricClientCommandSource> dispatcher,
            String root
    ) {
        dispatcher.register(
                ClientCommands.literal(root)
                        .requires(
                                FabricClientCommandSource::attended
                        )
                        .then(
                                ClientCommands.literal("logs")
                                        .executes(
                                                context ->
                                                        reportStatsLoggingStatus(
                                                                context.getSource()
                                                        )
                                        )
                                        .then(
                                                ClientCommands.literal("status")
                                                        .executes(
                                                                context ->
                                                                        reportStatsLoggingStatus(
                                                                                context.getSource()
                                                                        )
                                                        )
                                        )
                                        .then(
                                                ClientCommands.literal("off")
                                                        .executes(
                                                                context ->
                                                                        setStatsLoggingFromCommand(
                                                                                context.getSource(),
                                                                                false
                                                                        )
                                                        )
                                        )
                                        .then(
                                                ClientCommands.literal("on")
                                                        .executes(
                                                                context ->
                                                                        setStatsLoggingFromCommand(
                                                                                context.getSource(),
                                                                                true
                                                                        )
                                                        )
                                        )
                        )
                        .then(
                                ClientCommands.literal("delete-last")
                                        .executes(
                                                context ->
                                                        deleteLastSavedGame(
                                                                context.getSource()
                                                        )
                                        )
                        )
        );
    }

    private static int setStatsLoggingFromCommand(
            FabricClientCommandSource source,
            boolean enabled
    ) {
        gameStatsLoggingEnabled = enabled;

        if (!enabled
                && matchActive
                && !gameFinalized) {
            /*
             * Exclude the entire current game, even if logging is re-enabled
             * before it ends. Otherwise a deliberately weird/private partial
             * match could still leak into the training CSV.
             */
            suppressCurrentGameStats = true;
        }

        if (enabled) {
            if (matchActive
                    && !gameFinalized
                    && suppressCurrentGameStats) {

                source.sendFeedback(
                        Component.literal(
                                "Perphet stats logging ON for future games; current game remains excluded."
                        )
                );

            } else {
                source.sendFeedback(
                        Component.literal(
                                "Perphet stats logging ON."
                        )
                );
            }

        } else {
            source.sendFeedback(
                    Component.literal(
                            matchActive && !gameFinalized
                                    ? "Perphet stats logging OFF; current game will not be saved to games.csv/games.jsonl."
                                    : "Perphet stats logging OFF; upcoming games will not be saved to games.csv/games.jsonl."
                    )
            );
        }

        LOGGER.info(
                "Completed-game stats logging changed: enabled={} suppressCurrentGame={}",
                gameStatsLoggingEnabled,
                suppressCurrentGameStats
        );

        return 1;
    }

    private static int reportStatsLoggingStatus(
            FabricClientCommandSource source
    ) {
        String status =
                gameStatsLoggingEnabled
                        ? "ON"
                        : "OFF";

        String suffix =
                matchActive
                        && !gameFinalized
                        && suppressCurrentGameStats
                        ? " (current game excluded)"
                        : "";

        source.sendFeedback(
                Component.literal(
                        "Perphet stats logging: "
                                + status
                                + suffix
                )
        );

        return 1;
    }

    private static int deleteLastSavedGame(
            FabricClientCommandSource source
    ) {
        if (matchActive && !gameFinalized) {
            source.sendFeedback(
                    Component.literal(
                            "Perphet: cannot delete the last saved game while a game is active."
                    )
            );
            return 0;
        }

        try {
            if (!Files.exists(GAMES_CSV_FILE)
                    || Files.size(GAMES_CSV_FILE) == 0) {
                source.sendFeedback(
                        Component.literal(
                                "Perphet: games.csv has no saved games to delete."
                        )
                );
                return 0;
            }

            List<String> csvLines =
                    new ArrayList<>(
                            Files.readAllLines(
                                    GAMES_CSV_FILE,
                                    StandardCharsets.UTF_8
                            )
                    );

            if (csvLines.size() <= 1) {
                source.sendFeedback(
                        Component.literal(
                                "Perphet: games.csv has no saved games to delete."
                        )
                );
                return 0;
            }

            int lastRowIndex = csvLines.size() - 1;
            while (lastRowIndex > 0
                    && csvLines.get(lastRowIndex).isBlank()) {
                lastRowIndex--;
            }

            if (lastRowIndex <= 0) {
                source.sendFeedback(
                        Component.literal(
                                "Perphet: games.csv has no saved games to delete."
                        )
                );
                return 0;
            }

            List<String> header =
                    parseCsvCellsForMigration(
                            csvLines.get(0)
                    );

            List<String> row =
                    parseCsvCellsForMigration(
                            csvLines.get(lastRowIndex)
                    );

            int gameIdIndex = header.indexOf("game_id");
            int startIndex = header.indexOf("start_timestamp");
            int endIndex = header.indexOf("end_timestamp");
            int mapIndex = header.indexOf("map");

            if (gameIdIndex < 0
                    || gameIdIndex >= row.size()) {
                throw new IOException(
                        "games.csv is missing a usable game_id column"
                );
            }

            String gameId = row.get(gameIdIndex);
            String startTimestamp =
                    startIndex >= 0 && startIndex < row.size()
                            ? row.get(startIndex)
                            : null;
            String endTimestamp =
                    endIndex >= 0 && endIndex < row.size()
                            ? row.get(endIndex)
                            : null;
            String map =
                    mapIndex >= 0 && mapIndex < row.size()
                            ? row.get(mapIndex)
                            : null;

            csvLines.remove(lastRowIndex);
            while (csvLines.size() > 1
                    && csvLines.get(csvLines.size() - 1).isBlank()) {
                csvLines.remove(csvLines.size() - 1);
            }
            writeLinesAtomically(
                    GAMES_CSV_FILE,
                    csvLines
            );

            int removedGameJson =
                    removeJsonlEntriesForGameId(
                            GAMES_JSONL_FILE,
                            gameId
                    );
            int removedTimeline =
                    removeJsonlEntriesForGameId(
                            TIMELINE_OUTPUT_FILE,
                            gameId
                    );
            int removedDeaths =
                    removeJsonlEntriesForGameId(
                            DISASTER_DEATH_OUTPUT_FILE,
                            gameId
                    );
            int removedRecorder =
                    removeJsonlEntriesForGameId(
                            RECORDER_DEATH_OUTPUT_FILE,
                            gameId
                    );

            int removedRaw =
                    removeRawSnapshotsForGameWindow(
                            startTimestamp,
                            endTimestamp
                    );

            /*
             * model_dataset.csv is derived from games.csv, so rebuild it rather
             * than trying to surgically edit a second copy of the live row.
             */
            ModelDataManager.refreshModelDataset();

            /*
             * A deleted game can move the live sample count below a 20-game
             * Misc-correlation milestone. Drop both the in-memory and on-disk
             * cache so the next prediction refits from the surviving games.
             */
            MiscCorrelationModel.invalidateCache();

            int relatedLogRows =
                    removedGameJson
                            + removedTimeline
                            + removedDeaths
                            + removedRecorder
                            + removedRaw;

            source.sendFeedback(
                    Component.literal(
                            "Perphet: deleted last saved game "
                                    + gameId
                                    + (map == null || map.isBlank()
                                            ? ""
                                            : " (" + map + ")")
                                    + "; removed 1 games.csv row and "
                                    + relatedLogRows
                                    + " related JSONL rows. Model data rebuilt."
                    )
            );

            LOGGER.info(
                    "Deleted last saved game {} map={} jsonlRowsRemoved={} rawRowsRemoved={}",
                    gameId,
                    map,
                    removedGameJson
                            + removedTimeline
                            + removedDeaths
                            + removedRecorder,
                    removedRaw
            );

            return 1;

        } catch (Exception exception) {
            LOGGER.error(
                    "Could not delete last saved Perphet game",
                    exception
            );

            source.sendFeedback(
                    Component.literal(
                            "Perphet: could not delete the last saved game; check latest.log."
                    )
            );
            return 0;
        }
    }

    private static int removeJsonlEntriesForGameId(
            Path file,
            String gameId
    ) throws IOException {
        if (!Files.exists(file)
                || Files.size(file) == 0) {
            return 0;
        }

        List<String> lines =
                Files.readAllLines(
                        file,
                        StandardCharsets.UTF_8
                );

        List<String> kept =
                new ArrayList<>(lines.size());

        int removed = 0;

        for (String line : lines) {
            if (line.isBlank()) {
                kept.add(line);
                continue;
            }

            boolean matches = false;

            try {
                JsonObject object =
                        JsonParser.parseString(line)
                                .getAsJsonObject();

                matches =
                        object.has("gameId")
                                && !object.get("gameId").isJsonNull()
                                && gameId.equals(
                                        object.get("gameId")
                                                .getAsString()
                                );

            } catch (Exception ignored) {
                /* Preserve malformed/legacy diagnostic lines verbatim. */
            }

            if (matches) {
                removed++;
            } else {
                kept.add(line);
            }
        }

        if (removed > 0) {
            writeLinesAtomically(
                    file,
                    kept
            );
        }

        return removed;
    }

    private static int removeRawSnapshotsForGameWindow(
            String startTimestamp,
            String endTimestamp
    ) throws IOException {
        if (startTimestamp == null
                || startTimestamp.isBlank()
                || endTimestamp == null
                || endTimestamp.isBlank()
                || !Files.exists(RAW_OUTPUT_FILE)
                || Files.size(RAW_OUTPUT_FILE) == 0) {
            return 0;
        }

        Instant start =
                Instant.parse(startTimestamp)
                        .minusMillis(1000);
        Instant end =
                Instant.parse(endTimestamp);

        List<String> lines =
                Files.readAllLines(
                        RAW_OUTPUT_FILE,
                        StandardCharsets.UTF_8
                );

        List<String> kept =
                new ArrayList<>(lines.size());

        int removed = 0;

        for (String line : lines) {
            boolean inWindow = false;

            if (!line.isBlank()) {
                try {
                    JsonObject object =
                            JsonParser.parseString(line)
                                    .getAsJsonObject();

                    if (object.has("timestamp")
                            && !object.get("timestamp").isJsonNull()) {
                        Instant timestamp =
                                Instant.parse(
                                        object.get("timestamp")
                                                .getAsString()
                                );

                        inWindow =
                                !timestamp.isBefore(start)
                                        && !timestamp.isAfter(end);
                    }

                } catch (Exception ignored) {
                    /* Preserve malformed/legacy raw rows verbatim. */
                }
            }

            if (inWindow) {
                removed++;
            } else {
                kept.add(line);
            }
        }

        if (removed > 0) {
            writeLinesAtomically(
                    RAW_OUTPUT_FILE,
                    kept
            );
        }

        return removed;
    }

    private static void writeLinesAtomically(
            Path file,
            List<String> lines
    ) throws IOException {
        Files.createDirectories(file.getParent());

        Path temporary =
                file.resolveSibling(
                        file.getFileName()
                                + ".delete-last.tmp"
                );

        Files.write(
                temporary,
                lines,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        try {
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );

        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static void handleTemporarySidebarGap(
            String reason
    ) {
        if (PredictionOverlay.isFinalSummaryVisible()) {
            return;
        }

        if (!matchActive) {
            sidebarGapStartedAtMs = 0L;
            PredictionOverlay.clearAll();
            return;
        }

        long now =
                System.currentTimeMillis();

        if (sidebarGapStartedAtMs == 0L) {
            sidebarGapStartedAtMs = now;

            LOGGER.info(
                    "Preserving live overlay during temporary sidebar gap: {}",
                    reason
            );

            return;
        }

        long ageMs =
                now - sidebarGapStartedAtMs;

        if (ageMs < SIDEBAR_GAP_GRACE_MS) {
            return;
        }

        /*
         * A two-second non-DISASTERS gap is no longer treated as a transient
         * visual swap. Clear stale overlays and abandon the active-match state
         * rather than preserving it indefinitely.
         */
        LOGGER.info(
                "Sidebar gap persisted {} ms; clearing stale live overlay and resetting match ({})",
                ageMs,
                reason
        );

        sidebarGapStartedAtMs = 0L;
        PredictionOverlay.clearAll();
        resetCurrentMatch();
    }

    private static void captureSidebar(Minecraft client) {
        if (client.level == null) {
            lastFingerprint = "";
            sidebarGapStartedAtMs = 0L;

            /*
             * A recorder death does not require client.level to disappear.
             * A null level is a real world unload / disconnect / server
             * transition, so do not keep a stale active-game overlay forever.
             *
             * Preserve an already-created 5-second final summary, but reset
             * ordinary live-match state.
             */
            if (!PredictionOverlay.isFinalSummaryVisible()) {
                PredictionOverlay.clearAll();
            }

            if (matchActive) {
                resetCurrentMatch();
            }

            return;
        }

        Scoreboard scoreboard = client.level.getScoreboard();

        Objective objective =
                scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);

        if (objective == null) {
            lastFingerprint = "";

            handleTemporarySidebarGap(
                    "no sidebar objective"
            );

            return;
        }

        String title =
                stripFormatting(objective.getDisplayName().getString());

        if (!title.equalsIgnoreCase("DISASTERS")) {
            lastFingerprint = "";

            handleTemporarySidebarGap(
                    "sidebar title=" + title
            );

            return;
        }

        /*
         * A valid DISASTERS sidebar returned. Any temporary scoreboard gap is
         * over, so cancel the grace timer.
         */
        sidebarGapStartedAtMs = 0L;

        List<SidebarLine> lines =
                scoreboard.listPlayerScores(objective)
                        .stream()
                        .filter(entry -> !entry.isHidden())
                        .sorted(SCORE_DISPLAY_ORDER)
                        .limit(15)
                        .map(entry -> makeSidebarLine(scoreboard, entry))
                        .toList();

        String fingerprint = makeFingerprint(title, lines);

        if (fingerprint.equals(lastFingerprint)) {
            return;
        }

        lastFingerprint = fingerprint;

        appendJson(
                RAW_OUTPUT_FILE,
                new RawSnapshot(
                        Instant.now().toString(),
                        title,
                        lines
                )
        );

        ParsedSidebar parsed = parseSidebar(lines);

        // Waiting lobby
        if (parsed.map() != null && parsed.timeLeftSeconds() == null) {

            if (matchActive
                    && !gameFinalized
                    && shouldFinalizeWithoutZero()) {

                if (isWerewolfWinState(
                        lastPlayersAliveRaw
                )) {
                    lastPlayersAliveModel = 0;

                    finalizeLastWave(
                            lastPlayersAliveRaw,
                            0
                    );

                    LOGGER.info(
                            "Finalizing Werewolf win on lobby transition: raw end={} modeled end=0",
                            lastPlayersAliveRaw
                    );

                } else {
                    finalizeLastWave(
                            lastPlayersAliveRaw,
                            lastPlayersAliveModel
                    );
                }

                saveCompletedGame();
            }

            pendingMap = parsed.map();

            pendingPreGameWerewolfPlayerName = null;
            pendingPreGameWerewolfSelectionMessage = null;

            if (parsed.lobbyPlayers() != null) {
                pendingLobbyPlayersRaw = parsed.lobbyPlayers();
            }

            if (parsed.lobbyCapacity() != null) {
                pendingCapacity = parsed.lobbyCapacity();
            }

            if (!PredictionOverlay.isFinalSummaryVisible()) {
                PredictionOverlay.clearAll();
            }

            resetCurrentMatch();
            return;
        }

        /*
         * All-dead completion must be checked BEFORE the generic transition
         * filter. Hypixel can replace/remove the disaster list immediately
         * after the last normal survivor dies, while still briefly exposing
         * Players Alive: 0.
         *
         * v0.9.57 filtered that snapshot out on !hasDisastersSection(), so
         * saveCompletedGame() never ran and the final TTR overlay disappeared.
         */
        if (matchActive
                && !gameFinalized
                && parsed.playersAlive() != null
                && parsed.playersAlive() == 0) {

            lastPlayersAliveRaw = 0;
            lastPlayersAliveModel = 0;
            lastRecorderAlive = false;

            PredictionOverlay.setRecorderAlive(false);

            finalizeLastWave(
                    0,
                    0
            );

            LOGGER.info(
                    "Detected all-dead transition state before disaster-section filter; finalizing actual=0 for TTR/overlay"
            );

            saveCompletedGame();
            return;
        }

        // Ignore transition states.
        if (parsed.timeLeftSeconds() == null
                || !parsed.hasDisastersSection()) {
            return;
        }

        // Game start
        if (isNewGame(parsed)) {
            beginGame(parsed, client);
        }

        /*
         * Capture the 16-player tab order as early as possible. If the LIST
         * health objective is briefly unavailable at game start, retry on
         * subsequent valid DISASTERS snapshots until Wave 1.
         */
        if (tabHealthRoster.isEmpty()) {
            ensureTabHealthRoster(
                    client,
                    gameStartPlayersRaw
            );
        }

        activeDisastersForChat.clear();

        for (String disaster :
                realDisasters(
                        parsed.activeDisasters()
                )) {

            activeDisastersForChat.add(
                    disaster.toLowerCase(
                            Locale.ROOT
                    )
            );
        }

        boolean recorderAliveNow = isRecorderAlive(client);

        PredictionOverlay.setRecorderAlive(
                recorderAliveNow
        );

        // Newly rolled disasters
        List<String> newlySeen = new ArrayList<>();

        for (String disaster : parsed.allDisasters()) {
            if (!isRealDisaster(disaster)) {
                continue;
            }

            if (seenDisasters.add(disaster)) {
                newlySeen.add(disaster);
            }
        }

        /*
         * Register every newly-added disaster with the exact scoreboard color
         * code Hypixel used when it appeared. This is later used to attribute
         * non-colon death-chat lines to disasters.
         */
        for (String disaster : newlySeen) {
            TrackedDisaster tracked =
                    makeTrackedDisaster(
                            lines,
                            disaster
                    );

            trackedDisasters.put(
                    disaster.toLowerCase(Locale.ROOT),
                    tracked
            );

            PredictionOverlay.registerDisaster(
                    tracked.name,
                    tracked.colorRgb,
                    tracked.colorPattern
            );

            LOGGER.info(
                    "Tracking disaster chat deaths: {} colorCode={} rgb={} colorPattern={}",
                    tracked.name,
                    tracked.colorCode == null
                            ? "UNKNOWN"
                            : "§" + tracked.colorCode,
                    tracked.colorRgb,
                    tracked.colorPattern
            );

        }

        /*
         * Hypixel scoreboard semantics:
         *
         * while the transformed Werewolf is active, Hypixel STILL includes
         * that player in Players Alive. Our ranking does not. Therefore the
         * model/ranking count needs an extra -1 while Hypixel's raw count
         * still contains the Werewolf.
         *
         * Once the Werewolf is killed, Hypixel's raw Players Alive count
         * itself drops by one, so this extra adjustment must turn OFF. That
         * does NOT mean we add the player back; it prevents double-subtracting
         * a player Hypixel has already removed from the raw count.
         */
        if (newlySeen.stream()
                .anyMatch(
                        disaster ->
                                disaster.equalsIgnoreCase(
                                        "Werewolf"
                                )
                )) {

            werewolfAdjustmentActive =
                    !(
                            !recorderMovingForGame
                                    && recorderIsWerewolfForGame
                    );

            LOGGER.info(
                    "Werewolf ranking exclusion activated={} (recorderIsWerewolf={})",
                    werewolfAdjustmentActive,
                    recorderIsWerewolfForGame
            );
        }

        Integer playersAliveRaw = parsed.playersAlive();
        Integer playersAliveModel =
                analysisPlayerCount(
                        playersAliveRaw,
                        recorderAliveNow,
                        recorderMovingForGame
                );

        if (!newlySeen.isEmpty()) {
            if (!waves.isEmpty()) {
                finalizeLastWave(
                        playersAliveRaw,
                        playersAliveModel
                );
            }

            TabHealthSnapshot healthAtRoll =
                    tabListHealthSnapshot(
                            client,
                            recorderAliveNow,
                            playersAliveRaw,
                            playersAliveModel
                    );

            waves.add(
                    new WaveState(
                            waves.size() + 1,
                            parsed.timeLeftSeconds(),
                            playersAliveRaw,
                            playersAliveModel,
                            healthAtRoll == null
                                    ? null
                                    : healthAtRoll.modeledTotalHealth(),
                            healthAtRoll == null
                                    ? List.of()
                                    : healthAtRoll.slotHealth(),
                            recorderAliveNow,
                            List.copyOf(newlySeen),
                            /*
                             * Intentional cumulative semantics: once a
                             * disaster has appeared, keep it eligible in later
                             * waves even if the scoreboard strikes it out.
                             * Residual hazards can still kill later (Floor Is
                             * Lava is the canonical example).
                             */
                            List.copyOf(
                                    seenDisasters
                            )
                    )
            );

            updateDerivedMiscOverlay(
                    playersAliveModel
            );

            maybeCountWerewolfTransformationDeath();

            LOGGER.info(
                    "Wave {}: {} totalHealth={} slotHealth={}",
                    waves.size(),
                    newlySeen,
                    healthAtRoll == null
                            ? null
                            : healthAtRoll.modeledTotalHealth(),
                    healthAtRoll == null
                            ? List.of()
                            : healthAtRoll.slotHealth()
            );

            List<List<String>> observedWaveDisasters =
                    waves.stream()
                            .map(wave -> wave.disasters)
                            .toList();

            List<List<String>> observedActiveDisastersByWave =
                    waves.stream()
                            .map(
                                    wave ->
                                            wave.activeDisastersAtRoll
                            )
                            .toList();

            List<Integer> observedWavePlayersAtRoll =
                    waves.stream()
                            .map(
                                    wave ->
                                            wave.playersAtRollModel
                            )
                            .toList();

            EndPlayerPredictor.Prediction prediction =
                    EndPlayerPredictor.predict(
                            predictionModelForGame,
                            waves.size(),
                            gameStartPlayersModel,
                            playersAliveModel,
                            currentMap,
                            playerCountBasisForGame,
                            observedWaveDisasters,
                            observedActiveDisastersByWave,
                            observedWavePlayersAtRoll,
                            /*
                             * Model DEDS uses every disaster seen so far,
                             * including expired/struck-through disasters.
                             */
                            List.copyOf(
                                    seenDisasters
                            ),
                            percentageBasedForGame,
                            waveSpecificModeForGame
                    );

            Integer predictedEndPlayers =
                    prediction == null
                            ? null
                            : prediction.predictedPlayers();

            endPlayerPredictions.add(predictedEndPlayers);

            endPlayerPredictionRawValues.add(
                    prediction == null
                            ? null
                            : prediction.rawPredictedPlayers()
            );

            PredictionOverlay.addPrediction(
                    waves.size(),
                    prediction
            );

            EndPlayerPredictor.PercentileResult percentile =
                    prediction == null
                            ? null
                            : EndPlayerPredictor
                            .predictedFinalSurvivorPercentile(
                                    prediction.rawPredictedPlayers(),
                                    playerCountBasisForGame
                            );

            PredictionOverlay.setGamePercentile(
                    percentile
            );

            LOGGER.info(
                    "Wave {} end-player prediction: {} model={} rows={} fallback={} exactMatch={} deathsThisWave={} stdev={} dedsByDisaster={} survivorPercentile={} percentileGames={}",
                    waves.size(),
                    predictedEndPlayers,
                    prediction == null
                            ? EndPlayerPredictor.modelId(
                                    predictionModelForGame,
                                    percentageBasedForGame,
                                    waveSpecificModeForGame
                            )
                            : prediction.modelId(),
                    prediction == null
                            ? 0
                            : prediction.trainingRows(),
                    prediction != null
                            && prediction.fallbackUsed(),
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
                            ? Map.of()
                            : prediction.deathsThisWaveByDisaster(),
                    percentile == null
                            ? null
                            : percentile.percentile(),
                    percentile == null
                            ? 0
                            : percentile.compatibleGames()
            );
        }

        // Timeline
        appendJson(
                TIMELINE_OUTPUT_FILE,
                new ParsedSnapshot(
                        Instant.now().toString(),
                        currentGameId,
                        currentHeader,
                        currentMap,
                        recorderMovingForGame,
                        playerCountBasisForGame,
                        gameStartPlayersRaw,
                        gameStartPlayersModel,
                        lobbyCapacity,
                        parsed.timeLeftSeconds(),
                        playersAliveRaw,
                        playersAliveModel,
                        recorderAliveNow,
                        realDisasters(parsed.allDisasters()),
                        realDisasters(parsed.activeDisasters()),
                        newlySeen
                )
        );

        if (parsed.header() != null) {
            currentHeader = parsed.header();
        }

        previousTimeLeft = parsed.timeLeftSeconds();

        if (playersAliveRaw != null) {
            updateDerivedMiscOverlay(
                    playersAliveModel
            );

            lastPlayersAliveRaw = playersAliveRaw;
            lastPlayersAliveModel = playersAliveModel;
            lastRecorderAlive = recorderAliveNow;
        }

        matchActive = true;

        // Normal completion
        if (!gameFinalized && parsed.timeLeftSeconds() == 0) {
            finalizeLastWave(
                    playersAliveRaw,
                    playersAliveModel
            );
            saveCompletedGame();
        }

        // Early completion if everybody dies.
        if (!gameFinalized
                && playersAliveRaw != null
                && playersAliveRaw == 0) {

            /*
             * Zero is authoritative here. Do not allow recorder/werewolf
             * bookkeeping to leave a nonzero model end count.
             */
            lastPlayersAliveRaw = 0;
            lastPlayersAliveModel = 0;
            lastRecorderAlive = false;

            PredictionOverlay.setRecorderAlive(false);

            finalizeLastWave(0, 0);
            saveCompletedGame();
        }

        /*
         * Early Werewolf victory:
         *
         * Hypixel can end the match before Time Left reaches 0 when the
         * transformed Werewolf is the sole remaining player. Raw Players Alive
         * is then 1 because Hypixel still counts the Werewolf, while our
         * ranking has 0 normal survivors.
         *
         * v0.9.55 only finalized on raw==0 / timer expiry, so these games could
         * jump to the lobby and reset without ever calling showFinalSummary().
         * That is why TTR disappeared specifically on Werewolf wins.
         */
        if (!gameFinalized
                && isWerewolfWinState(
                playersAliveRaw
        )) {

            lastPlayersAliveRaw = 1;
            lastPlayersAliveModel = 0;

            finalizeLastWave(
                    1,
                    0
            );

            LOGGER.info(
                    "Detected Werewolf win: raw Players Alive=1, modeled end=0; finalizing for TTR/overlay"
            );

            saveCompletedGame();
        }
    }

    private static ParsedSidebar parseSidebar(List<SidebarLine> lines) {
        String header = null;
        String map = null;

        Integer lobbyPlayers = null;
        Integer lobbyCapacityValue = null;

        Integer timeLeftSeconds = null;
        Integer playersAlive = null;

        boolean readingDisasters = false;
        boolean hasDisastersSection = false;

        List<String> allDisasters = new ArrayList<>();
        List<String> activeDisasters = new ArrayList<>();

        for (SidebarLine sidebarLine : lines) {
            String line = sidebarLine.text();

            if (line.isBlank()) {
                continue;
            }

            if (header == null
                    && HEADER_PATTERN.matcher(line).matches()) {
                header = line;
            }

            Matcher mapMatcher = MAP_PATTERN.matcher(line);
            if (mapMatcher.matches()) {
                map = mapMatcher.group(1).strip();
                continue;
            }

            Matcher lobbyMatcher = LOBBY_PLAYERS_PATTERN.matcher(line);
            if (lobbyMatcher.matches()) {
                lobbyPlayers = Integer.parseInt(lobbyMatcher.group(1));
                lobbyCapacityValue = Integer.parseInt(lobbyMatcher.group(2));
                continue;
            }

            Matcher timeMatcher = TIME_PATTERN.matcher(line);
            if (timeMatcher.matches()) {
                int minutes = Integer.parseInt(timeMatcher.group(1));
                int seconds = Integer.parseInt(timeMatcher.group(2));
                timeLeftSeconds = minutes * 60 + seconds;
                continue;
            }

            Matcher aliveMatcher = ALIVE_PATTERN.matcher(line);
            if (aliveMatcher.matches()) {
                playersAlive = Integer.parseInt(aliveMatcher.group(1));
                continue;
            }

            if (line.equalsIgnoreCase("Disasters:")) {
                readingDisasters = true;
                hasDisastersSection = true;
                continue;
            }

            if (readingDisasters) {
                if (line.toLowerCase(Locale.ROOT).contains("hypixel.net")) {
                    readingDisasters = false;
                    continue;
                }

                String canonicalDisaster =
                        canonicalDisasterName(
                                line
                        );

                if (canonicalDisaster == null) {
                    continue;
                }

                allDisasters.add(
                        canonicalDisaster
                );

                // Hypixel uses §m (strikethrough) for expired disasters.
                boolean inactive = sidebarLine.rawText().contains("§m");

                if (!inactive) {
                    activeDisasters.add(
                            canonicalDisaster
                    );
                }
            }
        }

        return new ParsedSidebar(
                header,
                map,
                lobbyPlayers,
                lobbyCapacityValue,
                timeLeftSeconds,
                playersAlive,
                hasDisastersSection,
                List.copyOf(allDisasters),
                List.copyOf(activeDisasters)
        );
    }

    private static boolean isNewGame(ParsedSidebar parsed) {
        if (!matchActive) {
            return true;
        }

        if (currentHeader != null
                && parsed.header() != null
                && !currentHeader.equals(parsed.header())) {
            return true;
        }

        return previousTimeLeft != null
                && parsed.timeLeftSeconds() > previousTimeLeft + 30;
    }

    private static void beginGame(
            ParsedSidebar parsed,
            Minecraft client
    ) {
        resetCurrentMatch();
        PredictionOverlay.resetForGame();

        unmatchedChatMessages = 0;
        fallChatDeaths = 0;
        voidChatDeaths = 0;
        miscChatDeaths = 0;

        PredictionOverlay.setDisasterDeathCount(
                "Chats",
                null,
                0
        );

        matchActive = true;

        suppressCurrentGameStats =
                !gameStatsLoggingEnabled;

        currentGameId = "game-" + System.currentTimeMillis();
        gameStartTimestamp = Instant.now().toString();
        currentHeader = parsed.header();
        currentMap = pendingMap;

        recorderMovingForGame =
                ModelDataManager.isRecorderMoving();

        /*
         * Freeze the selected predictor for this match. A Mod Menu change
         * during the game therefore applies to the NEXT game rather than
         * mixing different predictors across waves.
         */
        predictionModelForGame =
                ModelDataManager.getPredictionModel();

        percentageBasedForGame =
                ModelDataManager.isPercentageBasedAverages();

        waveSpecificModeForGame =
                ModelDataManager.getWaveSpecificMode();

        waveSpecificForGame =
                waveSpecificModeForGame
                        != ModelDataManager.WaveSpecificMode.OFF;

        playerCountBasisForGame =
                recorderMovingForGame
                        ? "total_including_recorder"
                        : "excluding_recorder_when_alive";

        /*
         * True game-start count must come from the first IN-GAME scoreboard
         * snapshot, not the last waiting-lobby snapshot. A lobby can still
         * fill between those two moments; using the lobby value produced
         * impossible negative start->wave-1 losses such as 14 -> 15.
         */
        gameStartPlayersRaw =
                parsed.playersAlive() != null
                        ? parsed.playersAlive()
                        : pendingLobbyPlayersRaw;

        boolean recorderAliveAtStart = true;

        gameStartPlayersModel =
                analysisPlayerCount(
                        gameStartPlayersRaw,
                        recorderAliveAtStart,
                        recorderMovingForGame
                );

        gameStartTotalHealth =
                gameStartPlayersModel == null
                        ? null
                        : gameStartPlayersModel * 20;

        ensureTabHealthRoster(
                client,
                gameStartPlayersRaw
        );

        lobbyCapacity = pendingCapacity;
        previousTimeLeft = parsed.timeLeftSeconds();

        boolean recorderAliveNow = isRecorderAlive(client);

        lastPlayersAliveRaw = parsed.playersAlive();
        lastPlayersAliveModel =
                analysisPlayerCount(
                        parsed.playersAlive(),
                        recorderAliveNow,
                        recorderMovingForGame
                );
        lastRecorderAlive = recorderAliveNow;

        observedFromStart =
                pendingLobbyPlayersRaw != null
                        || (parsed.timeLeftSeconds() != null
                        && parsed.timeLeftSeconds() >= 180);

        pendingMap = null;
        pendingLobbyPlayersRaw = null;
        pendingCapacity = null;

        if (pendingPreGameWerewolfPlayerName != null) {
            String selectedPlayer =
                    pendingPreGameWerewolfPlayerName;
            String selectionMessage =
                    pendingPreGameWerewolfSelectionMessage;

            pendingPreGameWerewolfPlayerName = null;
            pendingPreGameWerewolfSelectionMessage = null;

            applyWerewolfSelection(
                    selectedPlayer,
                    selectionMessage,
                    client
            );
        }

        LOGGER.info(
                "New game {} map={} rawStart={} modelStart={} startHealth={} tabHealthRosterSize={} recorderMoving={} predictionModel={} percentageBased={} waveMode={}",
                currentGameId,
                currentMap,
                gameStartPlayersRaw,
                gameStartPlayersModel,
                gameStartTotalHealth,
                tabHealthRoster.size(),
                recorderMovingForGame,
                EndPlayerPredictor.modelId(
                        predictionModelForGame,
                        percentageBasedForGame,
                        waveSpecificModeForGame
                ),
                percentageBasedForGame,
                waveSpecificModeForGame
        );
    }

    private static Objective tabListHealthObjective(
            Minecraft client
    ) {
        if (client == null
                || client.level == null) {
            return null;
        }

        Scoreboard scoreboard =
                client.level.getScoreboard();

        Objective healthObjective =
                scoreboard.getDisplayObjective(
                        DisplaySlot.LIST
                );

        if (healthObjective == null) {
            return null;
        }

        String objectiveName =
                healthObjective.getName() == null
                        ? ""
                        : healthObjective.getName()
                        .toLowerCase(
                                Locale.ROOT
                        );

        String objectiveDisplay =
                healthObjective.getDisplayName() == null
                        ? ""
                        : healthObjective
                        .getDisplayName()
                        .getString()
                        .toLowerCase(
                                Locale.ROOT
                        );

        String renderType =
                String.valueOf(
                        healthObjective.getRenderType()
                ).toLowerCase(
                        Locale.ROOT
                );

        boolean looksLikeHealth =
                renderType.contains("heart")
                        || objectiveName.contains("health")
                        || objectiveName.contains("hp")
                        || objectiveDisplay.contains("health")
                        || objectiveDisplay.contains("hp")
                        || objectiveDisplay.contains("❤");

        return looksLikeHealth
                ? healthObjective
                : null;
    }

    private static Map<String, Integer> tabListHealthScores(
            Minecraft client,
            Objective healthObjective
    ) {
        Map<String, Integer> scores =
                new LinkedHashMap<>();

        if (client == null
                || client.level == null
                || healthObjective == null) {
            return scores;
        }

        for (PlayerScoreEntry entry :
                client.level
                        .getScoreboard()
                        .listPlayerScores(
                                healthObjective
                        )) {

            if (entry == null
                    || entry.owner() == null
                    || entry.owner().isBlank()) {
                continue;
            }

            scores.put(
                    entry.owner()
                            .toLowerCase(
                                    Locale.ROOT
                            ),
                    Math.max(
                            0,
                            entry.value()
                    )
            );
        }

        return scores;
    }

    private static boolean ensureTabHealthRoster(
            Minecraft client,
            Integer expectedStartingPlayersRaw
    ) {
        if (!tabHealthRoster.isEmpty()) {
            return true;
        }

        if (client == null
                || client.getConnection() == null) {
            return false;
        }

        Objective healthObjective =
                tabListHealthObjective(
                        client
                );

        if (healthObjective == null) {
            return false;
        }

        Map<String, Integer> scores =
                tabListHealthScores(
                        client,
                        healthObjective
                );

        List<String> orderedPlayers =
                new ArrayList<>();

        client.getConnection()
                .getListedOnlinePlayers()
                .stream()
                .sorted(
                        Comparator.comparingInt(
                                info ->
                                        info.getTabListOrder()
                        )
                )
                .forEach(
                        info -> {
                            if (info == null
                                    || info.getProfile() == null
                                    || info.getProfile().name() == null) {
                                return;
                            }

                            String name =
                                    info.getProfile().name();

                            Integer health =
                                    scores.get(
                                            name.toLowerCase(
                                                    Locale.ROOT
                                            )
                                    );

                            /*
                             * At game start every actual participant should
                             * have a positive LIST-health score. This filters
                             * unrelated/fake tab entries without saving names
                             * to the CSV.
                             */
                            if (health != null
                                    && health > 0
                                    && orderedPlayers.stream()
                                    .noneMatch(
                                            existing ->
                                                    existing.equalsIgnoreCase(
                                                            name
                                                    )
                                    )) {
                                orderedPlayers.add(
                                        name
                                );
                            }
                        }
                );

        if (orderedPlayers.size() > TAB_HEALTH_SLOTS) {
            LOGGER.debug(
                    "Rejected tab health roster: {} scored players exceeds {} slots",
                    orderedPlayers.size(),
                    TAB_HEALTH_SLOTS
            );
            return false;
        }

        if (expectedStartingPlayersRaw != null
                && orderedPlayers.size()
                != expectedStartingPlayersRaw) {

            LOGGER.debug(
                    "Rejected tab health roster: expected {} starting players but found {} scored tab players",
                    expectedStartingPlayersRaw,
                    orderedPlayers.size()
            );
            return false;
        }

        if (orderedPlayers.isEmpty()) {
            return false;
        }

        tabHealthRoster.clear();
        tabHealthRoster.addAll(
                orderedPlayers
        );

        LOGGER.info(
                "Captured fixed tab health roster with {} player slots",
                tabHealthRoster.size()
        );

        return true;
    }

    private static TabHealthSnapshot tabListHealthSnapshot(
            Minecraft client,
            boolean recorderAlive,
            Integer expectedRawPlayers,
            Integer expectedModeledPlayers
    ) {
        if (client == null
                || client.level == null
                || client.getConnection() == null) {
            return null;
        }

        if (!ensureTabHealthRoster(
                client,
                gameStartPlayersRaw
        )) {
            return null;
        }

        Objective healthObjective =
                tabListHealthObjective(
                        client
                );

        if (healthObjective == null) {
            LOGGER.debug(
                    "No valid tab-list health objective available for wave sample"
            );
            return null;
        }

        Map<String, Integer> scores =
                tabListHealthScores(
                        client,
                        healthObjective
                );

        List<Integer> slotHealth =
                new ArrayList<>(
                        Collections.nCopies(
                                TAB_HEALTH_SLOTS,
                                (Integer) null
                        )
                );

        String recorderName =
                client.getGameProfile() == null
                        ? null
                        : client.getGameProfile().name();

        int rawPositivePlayers = 0;
        int modeledPositivePlayers = 0;
        int modeledTotalHealth = 0;

        for (int slot = 0;
             slot < tabHealthRoster.size()
                     && slot < TAB_HEALTH_SLOTS;
             slot++) {

            String playerName =
                    tabHealthRoster.get(
                            slot
                    );

            int health =
                    scores.getOrDefault(
                            playerName.toLowerCase(
                                    Locale.ROOT
                            ),
                            0
                    );

            slotHealth.set(
                    slot,
                    health
            );

            if (health <= 0) {
                continue;
            }

            rawPositivePlayers++;

            boolean excludedRecorder =
                    !recorderMovingForGame
                            && recorderAlive
                            && recorderName != null
                            && playerName.equalsIgnoreCase(
                            recorderName
                    );

            boolean excludedWerewolf =
                    werewolfAdjustmentActive
                            && werewolfPlayerName != null
                            && playerName.equalsIgnoreCase(
                            werewolfPlayerName
                    );

            if (excludedRecorder
                    || excludedWerewolf) {
                continue;
            }

            modeledPositivePlayers++;
            modeledTotalHealth +=
                    health;
        }

        /*
         * A missing score packet can look exactly like a dead player. Reject
         * the entire sample unless positive-health counts agree with both the
         * raw scoreboard count and the modeled survivor count.
         */
        if (expectedRawPlayers != null
                && rawPositivePlayers
                != expectedRawPlayers) {

            LOGGER.debug(
                    "Rejected tab health sample: expected raw players={} positive slots={} slotHealth={}",
                    expectedRawPlayers,
                    rawPositivePlayers,
                    slotHealth
            );
            return null;
        }

        if (expectedModeledPlayers != null
                && modeledPositivePlayers
                != expectedModeledPlayers) {

            LOGGER.debug(
                    "Rejected tab health sample: expected modeled players={} modeled positive slots={} modeledTotalHealth={}",
                    expectedModeledPlayers,
                    modeledPositivePlayers,
                    modeledTotalHealth
            );
            return null;
        }

        return new TabHealthSnapshot(
                modeledTotalHealth,
                Collections.unmodifiableList(
                        new ArrayList<>(
                                slotHealth
                        )
                )
        );
    }

    private static boolean isRecorderAlive(Minecraft client) {
        /*
         * Chat is authoritative for this experiment. Hypixel does not always
         * map an eliminated player cleanly onto vanilla spectator state, so
         * isSpectator() produced false "still alive" results.
         */
        return !recorderDeathDetected;
    }

    private static void handleServerGameMessage(
            Component message,
            boolean overlay
    ) {
        /*
         * The user specifically wants TEXT CHAT. Ignore action-bar overlays.
         */
        if (overlay || message == null) {
            return;
        }

        String text = stripFormatting(message.getString()).strip();

        if (text.isBlank()) {
            return;
        }

        /*
         * Player prediction commands are ordinary colon-containing chat, so
         * parse them BEFORE the normal player-chat filter.
         */
        if (maybeHandleDisasterPredictionCommand(
                text
        )) {
            return;
        }

        /*
         * Filtering rule: remove/ignore every message containing a colon.
         * This filters ordinary player chat like "Player: hello" from the
         * death detector. It does NOT remove the message from the user's HUD.
         */
        if (text.contains(":")) {
            return;
        }

        /*
         * Werewolf-selection announcement. Hypixel may emit this before the
         * first in-game scoreboard snapshot, so recognize it BEFORE the
         * matchActive guard.
         */
        Matcher werewolfSelectedMatcher =
                WEREWOLF_SELECTED_PATTERN.matcher(
                        text
                );

        if (werewolfSelectedMatcher.matches()
                && !matchActive) {

            pendingPreGameWerewolfPlayerName =
                    werewolfSelectedMatcher.group(1);
            pendingPreGameWerewolfSelectionMessage =
                    text;

            LOGGER.info(
                    "Queued pre-game Werewolf selection until first in-game scoreboard: {}",
                    pendingPreGameWerewolfPlayerName
            );
            return;
        }

        if (!matchActive || gameFinalized) {
            return;
        }

        if (werewolfSelectedMatcher.matches()) {
            applyWerewolfSelection(
                    werewolfSelectedMatcher.group(1),
                    text,
                    Minecraft.getInstance()
            );
            return;
        }

        /*
         * Werewolf terminal-status messages.
         *
         * Hypixel can remove the transformed Werewolf in either of these ways:
         *
         *   WEREWOLF! The Werewolf has been killed!
         *   WEREWOLF! The Werewolf has left the game!
         *
         * Both mean the Werewolf is no longer present in raw Players Alive, so
         * both must disable the special Werewolf -1 and must block the
         * "sole-surviving Werewolf" win detector.
         *
         * The killed message uses Hypixel's red/green color pattern. The
         * leave-game message observed in-game is all red.
         */
        boolean werewolfKilledStatus =
                WEREWOLF_KILLED_TEXT.equals(text);

        boolean werewolfLeftStatus =
                WEREWOLF_LEFT_TEXT.equals(text);

        if (werewolfKilledStatus
                || werewolfLeftStatus) {

            boolean expectedColors =
                    werewolfKilledStatus
                            ? hasExpectedWerewolfKilledColors(
                                    message
                            )
                            : hasExpectedWerewolfLeftColors(
                                    message
                            );

            if (expectedColors) {
                if (!werewolfKilledMessageDetected) {
                    /*
                     * Historical variable name retained for compatibility.
                     * Semantically this now means "Werewolf is gone": killed
                     * OR left the game.
                     */
                    werewolfKilledMessageDetected = true;

                    /*
                     * Hypixel has now removed the Werewolf from its raw
                     * Players Alive count, so the special extra -1 is no
                     * longer needed. The transformed player remains excluded
                     * conceptually; this simply prevents subtracting them
                     * twice.
                     */
                    werewolfAdjustmentActive = false;

                    /*
                     * If the recorder was the Werewolf, either terminal status
                     * is authoritative evidence that the recorder is no longer
                     * alive/in the match even though the line does not contain
                     * the username.
                     */
                    if (recorderIsWerewolfForGame) {
                        recorderDeathDetected = true;
                        lastRecorderAlive = false;
                        PredictionOverlay.setRecorderAlive(false);
                    }

                    /*
                     * Do NOT immediately recompute lastPlayersAliveModel from
                     * lastPlayersAliveRaw here. The chat message can arrive a
                     * fraction of a second before the scoreboard removes the
                     * Werewolf. Turning off the -1 against that stale raw count
                     * would transiently INCREASE modeled survivors by one and
                     * can be frozen into a wave/game boundary.
                     *
                     * Keep the modeled count continuous; the next scoreboard
                     * sample will apply the now-disabled Werewolf adjustment
                     * to Hypixel's updated raw count.
                     */

                    LOGGER.info(
                            "Werewolf terminal status detected ({}): waiting for scoreboard count to catch up before recomputing modeled survivors",
                            werewolfLeftStatus
                                    ? "left game"
                                    : "killed"
                    );
                } else {
                    LOGGER.info(
                            "Duplicate Werewolf terminal status detected ({}); no ranking change",
                            werewolfLeftStatus
                                    ? "left game"
                                    : "killed"
                    );
                }
            } else {
                LOGGER.info(
                        "Ignored Werewolf terminal status because chat colors did not match expected pattern: {}",
                        text
                );
            }

            return;
        }

        /*
         * IMPORTANT: ignored status messages must also be excluded from the
         * recorder-death detector. Previously a harmless line such as
         *
         *   HOT POTATO! <recorder> is now holding the Hot Potato!
         *
         * contained the recorder username, so the generic username heuristic
         * incorrectly marked the recorder dead even though death attribution
         * correctly ignored the status line.
         */
        if (isIgnoredNonDeathStatusMessage(
                text
        )) {
            LOGGER.info(
                    "Ignored non-death status message before recorder-death detection: {}",
                    text
            );
            return;
        }

        Minecraft client = Minecraft.getInstance();

        String username = null;

        try {
            if (client.getGameProfile() != null) {
                username = client.getGameProfile().name();
            }
        } catch (Exception ignored) {
            // Leave username null; no recorder-death inference is made.
        }

        /*
         * Recorder death must be handled BEFORE disaster/Fall/Chats
         * attribution. The recorder is excluded from the modeled population
         * when Recorder moving=OFF, so the recorder's own death must not be
         * added to the DEATHS overlay either.
         *
         * Non-death status messages have already returned above, avoiding
         * false positives such as:
         *   HOT POTATO! <recorder> is now holding the Hot Potato!
         */
        if (!recorderDeathDetected
                && username != null
                && !username.isBlank()
                && containsMinecraftUsername(
                text,
                username
        )) {
            recorderDeathDetected = true;
            recorderDeathMessage = text;
            recorderDeathTimestamp = Instant.now().toString();
            lastRecorderAlive = false;

            PredictionOverlay.setRecorderAlive(false);

            /*
             * Do NOT recompute lastPlayersAliveModel from the current raw
             * scoreboard on the death-chat frame. With Recorder moving=OFF,
             * the recorder was already excluded before dying, so the modeled
             * survivor count should remain continuous. The raw scoreboard can
             * lag chat by one sample; recomputing immediately with
             * recorderAlive=false could otherwise make modeled survivors jump
             * upward by one. The next 5 Hz scoreboard sample reconciles raw and
             * modeled counts.
             */

            appendJson(
                    RECORDER_DEATH_OUTPUT_FILE,
                    new RecorderDeathDetection(
                            recorderDeathTimestamp,
                            currentGameId,
                            username,
                            text,
                            previousTimeLeft,
                            lastPlayersAliveRaw,
                            lastPlayersAliveModel
                    )
            );

            LOGGER.info(
                    "Recorder death detected from Hypixel chat and excluded from death attribution: {}",
                    text
            );

            return;
        }

        /*
         * IMPORTANT:
         *
         * recorderDeathDetected changes PLAYER-COUNT accounting only.
         * It must NOT disable observation of later players' chat deaths.
         *
         * The recorder's own death message already returns inside the branch
         * above, so every later non-colon message is safe to continue through
         * disaster / Fall / Void / Misc / Chats attribution.
         */
        attributeDisasterDeathsFromChat(
                message,
                text
        );
    }

    private static boolean maybeHandleDisasterPredictionCommand(
            String text
    ) {
        Matcher matcher =
                DISASTER_PREDICTION_CHAT_PATTERN.matcher(
                        text
                );

        if (!matcher.matches()) {
            return false;
        }

        /*
         * It is still a recognized command line even if it arrives outside
         * the valid prediction window. Return true so it never enters death
         * attribution / Chats.
         */
        if (!matchActive
                || gameFinalized
                || waves.isEmpty()) {
            return true;
        }

        WaveState wave =
                waves.get(
                        waves.size() - 1
                );

        long ageMs =
                System.currentTimeMillis()
                        - wave.startedAtMs;

        long allowedWindowMs =
                Math.max(
                        0L,
                        Math.round(
                                ModelDataManager
                                        .getPredictionWindowSeconds()
                                        * 1000.0
                        )
                );

        if (ageMs < 0
                || ageMs > allowedWindowMs) {

            LOGGER.info(
                    "Ignored !disasterpred/!dpred outside prediction window: wave={} ageMs={} allowedMs={} text={}",
                    wave.wave,
                    ageMs,
                    allowedWindowMs,
                    text
            );
            return true;
        }

        double guess;

        try {
            guess =
                    Double.parseDouble(
                            matcher.group(2)
                    );
        } catch (NumberFormatException exception) {
            return true;
        }

        if (!Double.isFinite(guess)
                || guess < 0.0
                || guess > 100.0) {
            return true;
        }

        String username =
                matcher.group(1);

        String key =
                username.toLowerCase(
                        Locale.ROOT
                );

        UserPredictionState state =
                userPredictions.computeIfAbsent(
                        key,
                        ignored ->
                                new UserPredictionState(
                                        username
                                )
                );

        state.displayName =
                username;

        state.guesses.set(
                wave.wave - 1,
                guess
        );

        PredictionOverlay.setCommunityPrediction(
                username,
                wave.wave,
                guess
        );

        LOGGER.info(
                "Accepted !disasterpred/!dpred username={} wave={} guess={} ageMs={} allowedMs={}",
                username,
                wave.wave,
                guess,
                ageMs,
                allowedWindowMs
        );

        return true;
    }

    private static void applyWerewolfSelection(
            String selectedPlayer,
            String selectionMessage,
            Minecraft client
    ) {
        if (selectedPlayer == null
                || selectedPlayer.isBlank()) {
            return;
        }

        werewolfPlayerName =
                selectedPlayer;

        String recorderUsername = null;

        try {
            if (client != null
                    && client.getGameProfile() != null) {
                recorderUsername =
                        client.getGameProfile().name();
            }
        } catch (Exception ignored) {
            // Leave recorder username unknown.
        }

        recorderIsWerewolfForGame =
                recorderUsername != null
                        && werewolfPlayerName
                        .equalsIgnoreCase(
                                recorderUsername
                        );

        werewolfAdjustmentActive =
                !(
                        !recorderMovingForGame
                                && recorderIsWerewolfForGame
                );

        boolean countTransformationDeath =
                recorderMovingForGame
                        || !recorderIsWerewolfForGame;

        if (countTransformationDeath
                && !werewolfTransformationDeathCounted) {

            werewolfTransformationDeathPending = true;
            werewolfSelectionMessage =
                    selectionMessage;

            /*
             * This only succeeds after the Werewolf is registered AND the
             * correct WaveState exists. Otherwise it intentionally remains
             * pending.
             */
            maybeCountWerewolfTransformationDeath();
        }

        lastPlayersAliveModel =
                analysisPlayerCount(
                        lastPlayersAliveRaw,
                        isRecorderAlive(
                                client == null
                                        ? Minecraft.getInstance()
                                        : client
                        ),
                        recorderMovingForGame
                );

        LOGGER.info(
                "Werewolf player stored internally; recorderIsWerewolf={} rawStillIncludesWerewolfAdjustment={}",
                recorderIsWerewolfForGame,
                werewolfAdjustmentActive
        );
    }

    private static TrackedDisaster makeTrackedDisaster(
            List<SidebarLine> lines,
            String disaster
    ) {
        Character colorCode = null;
        Integer firstRgb = null;
        List<Integer> colorPattern =
                List.of();

        for (SidebarLine line : lines) {
            if (!line.text().equalsIgnoreCase(disaster)) {
                continue;
            }

            /*
             * Keep the old first-color metadata for logging/backward
             * compatibility, but matching/rendering now uses the FULL visible
             * color pattern captured from the scoreboard component.
             *
             * This matters for multicolor disasters such as:
             *   Red Light, Green Light
             * where "Red Light," is red and "Green Light" is green.
             */
            colorCode =
                    firstLegacyColorCode(
                            line.rawText()
                    );

            colorPattern =
                    extractDisasterColorPattern(
                            line,
                            disaster
                    );

            for (Integer rgb : colorPattern) {
                if (rgb != null && rgb >= 0) {
                    firstRgb = rgb;
                    break;
                }
            }

            break;
        }

        if (firstRgb == null
                && colorCode != null) {
            firstRgb =
                    legacyColorRgb(
                            colorCode
                    );
        }

        return new TrackedDisaster(
                disaster,
                colorCode,
                firstRgb,
                colorPattern
        );
    }

    private static List<Integer> extractDisasterColorPattern(
            SidebarLine line,
            String disaster
    ) {
        if (line == null
                || disaster == null
                || disaster.isBlank()
                || line.visualText() == null
                || line.visualColors() == null) {
            return List.of();
        }

        String visual =
                line.visualText();

        String lowerVisual =
                visual.toLowerCase(
                        Locale.ROOT
                );

        String lowerDisaster =
                disaster.toLowerCase(
                        Locale.ROOT
                );

        int start =
                lowerVisual.indexOf(
                        lowerDisaster
                );

        if (start < 0) {
            return List.of();
        }

        List<Integer> pattern =
                new ArrayList<>();

        for (int offset = 0;
             offset < disaster.length();
             offset++) {

            int index = start + offset;

            pattern.add(
                    index < line.visualColors().size()
                            ? line.visualColors().get(index)
                            : -1
            );
        }

        return List.copyOf(pattern);
    }

    private static Character firstLegacyColorCode(
            String rawText
    ) {
        if (rawText == null) {
            return null;
        }

        for (int i = 0;
             i + 1 < rawText.length();
             i++) {

            if (rawText.charAt(i) != '§') {
                continue;
            }

            char code =
                    Character.toLowerCase(
                            rawText.charAt(i + 1)
                    );

            if ((code >= '0' && code <= '9')
                    || (code >= 'a' && code <= 'f')) {
                return code;
            }
        }

        return null;
    }

    private static Integer legacyColorRgb(
            char code
    ) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> 0x000000;
            case '1' -> 0x0000AA;
            case '2' -> 0x00AA00;
            case '3' -> 0x00AAAA;
            case '4' -> 0xAA0000;
            case '5' -> 0xAA00AA;
            case '6' -> 0xFFAA00;
            case '7' -> 0xAAAAAA;
            case '8' -> 0x555555;
            case '9' -> 0x5555FF;
            case 'a' -> 0x55FF55;
            case 'b' -> 0x55FFFF;
            case 'c' -> 0xFF5555;
            case 'd' -> 0xFF55FF;
            case 'e' -> 0xFFFF55;
            case 'f' -> 0xFFFFFF;
            default -> null;
        };
    }


    private static boolean isIgnoredNonDeathStatusMessage(
            String plainText
    ) {
        if (plainText == null) {
            return false;
        }

        String trimmed =
                plainText.strip();

        /*
         * These visible non-colon status messages are NOT deaths and should
         * not contribute either to disaster death counts or to Chats.
         */
        if (trimmed.startsWith("SWAPPAGE!")
                && trimmed.contains(
                "Your location was swapped with"
        )) {
            return true;
        }

        if (trimmed.startsWith("HOT POTATO!")
                && trimmed.contains(
                " is now holding the Hot Potato!"
        )) {
            return true;
        }

        /*
         * Hot Potato transfer/status messages shown only to the local player.
         * These are not deaths and must not increment Chats or trigger the
         * recorder-death username heuristic.
         *
         * Examples:
         *   HOT POTATO! You were given the Hot Potato by Dandonioo!
         *   HOT POTATO! You gave the Hot Potato to ElBrysn!
         */
        if (trimmed.matches(
                "^HOT POTATO! You were given the Hot Potato by [A-Za-z0-9_]{1,16}!$"
        )) {
            return true;
        }

        if (trimmed.matches(
                "^HOT POTATO! You gave the Hot Potato to [A-Za-z0-9_]{1,16}!$"
        )) {
            return true;
        }

        if (trimmed.startsWith("HOT POTATO!")
                && trimmed.contains(
                "The Hot Potato has exploded!"
        )) {
            return true;
        }

        /*
         * Nuke countdown status text is not a death:
         *   Nuke in 5...
         *   Nuke in 4...
         *   ...
         */
        if (trimmed.matches(
                "^Nuke in \\d+\\.\\.\\.$"
        )) {
            return true;
        }

        /*
         * Grounded restoration status text is not a death.
         */
        if (trimmed.matches(
                "^GROUNDED! Jumping has been restored!$"
        )) {
            return true;
        }

        /*
         * Purge completion/status text is not itself a death.
         * Example:
         *   PURGE! The Purge has concluded. 5 players remain.
         */
        if (trimmed.matches(
                "^PURGE! The Purge has concluded\\. \\d+ players remain\\.$"
        )) {
            return true;
        }

        /*
         * Hypixel Says penalty/status text is not a death.
         */
        if (trimmed.matches(
                "^HYPIXEL SAYS! You took damage for completing a task that didn't start with Hypixel Says!$"
        )) {
            return true;
        }

        /*
         * Hypixel Says timeout/failed-task penalty is damage, not a death.
         * Example:
         *   HYPIXEL SAYS! You took damage for not completing your Hypixel Says task in time!
         */
        if (trimmed.matches(
                "^HYPIXEL SAYS! You took damage for not completing your Hypixel Says task in time!$"
        )) {
            return true;
        }

        /*
         * Successful Hypixel Says completion is also status text, not a death.
         * Example:
         *   HYPIXEL SAYS! You completed your Hypixel Says task in 1.8s!
         */
        if (trimmed.matches(
                "^HYPIXEL SAYS! You completed your Hypixel Says task in \\d+(?:\\.\\d+)?s!$"
        )) {
            return true;
        }

        /*
         * Disco movement penalty/status text is damage, not a death.
         * Example:
         *   DISCO! You took damage because you stopped moving!
         */
        if (trimmed.matches(
                "^DISCO! You took damage because you stopped moving!$"
        )) {
            return true;
        }

        return false;
    }



    private static void maybeCountWerewolfTransformationDeath() {
        if (!werewolfTransformationDeathPending
                || werewolfTransformationDeathCounted) {
            return;
        }

        TrackedDisaster werewolf =
                trackedDisasters.get(
                        "werewolf"
                );

        if (werewolf == null
                || currentOpenWaveForAttribution() == null) {
            /*
             * The chat announcement can arrive before scoreboard registration
             * OR before the new WaveState is created. Keep the event pending
             * so the +1 lands in the correct per-wave death bucket.
             */
            return;
        }

        incrementTrackedDisasterDeath(
                werewolf,
                werewolfSelectionMessage == null
                        ? "Werewolf transformation"
                        : werewolfSelectionMessage
        );

        werewolfTransformationDeathCounted = true;
        werewolfTransformationDeathPending = false;

        LOGGER.info(
                "Counted Werewolf transformation as one Werewolf death"
        );
    }

    private static WaveState currentOpenWaveForAttribution() {
        if (waves.isEmpty()) {
            return null;
        }

        WaveState wave =
                waves.get(
                        waves.size() - 1
                );

        return wave.playersAfterModel == null
                ? wave
                : null;
    }

    private static void incrementTrackedDisasterDeath(
            TrackedDisaster tracked,
            String plainText
    ) {
        if (tracked == null) {
            return;
        }

        tracked.chatDeaths++;

        WaveState currentWave =
                currentOpenWaveForAttribution();

        if (currentWave != null) {
            currentWave.disasterDeaths.merge(
                    tracked.name.toLowerCase(
                            Locale.ROOT
                    ),
                    1,
                    Integer::sum
            );
        }

        PredictionOverlay.setDisasterDeathCount(
                tracked.name,
                tracked.colorRgb,
                tracked.colorPattern,
                tracked.chatDeaths
        );

        updateDerivedMiscOverlay(
                lastPlayersAliveModel
        );

        appendJson(
                DISASTER_DEATH_OUTPUT_FILE,
                new DisasterDeathAttribution(
                        Instant.now().toString(),
                        currentGameId,
                        tracked.name,
                        tracked.colorCode == null
                                ? null
                                : "§" + tracked.colorCode,
                        tracked.colorRgb,
                        plainText,
                        previousTimeLeft,
                        tracked.chatDeaths
                )
        );

        LOGGER.info(
                "Attributed chat death to {}: count={} message={}",
                tracked.name,
                tracked.chatDeaths,
                plainText
        );
    }



    private static void incrementVoidDeath(
            String plainText
    ) {
        voidChatDeaths++;

        WaveState currentWave =
                currentOpenWaveForAttribution();

        if (currentWave != null) {
            currentWave.voidDeaths++;
        }

        PredictionOverlay.setDisasterDeathCount(
                "Void",
                null,
                voidChatDeaths
        );

        updateDerivedMiscOverlay(
                lastPlayersAliveModel
        );

        appendJson(
                DISASTER_DEATH_OUTPUT_FILE,
                new DisasterDeathAttribution(
                        Instant.now().toString(),
                        currentGameId,
                        "Void",
                        null,
                        null,
                        plainText,
                        previousTimeLeft,
                        voidChatDeaths
                )
        );

        LOGGER.info(
                "Attributed chat death to Void: count={} message={}",
                voidChatDeaths,
                plainText
        );
    }

    private static void incrementMiscDeath(
            String plainText
    ) {
        miscChatDeaths++;

        WaveState currentWave =
                currentOpenWaveForAttribution();

        if (currentWave != null) {
            currentWave.explicitMiscChatDeaths++;
        }

        updateDerivedMiscOverlay(
                lastPlayersAliveModel
        );

        appendJson(
                DISASTER_DEATH_OUTPUT_FILE,
                new DisasterDeathAttribution(
                        Instant.now().toString(),
                        currentGameId,
                        "Misc",
                        null,
                        null,
                        plainText,
                        previousTimeLeft,
                        miscChatDeaths
                )
        );

        LOGGER.info(
                "Attributed chat death to Misc: count={} message={}",
                miscChatDeaths,
                plainText
        );
    }

    private static void incrementFallDeath(
            String plainText
    ) {
        fallChatDeaths++;

        WaveState currentWave =
                currentOpenWaveForAttribution();

        if (currentWave != null) {
            currentWave.fallDeaths++;
        }

        /*
         * Fall is not a Hypixel disaster, but showing it live when nonzero is
         * useful. PredictionOverlay filters zero-death disaster rows, so Fall
         * appears only after the first matching fall death.
         */
        PredictionOverlay.setDisasterDeathCount(
                "Fall",
                null,
                fallChatDeaths
        );

        updateDerivedMiscOverlay(
                lastPlayersAliveModel
        );

        appendJson(
                DISASTER_DEATH_OUTPUT_FILE,
                new DisasterDeathAttribution(
                        Instant.now().toString(),
                        currentGameId,
                        "Fall",
                        null,
                        null,
                        plainText,
                        previousTimeLeft,
                        fallChatDeaths
                )
        );

        LOGGER.info(
                "Attributed chat death to Fall: count={} message={}",
                fallChatDeaths,
                plainText
        );
    }

    private static void attributeDisasterDeathsFromChat(
            Component message,
            String plainText
    ) {
        if (message == null
                || plainText == null) {
            return;
        }

        if (isIgnoredNonDeathStatusMessage(
                plainText
        )) {
            LOGGER.info(
                    "Ignored non-death status message: {}",
                    plainText
            );
            return;
        }

        String trimmed =
                plainText.strip();

        /*
         * TNT Rain explosion death alias.
         *
         * Only attribute the generic vanilla death text "<player> blew up"
         * to TNT Rain while TNT Rain is CURRENTLY active. This intentionally
         * does not use trackedDisasters, because unlike residual Floor Is Lava
         * deaths, this alias should stop applying once TNT Rain becomes
         * inactive.
         */
        if (trimmed.matches(
                "^.+ blew up\\.?$"
        )
                && activeDisastersForChat.contains(
                "tnt rain"
        )) {

            TrackedDisaster tntRain =
                    trackedDisasters.get(
                            "tnt rain"
                    );

            if (tntRain != null) {
                incrementTrackedDisasterDeath(
                        tntRain,
                        plainText
                );
                return;
            }
        }

        /*
         * Vanilla explosion wording:
         *   <player> was blown up.
         *
         * If TNT Rain is active, treat the explosion as TNT Rain.
         * Otherwise classify it as Misc rather than Chats.
         */
        if (trimmed.matches(
                "^.+ was blown up\\.?$"
        )) {
            if (activeDisastersForChat.contains(
                    "tnt rain"
            )) {

                TrackedDisaster tntRain =
                        trackedDisasters.get(
                                "tnt rain"
                        );

                if (tntRain != null) {
                    incrementTrackedDisasterDeath(
                            tntRain,
                            plainText
                    );
                    return;
                }
            }

            incrementMiscDeath(
                    plainText
            );
            return;
        }

        /*
         * Void/environmental out-of-bounds deaths.
         */
        if (trimmed.matches(
                "^.+ died while exploring the void\\.?$"
        )
                || trimmed.matches(
                "^.+ fell infinitely into the void\\.?$"
        )
                || trimmed.matches(
                "^.+ tried to escape the disasters the wrong way\\.?$"
        )) {

            incrementVoidDeath(
                    plainText
            );
            return;
        }

        /*
         * Miscellaneous non-disaster death messages that we want to track
         * explicitly instead of leaving in Chats.
         *
         * Accept either a straight or curly apostrophe in couldn't.
         */
        if (trimmed.matches(
                "^.+ couldn['’]t hold their breath any longer\\.?$"
        )) {

            incrementMiscDeath(
                    plainText
            );
            return;
        }

        /*
         * Plain environmental fall deaths get their own CSV category instead
         * of being mixed into Chats.
         */
        if (trimmed.matches(
                "^.+ died from a large fall\\.?$"
        )
                || trimmed.matches(
                "^.+ tripped and fell from a high place\\.?$"
        )
                || trimmed.matches(
                "^.+ hit the ground too hard\\.?$"
        )) {

            incrementFallDeath(
                    plainText
            );
            return;
        }

        /*
         * Meteor Shower's official death text can omit the literal disaster
         * name:
         *   <player> exploded from a meteor impact.
         *
         * If Meteor Shower has appeared in this match, attribute this directly
         * to Meteor Shower instead of letting it fall through to Chats.
         */
        if (trimmed.matches(
                "^.+ exploded from a meteor impact\\.?$"
        )) {
            TrackedDisaster meteor =
                    trackedDisasters.get(
                            "meteor shower"
                    );

            if (meteor != null) {
                incrementTrackedDisasterDeath(
                        meteor,
                        plainText
                );
                return;
            }
        }

        /*
         * Vanilla-style lava/fire death messages do not name the Hypixel
         * disaster directly, so attribute them using the disasters that have
         * appeared in the current game.
         *
         * "went up in flames" is ambiguous between Meteor Shower and
         * The Floor is Lava. If BOTH are present, prefer Meteor Shower.
         * This preference is intentionally arbitrary, but explicit, so the
         * dataset stays deterministic and we can revise the convention later
         * without pretending it was semantically certain.
         */
        if (trimmed.matches(
                "^.+ went up in flames\\.?$"
        )) {
            TrackedDisaster meteor =
                    trackedDisasters.get(
                            "meteor shower"
                    );

            TrackedDisaster floorIsLava =
                    trackedDisasters.get(
                            "the floor is lava"
                    );

            TrackedDisaster target =
                    meteor != null
                            ? meteor
                            : floorIsLava;

            if (target != null) {
                incrementTrackedDisasterDeath(
                        target,
                        plainText
                );
                return;
            }
        }

        /*
         * Same ambiguity as "went up in flames": both Meteor Shower and
         * The Floor is Lava can plausibly generate vanilla fire deaths.
         * Keep the same arbitrary-but-deterministic preference:
         * Meteor Shower > The Floor is Lava when both are present.
         */
        if (trimmed.matches(
                "^.+ went down in flames\\.?$"
        )) {
            TrackedDisaster meteor =
                    trackedDisasters.get(
                            "meteor shower"
                    );

            TrackedDisaster floorIsLava =
                    trackedDisasters.get(
                            "the floor is lava"
                    );

            TrackedDisaster target =
                    meteor != null
                            ? meteor
                            : floorIsLava;

            if (target != null) {
                incrementTrackedDisasterDeath(
                        target,
                        plainText
                );
                return;
            }
        }

        /*
         * Same ambiguity as "went up in flames": both Meteor Shower and
         * The Floor is Lava can plausibly generate vanilla fire deaths.
         * Keep the same arbitrary-but-deterministic preference:
         * Meteor Shower > The Floor is Lava when both are present.
         */
        if (trimmed.matches(
                "^.+ burned to death\\.?$"
        )) {
            TrackedDisaster meteor =
                    trackedDisasters.get(
                            "meteor shower"
                    );

            TrackedDisaster floorIsLava =
                    trackedDisasters.get(
                            "the floor is lava"
                    );

            TrackedDisaster target =
                    meteor != null
                            ? meteor
                            : floorIsLava;

            if (target != null) {
                incrementTrackedDisasterDeath(
                        target,
                        plainText
                );
                return;
            }
        }

        /*
         * Same fire ambiguity as "went up in flames" and "burned to death".
         * If both Meteor Shower and The Floor is Lava have appeared, prefer
         * Meteor Shower. This is intentionally arbitrary but deterministic.
         */
        if (trimmed.matches(
                "^.+ stood too close to fire\\.?$"
        )) {
            TrackedDisaster meteor =
                    trackedDisasters.get(
                            "meteor shower"
                    );

            TrackedDisaster floorIsLava =
                    trackedDisasters.get(
                            "the floor is lava"
                    );

            TrackedDisaster target =
                    meteor != null
                            ? meteor
                            : floorIsLava;

            if (target != null) {
                incrementTrackedDisasterDeath(
                        target,
                        plainText
                );
                return;
            }
        }

        /*
         * "fell into a pool of lava" is attributed to The Floor is Lava
         * whenever that disaster has APPEARED at any point in this match.
         *
         * Important: trackedDisasters contains every disaster seen so far,
         * not only currently-active disasters. Hypixel disabling/striking
         * through The Floor is Lava stops NEW lava placement, but existing
         * lava remains in the map. A later death in that residual lava is
         * therefore still credited to The Floor is Lava.
         */
        if (trimmed.matches(
                "^.+ fell into a pool of lava\\.?$"
        )) {
            TrackedDisaster floorIsLava =
                    trackedDisasters.get(
                            "the floor is lava"
                    );

            if (floorIsLava != null) {
                incrementTrackedDisasterDeath(
                        floorIsLava,
                        plainText
                );
                return;
            }
        }

        /*
         * This message explicitly identifies lava, so when The Floor is Lava
         * has appeared in the match attribute it there. trackedDisasters is
         * intentionally not limited to currently-active disasters.
         */
        /*
         * Another vanilla lava death wording observed in live chat.
         * Residual Floor Is Lava can keep killing after the scoreboard entry
         * expires, so use trackedDisasters (appeared at any point), not only
         * the currently-active set.
         */
        if (trimmed.matches(
                "^.+ took a swim in lava\\.?$"
        )) {
            TrackedDisaster floorIsLava =
                    trackedDisasters.get(
                            "the floor is lava"
                    );

            if (floorIsLava != null) {
                incrementTrackedDisasterDeath(
                        floorIsLava,
                        plainText
                );
                return;
            }
        }

        if (trimmed.matches(
                "^.+ discovered that lava (?:was|is) very hot\\.?$"
        )) {
            TrackedDisaster floorIsLava =
                    trackedDisasters.get(
                            "the floor is lava"
                    );

            if (floorIsLava != null) {
                incrementTrackedDisasterDeath(
                        floorIsLava,
                        plainText
                );
                return;
            }
        }

        /*
         * Red Light, Green Light uses vanilla-ish death text that does not
         * contain the full disaster name/color pattern. Attribute these
         * explicit phrases directly when RLGL has appeared in the game.
         *
         * Examples:
         *   <player> thought that Red Light meant go.
         *   <player> moved during a Red Light.
         */
        if (trimmed.matches(
                "^.+ thought that Red Light meant go\\.?$"
        )
                || trimmed.matches(
                "^.+ moved during a Red Light\\.?$"
        )) {

            TrackedDisaster redLightGreenLight =
                    trackedDisasters.get(
                            "red light, green light"
                    );

            if (redLightGreenLight != null) {
                incrementTrackedDisasterDeath(
                        redLightGreenLight,
                        plainText
                );
                return;
            }
        }

        /*
         * Direct kill by the player selected as Werewolf is attributed to the
         * Werewolf disaster.
         *
         * This check intentionally runs BEFORE the generic Purge PvP rule.
         * If Purge and Werewolf are both active, a kill by the known Werewolf
         * player belongs to Werewolf, not Purge.
         */
        Matcher killedByMatcher =
                PLAYER_KILLED_BY_PATTERN.matcher(
                        trimmed
                );

        if (killedByMatcher.matches()
                && werewolfPlayerName != null
                && killedByMatcher.group(2)
                .equalsIgnoreCase(
                        werewolfPlayerName
                )) {

            TrackedDisaster werewolf =
                    trackedDisasters.get(
                            "werewolf"
                    );

            if (werewolf != null) {
                incrementTrackedDisasterDeath(
                        werewolf,
                        plainText
                );
                return;
            }
        }

        /*
         * Direct player-vs-player kill text is attributed to Purge, but only
         * when Purge has appeared in the current game.
         */
        if (trimmed.matches(
                "^.+ was killed by .+\\.?$"
        )) {
            TrackedDisaster purge =
                    trackedDisasters.get(
                            "purge"
                    );

            if (purge != null) {
                incrementTrackedDisasterDeath(
                        purge,
                        plainText
                );
                return;
            }
        }

        /*
         * Werewolf ranking semantics:
         *
         * becoming the Werewolf already counts as the player's one Werewolf
         * death, because they leave the normal survivor pool at transformation.
         *
         * Hypixel may later emit one of these Werewolf-specific death texts:
         *   <player> has succumbed to the curse of the Werewolf.
         *   <player> was outcast as a Werewolf for eternity.
         *
         * Do NOT count those again. They describe the later destruction of the
         * already-counted environmental hazard, not a second survivor death.
         * Return before the generic name+color matcher so they increment
         * neither Werewolf nor Chats.
         */
        if (trimmed.matches(
                "^.+ has succumbed to the curse of the Werewolf\\.?$"
        )
                || trimmed.matches(
                "^.+ was outcast as a Werewolf for eternity\\.?$"
        )) {

            LOGGER.info(
                    "Ignored post-transformation Werewolf death text to avoid double-counting: {}",
                    plainText
            );
            return;
        }

        boolean matchedAny = false;

        for (TrackedDisaster tracked :
                trackedDisasters.values()) {

            if (tracked.colorRgb == null) {
                continue;
            }

            boolean matched =
                    containsStyledDisaster(
                            message,
                            tracked.name,
                            tracked.colorPattern,
                            tracked.colorRgb
                    );

            /*
             * Hypixel's Floor Is Lava death text can abbreviate the disaster
             * to just "lava". If The Floor is Lava is active/tracked, accept
             * the styled word "lava" in the same recorded disaster color as
             * an attribution to The Floor is Lava.
             */
            if (!matched
                    && tracked.name.equalsIgnoreCase(
                            "The Floor is Lava"
                    )) {

                matched =
                        containsStyledDisaster(
                                message,
                                "lava",
                                colorPatternForSubstring(
                                        tracked.name,
                                        tracked.colorPattern,
                                        "lava"
                                ),
                                tracked.colorRgb
                        );
            }

            if (!matched) {
                continue;
            }

            matchedAny = true;

            incrementTrackedDisasterDeath(
                    tracked,
                    plainText
            );
        }

        /*
         * Catch-all requested by the user: any active-game non-colon message
         * that did not name a tracked disaster is attributed to Chats.
         */
        if (!matchedAny) {
            unmatchedChatMessages++;

            PredictionOverlay.setDisasterDeathCount(
                    "Chats",
                    null,
                    unmatchedChatMessages
            );

            appendJson(
                    DISASTER_DEATH_OUTPUT_FILE,
                    new DisasterDeathAttribution(
                            Instant.now().toString(),
                            currentGameId,
                            "Chats",
                            null,
                            null,
                            plainText,
                            previousTimeLeft,
                            unmatchedChatMessages
                    )
            );

            LOGGER.info(
                    "Attributed non-colon chat line to Chats: count={} message={}",
                    unmatchedChatMessages,
                    plainText
            );
        }
    }

    private static List<Integer> colorPatternForSubstring(
            String fullName,
            List<Integer> fullPattern,
            String substring
    ) {
        if (fullName == null
                || fullPattern == null
                || substring == null) {
            return List.of();
        }

        int start =
                fullName.toLowerCase(Locale.ROOT)
                        .indexOf(
                                substring.toLowerCase(
                                        Locale.ROOT
                                )
                        );

        if (start < 0) {
            return List.of();
        }

        List<Integer> result =
                new ArrayList<>();

        for (int offset = 0;
             offset < substring.length();
             offset++) {

            int index = start + offset;

            result.add(
                    index < fullPattern.size()
                            ? fullPattern.get(index)
                            : -1
            );
        }

        return List.copyOf(result);
    }

    private static boolean containsStyledDisaster(
            Component message,
            String disasterName,
            List<Integer> expectedColorPattern,
            Integer fallbackRgb
    ) {
        StringBuilder rendered =
                new StringBuilder();

        List<Integer> colors =
                new ArrayList<>();

        try {
            message.getVisualOrderText().accept(
                    (index, style, codePoint) -> {
                        rendered.appendCodePoint(
                                codePoint
                        );

                        int rgb =
                                style.getColor() == null
                                        ? -1
                                        : style.getColor().getValue();

                        /*
                         * Disaster/chat strings here are ordinary BMP text.
                         * Add one color entry per UTF-16 code unit so indices
                         * remain aligned with String#indexOf offsets.
                         */
                        int charCount =
                                Character.charCount(
                                        codePoint
                                );

                        for (int i = 0;
                             i < charCount;
                             i++) {
                            colors.add(rgb);
                        }

                        return true;
                    }
            );
        } catch (Exception exception) {
            LOGGER.debug(
                    "Could not inspect disaster chat colors",
                    exception
            );
            return false;
        }

        String lowerRendered =
                rendered.toString()
                        .toLowerCase(
                                Locale.ROOT
                        );

        String lowerName =
                disasterName.toLowerCase(
                        Locale.ROOT
                );

        int from = 0;

        while (true) {
            int start =
                    lowerRendered.indexOf(
                            lowerName,
                            from
                    );

            if (start < 0) {
                return false;
            }

            boolean colorMatch = true;

            for (int offset = 0;
                 offset < disasterName.length();
                 offset++) {

                char expectedChar =
                        disasterName.charAt(
                                offset
                        );

                /*
                 * Hypixel can leave spaces uncolored/default-colored between
                 * segments. Ignore whitespace color while requiring the actual
                 * visible letters/punctuation to match their scoreboard style.
                 */
                if (Character.isWhitespace(
                        expectedChar
                )) {
                    continue;
                }

                int colorIndex =
                        start + offset;

                if (colorIndex >= colors.size()) {
                    colorMatch = false;
                    break;
                }

                Integer expectedRgb = null;

                if (expectedColorPattern != null
                        && offset
                        < expectedColorPattern.size()) {

                    int patternRgb =
                            expectedColorPattern.get(
                                    offset
                            );

                    if (patternRgb >= 0) {
                        expectedRgb =
                                patternRgb;
                    }
                }

                if (expectedRgb == null) {
                    expectedRgb =
                            fallbackRgb;
                }

                /*
                 * If we have no usable color metadata at all for this
                 * character, do not manufacture a mismatch.
                 */
                if (expectedRgb != null
                        && colors.get(colorIndex)
                        .intValue()
                        != expectedRgb.intValue()) {

                    colorMatch = false;
                    break;
                }
            }

            if (colorMatch) {
                return true;
            }

            from = start + 1;
        }
    }

    private static boolean hasExpectedWerewolfKilledColors(
            Component message
    ) {
        if (message == null) {
            return false;
        }

        StringBuilder rendered =
                new StringBuilder();

        List<Integer> colors =
                new ArrayList<>();

        try {
            message.getVisualOrderText().accept(
                    (index, style, codePoint) -> {
                        rendered.appendCodePoint(
                                codePoint
                        );

                        colors.add(
                                style.getColor() == null
                                        ? -1
                                        : style.getColor()
                                        .getValue()
                        );

                        return true;
                    }
            );
        } catch (Exception exception) {
            LOGGER.debug(
                    "Could not inspect Werewolf chat colors",
                    exception
            );

            return false;
        }

        if (!WEREWOLF_KILLED_TEXT.contentEquals(
                rendered
        )) {
            return false;
        }

        if (colors.size()
                != WEREWOLF_KILLED_TEXT.length()) {
            return false;
        }

        /*
         * Ignore spaces for color matching because Minecraft components can
         * place separator spaces on either adjacent style. All visible
         * non-space characters must match the screenshot's colors.
         */
        for (int i = 0;
             i < WEREWOLF_KILLED_TEXT.length();
             i++) {

            char c =
                    WEREWOLF_KILLED_TEXT.charAt(i);

            if (c == ' ') {
                continue;
            }

            int expected;

            if (i <= 8) {
                expected = CHAT_RED_RGB;
            } else if (i >= 10 && i <= 12) {
                expected = CHAT_GREEN_RGB;
            } else if (i >= 14 && i <= 21) {
                expected = CHAT_RED_RGB;
            } else {
                expected = CHAT_GREEN_RGB;
            }

            if (colors.get(i) != expected) {
                return false;
            }
        }

        return true;
    }

    private static boolean hasExpectedWerewolfLeftColors(
            Component message
    ) {
        if (message == null) {
            return false;
        }

        StringBuilder rendered =
                new StringBuilder();

        List<Integer> colors =
                new ArrayList<>();

        try {
            message.getVisualOrderText().accept(
                    (index, style, codePoint) -> {
                        rendered.appendCodePoint(
                                codePoint
                        );

                        int rgb =
                                style.getColor() == null
                                        ? -1
                                        : style.getColor()
                                        .getValue();

                        int charCount =
                                Character.charCount(
                                        codePoint
                                );

                        for (int i = 0;
                             i < charCount;
                             i++) {
                            colors.add(rgb);
                        }

                        return true;
                    }
            );
        } catch (Exception exception) {
            LOGGER.debug(
                    "Could not inspect Werewolf-left chat colors",
                    exception
            );

            return false;
        }

        if (!WEREWOLF_LEFT_TEXT.contentEquals(
                rendered
        )) {
            return false;
        }

        if (colors.size()
                != WEREWOLF_LEFT_TEXT.length()) {
            return false;
        }

        /*
         * Observed Hypixel message:
         *   WEREWOLF! The Werewolf has left the game!
         * is red across all visible non-space characters.
         */
        for (int i = 0;
             i < WEREWOLF_LEFT_TEXT.length();
             i++) {

            char c =
                    WEREWOLF_LEFT_TEXT.charAt(i);

            if (Character.isWhitespace(c)) {
                continue;
            }

            if (colors.get(i) != CHAT_RED_RGB) {
                return false;
            }
        }

        return true;
    }

    private static boolean containsMinecraftUsername(
            String text,
            String username
    ) {
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerUsername = username.toLowerCase(Locale.ROOT);

        int from = 0;

        while (true) {
            int index = lowerText.indexOf(lowerUsername, from);

            if (index < 0) {
                return false;
            }

            int before = index - 1;
            int after = index + lowerUsername.length();

            boolean leftBoundary =
                    before < 0
                            || !isMinecraftUsernameCharacter(
                                    lowerText.charAt(before)
                            );

            boolean rightBoundary =
                    after >= lowerText.length()
                            || !isMinecraftUsernameCharacter(
                                    lowerText.charAt(after)
                            );

            if (leftBoundary && rightBoundary) {
                return true;
            }

            from = index + 1;
        }
    }

    private static boolean isMinecraftUsernameCharacter(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '_';
    }

    private static Integer analysisPlayerCount(
            Integer rawCount,
            boolean recorderAlive,
            boolean recorderMoving
    ) {
        if (rawCount == null) {
            return null;
        }

        int modelCount = rawCount;

        /*
         * Recorder toggle:
         *   OFF -> exclude recorder while alive.
         *   ON  -> leave recorder in the count for now.
         */
        if (!recorderMoving
                && recorderAlive) {
            modelCount--;
        }

        /*
         * Werewolf scoreboard adjustment:
         *   active Werewolf -> Hypixel raw count still includes them, ours does not
         *   killed Werewolf -> Hypixel raw count has removed them, so no extra -1
         *
         * If the recorder is the Werewolf while Recorder moving=OFF, the
         * selection handler also disables this adjustment because the recorder
         * is already excluded separately.
         */
        if (werewolfAdjustmentActive) {
            modelCount--;
        }

        return Math.max(
                0,
                modelCount
        );
    }

    private static void updateDerivedMiscOverlay(
            Integer currentPlayersModel
    ) {
        int derivedMiscTotal = 0;

        for (WaveState wave :
                waves) {

            if (wave.causeAccountingFinalized) {
                derivedMiscTotal +=
                        wave.miscDeaths;
                continue;
            }

            if (wave.playersAtRollModel == null
                    || currentPlayersModel == null) {
                continue;
            }

            int totalWaveDeathsSoFar =
                    Math.max(
                            0,
                            wave.playersAtRollModel
                                    - currentPlayersModel
                    );

            int disasterDeathsSoFar =
                    wave.disasterDeaths.values()
                            .stream()
                            .mapToInt(
                                    Integer::intValue
                            )
                            .sum();

            int liveMisc =
                    Math.max(
                            0,
                            totalWaveDeathsSoFar
                                    - disasterDeathsSoFar
                                    - wave.fallDeaths
                                    - wave.voidDeaths
                    );

            derivedMiscTotal +=
                    liveMisc;
        }

        PredictionOverlay.setDisasterDeathCount(
                "Misc",
                null,
                derivedMiscTotal
        );
    }

    private static void finalizeWaveCauseAccounting(
            WaveState wave
    ) {
        if (wave == null
                || wave.causeAccountingFinalized
                || wave.playersAtRollModel == null
                || wave.playersAfterModel == null) {
            return;
        }

        int totalWaveDeaths =
                Math.max(
                        0,
                        wave.playersAtRollModel
                                - wave.playersAfterModel
                );

        int disasterDeaths =
                wave.disasterDeaths.values()
                        .stream()
                        .mapToInt(Integer::intValue)
                        .sum();

        /*
         * Misc is the scoreboard-loss remainder after directly observed
         * disaster, Fall, and Void deaths. This intentionally captures quits
         * and other unattributed player losses, because Hypixel does not emit
         * a reliable quit-death chat line.
         *
         * Explicit Misc chat deaths are already part of totalWaveDeaths, so
         * they naturally live inside this remainder rather than being added a
         * second time.
         */
        wave.miscDeaths =
                Math.max(
                        0,
                        totalWaveDeaths
                                - disasterDeaths
                                - wave.fallDeaths
                                - wave.voidDeaths
                );

        wave.causeAccountingFinalized = true;

        updateDerivedMiscOverlay(
                null
        );

        LOGGER.info(
                "Wave {} cause accounting: total={} disaster={} fall={} void={} misc={} explicitMiscChat={}",
                wave.wave,
                totalWaveDeaths,
                disasterDeaths,
                wave.fallDeaths,
                wave.voidDeaths,
                wave.miscDeaths,
                wave.explicitMiscChatDeaths
        );
    }

    private static void finalizeLastWave(
            Integer playersAfterRaw,
            Integer playersAfterModel
    ) {
        if (waves.isEmpty()) {
            return;
        }

        WaveState wave = waves.get(waves.size() - 1);

        if (wave.playersAfterRaw != null) {
            return;
        }

        wave.playersAfterRaw = playersAfterRaw;
        wave.playersAfterModel = playersAfterModel;

        finalizeWaveCauseAccounting(
                wave
        );
    }

    private static boolean isWerewolfWinState(
            Integer playersAliveRaw
    ) {
        /*
         * Hypixel's raw Players Alive includes the transformed Werewolf while
         * they are alive. Therefore:
         *
         *   selected Werewolf exists
         *   no Werewolf killed/left-game terminal status has been seen
         *   raw Players Alive == 1
         *
         * means the Werewolf is the sole remaining player and the match has
         * effectively ended with zero normal survivors in our ranking.
         */
        return playersAliveRaw != null
                && playersAliveRaw == 1
                && werewolfPlayerName != null
                && !werewolfPlayerName.isBlank()
                && !werewolfKilledMessageDetected;
    }

    private static boolean shouldFinalizeWithoutZero() {
        if (waves.isEmpty()) {
            return false;
        }

        if (lastPlayersAliveRaw != null
                && lastPlayersAliveRaw == 0) {
            /*
             * All-dead games are complete regardless of wave count or
             * remaining timer. This supports W1/W2 wipes and lets TTR
             * renormalize over whatever predictions actually existed.
             */
            return true;
        }

        if (isWerewolfWinState(
                lastPlayersAliveRaw
        )) {
            return true;
        }

        return waves.size() >= 3
                && previousTimeLeft != null
                && previousTimeLeft <= 5;
    }

    private static void saveCompletedGame() {
        if (gameFinalized || currentGameId == null) {
            return;
        }

        gameFinalized = true;

        String endTimestamp = Instant.now().toString();

        Integer endPlayersRaw = lastPlayersAliveRaw;
        Integer endPlayersModel = lastPlayersAliveModel;

        if (!waves.isEmpty()) {
            WaveState last = waves.get(waves.size() - 1);

            if (last.playersAfterRaw == null) {
                last.playersAfterRaw = endPlayersRaw;
                last.playersAfterModel = endPlayersModel;
            }

            finalizeWaveCauseAccounting(
                    last
            );
        }

        Integer firstWaveRaw =
                waves.isEmpty()
                        ? null
                        : waves.get(0).playersAtRollRaw;

        Integer firstWaveModel =
                waves.isEmpty()
                        ? null
                        : waves.get(0).playersAtRollModel;

        Integer beforeWave1LossesRaw =
                subtract(
                        gameStartPlayersRaw,
                        firstWaveRaw
                );

        Integer beforeWave1LossesModel =
                subtract(
                        gameStartPlayersModel,
                        firstWaveModel
                );

        /*
         * Prediction target is recorder-excluded when Recorder moving=OFF,
         * so TTR loss must be scored against the same target.
         */
        Double ticketToRideLoss =
                EndPlayerPredictor.ticketToRideLoss(
                        endPlayerPredictionRawValues,
                        endPlayersModel
                );

        if (ticketToRideLoss == null) {
            ticketToRideLoss =
                    EndPlayerPredictor.ticketToRideLoss(
                            endPlayerPredictions,
                            endPlayersModel
                    );
        }

        /*
         * Freeze the death-attribution state into the game summary before any
         * later lobby/reset can mutate trackedDisasters.
         */
        Map<String, Integer> disasterDeathCounts =
                snapshotDisasterDeathCounts();

        GameSummary summary =
                new GameSummary(
                        currentGameId,
                        gameStartTimestamp,
                        endTimestamp,
                        currentHeader,
                        currentMap,

                        recorderMovingForGame,
                        playerCountBasisForGame,

                        true,
                        gameStartPlayersRaw,
                        gameStartPlayersModel,
                        gameStartTotalHealth,
                        lobbyCapacity,
                        observedFromStart,

                        beforeWave1LossesRaw,
                        beforeWave1LossesModel,

                        endPlayersRaw,
                        endPlayersModel,
                        lastRecorderAlive,

                        waves.stream()
                                .map(WaveState::toSummary)
                                .toList(),

                        new ArrayList<>(seenDisasters),

                        EndPlayerPredictor.modelId(
                                predictionModelForGame,
                                percentageBasedForGame,
                                waveSpecificForGame
                        ),
                        percentageBasedForGame,
                        waveSpecificForGame,
                        waveSpecificModeForGame.name(),
                        new ArrayList<>(endPlayerPredictions),
                        ticketToRideLoss,
                        waves.stream()
                                .mapToInt(wave -> wave.fallDeaths)
                                .sum(),
                        waves.stream()
                                .mapToInt(wave -> wave.voidDeaths)
                                .sum(),
                        waves.stream()
                                .mapToInt(wave -> wave.miscDeaths)
                                .sum(),
                        unmatchedChatMessages,
                        disasterDeathCounts
                );

        if (!suppressCurrentGameStats) {
            appendJson(
                    GAMES_JSONL_FILE,
                    summary
            );

            appendGameCsv(
                    summary
            );
        }

        Map<String, Double> userPredictionLosses =
                new LinkedHashMap<>();

        for (UserPredictionState state :
                userPredictions.values()) {

            Double loss =
                    EndPlayerPredictor.ticketToRideLoss(
                            state.guesses,
                            endPlayersModel
                    );

            if (loss != null) {
                userPredictionLosses.put(
                        state.displayName,
                        loss
                );
            }
        }

        PredictionOverlay.setCommunityFinalLosses(
                userPredictionLosses
        );

        /*
         * Overlay 1 is removed at game end. Overlay 2 replaces it with
         * the three historical guesses, actual end count, and TTR loss.
         */
        PredictionOverlay.showFinalSummary(
                new ArrayList<>(endPlayerPredictions),
                endPlayersModel,
                ticketToRideLoss
        );

        if (!suppressCurrentGameStats) {
            ModelDataManager.refreshModelDataset();

            LOGGER.info(
                    "Saved completed game {} -> {}",
                    currentGameId,
                    GAMES_CSV_FILE
            );

        } else {
            LOGGER.info(
                    "Skipped completed-game stats persistence for {} because /dpred logs off was active for this match",
                    currentGameId
            );
        }
    }

    private static void appendGameCsv(GameSummary game) {
        try {
            Files.createDirectories(OUTPUT_DIRECTORY);

            boolean needsHeader =
                    !Files.exists(GAMES_CSV_FILE)
                            || Files.size(GAMES_CSV_FILE) == 0;

            if (needsHeader) {
                Files.writeString(
                        GAMES_CSV_FILE,
                        csvHeader() + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            }

            Files.writeString(
                    GAMES_CSV_FILE,
                    csvRow(game) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );

        } catch (IOException exception) {
            LOGGER.error("Could not write game CSV", exception);
        }
    }

    private static String csvHeader() {
        List<String> columns = new ArrayList<>(
                List.of(
                        "schema_version",
                        "game_id",
                        "start_timestamp",
                        "end_timestamp",
                        "hypixel_header",
                        "map",
                        "starting_players_total",
                        "lobby_capacity",
                        "observed_from_start",

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

                        "wave_count",
                        "end_players_total",
                        "total_deaths",
                        "end_survival_fraction",
                        "disaster_count",
                        "all_disasters"
                )
        );

        columns.addAll(EXTRA_CSV_COLUMNS);

        return String.join(",", columns);
    }

    private static String csvRow(GameSummary game) {
        List<String> cells = new ArrayList<>();

        cells.add("8");
        cells.add(game.gameId());
        cells.add(game.startTimestamp());
        cells.add(game.endTimestamp());
        cells.add(game.header());
        cells.add(game.map());

        // Old/raw-total audit columns
        cells.add(intString(game.gameStartPlayersRaw()));
        cells.add(intString(game.lobbyCapacity()));
        cells.add(Boolean.toString(game.observedFromStart()));

        for (int i = 0; i < 3; i++) {
            WaveSummary wave =
                    i < game.waves().size()
                            ? game.waves().get(i)
                            : null;

            addRawWaveCsvCells(cells, wave);
        }

        cells.add(Integer.toString(game.waves().size()));
        cells.add(intString(game.endPlayersRaw()));
        cells.add(
                intString(
                        subtract(
                                game.gameStartPlayersRaw(),
                                game.endPlayersRaw()
                        )
                )
        );
        cells.add(
                fractionString(
                        game.endPlayersRaw(),
                        game.gameStartPlayersRaw()
                )
        );
        cells.add(Integer.toString(game.allDisasters().size()));
        cells.add(String.join(" | ", game.allDisasters()));

        // v0.6 model/compatibility columns
        cells.add(Boolean.toString(game.recorderMoving()));
        cells.add(game.playerCountBasis());
        cells.add(Boolean.toString(game.recorderAliveAtGameStart()));
        cells.add(intString(game.gameStartPlayersModel()));
        cells.add(intString(game.beforeWave1LossesRaw()));
        cells.add(intString(game.beforeWave1LossesModel()));

        for (int i = 0; i < 3; i++) {
            WaveSummary wave =
                    i < game.waves().size()
                            ? game.waves().get(i)
                            : null;

            if (wave == null) {
                cells.add("");
                cells.add("");
                cells.add("");
            } else {
                cells.add(Boolean.toString(wave.recorderAliveAtRoll()));
                cells.add(intString(wave.playersAtRollModel()));
                cells.add(intString(wave.playersAfterModel()));
            }
        }

        cells.add(Boolean.toString(game.recorderAliveEnd()));
        cells.add(intString(game.endPlayersModel()));
        cells.add(
                intString(
                        subtract(
                                game.gameStartPlayersModel(),
                                game.endPlayersModel()
                        )
                )
        );
        cells.add(
                fractionString(
                        game.endPlayersModel(),
                        game.gameStartPlayersModel()
                )
        );

        cells.add(game.predictionModel());
        cells.add(
                Boolean.toString(
                        game.percentageBasedAverages()
                )
        );

        cells.add(
                Boolean.toString(
                        game.waveSpecific()
                )
        );

        for (int i = 0; i < 3; i++) {
            Integer prediction =
                    i < game.endPlayerPredictions().size()
                            ? game.endPlayerPredictions().get(i)
                            : null;

            cells.add(intString(prediction));
        }

        cells.add(
                game.ticketToRideLoss() == null
                        ? ""
                        : Double.toString(
                                game.ticketToRideLoss()
                        )
        );

        boolean completeGame =
                game.observedFromStart()
                        && game.waves().size() == 3
                        && game.endPlayersRaw() != null;

        cells.add(Boolean.toString(completeGame));

        cells.add(
                disasterChatDeathsSummary(
                        game
                )
        );

        /*
         * Dedicated numeric fall column requested for analysis.
         */
        cells.add(
                Integer.toString(
                        game.fallDeaths()
                )
        );

        cells.add(
                Integer.toString(
                        game.voidDeaths()
                )
        );

        cells.add(
                Integer.toString(
                        game.miscDeaths()
                )
        );

        for (int i = 0; i < 3; i++) {
            WaveSummary wave =
                    i < game.waves().size()
                            ? game.waves().get(i)
                            : null;

            if (wave == null) {
                cells.add("");
                cells.add("");
                cells.add("");
            } else {
                cells.add(
                        Integer.toString(
                                wave.fallDeaths()
                        )
                );
                cells.add(
                        Integer.toString(
                                wave.voidDeaths()
                        )
                );
                cells.add(
                        Integer.toString(
                                wave.miscDeaths()
                        )
                );
            }
        }

        /*
         * Canonical per-wave disaster-attribution training fields.
         */
        for (int i = 0;
             i < 3;
             i++) {

            WaveSummary wave =
                    i < game.waves().size()
                            ? game.waves().get(i)
                            : null;

            if (wave == null) {
                cells.add("");

                for (DisasterDeathCsvField ignored :
                        DISASTER_DEATH_CSV_FIELDS) {
                    cells.add("");
                }

                continue;
            }

            cells.add(
                    String.join(
                            " | ",
                            wave.activeDisastersAtRoll()
                    )
            );

            for (DisasterDeathCsvField field :
                    DISASTER_DEATH_CSV_FIELDS) {

                cells.add(
                        Integer.toString(
                                wave.disasterDeaths()
                                        .getOrDefault(
                                                field.disasterName()
                                                        .toLowerCase(
                                                                Locale.ROOT
                                                        ),
                                                0
                                        )
                        )
                );
            }
        }

        /*
         * Whole-game one-column-per-disaster totals remain for audit.
         */
        for (DisasterDeathCsvField field :
                DISASTER_DEATH_CSV_FIELDS) {

            cells.add(
                    Integer.toString(
                            disasterDeathCount(
                                    game,
                                    field.disasterName()
                            )
                    )
            );
        }

        /*
         * Schema-8 health audit values. Total health is on the modeled basis;
         * the 16 slot columns are raw player health in the fixed tab-list
         * order captured at game start. Dead tracked players are 0. Slots
         * that did not exist in a <16-player start, or an invalid/missing
         * health snapshot, are blank.
         */
        cells.add(
                intString(
                        game.gameStartTotalHealth()
                )
        );

        for (int i = 0; i < 3; i++) {
            WaveSummary wave =
                    i < game.waves().size()
                            ? game.waves().get(i)
                            : null;

            cells.add(
                    wave == null
                            ? ""
                            : intString(
                                    wave.totalHealthAtRoll()
                            )
            );
        }

        for (int i = 0; i < 3; i++) {
            WaveSummary wave =
                    i < game.waves().size()
                            ? game.waves().get(i)
                            : null;

            for (int slot = 0;
                 slot < TAB_HEALTH_SLOTS;
                 slot++) {

                if (wave == null
                        || slot >= wave.playerHealthAtRoll().size()) {
                    cells.add("");
                    continue;
                }

                Integer health =
                        wave.playerHealthAtRoll().get(
                                slot
                        );

                cells.add(
                        intString(
                                health
                        )
                );
            }
        }

        return cells.stream()
                .map(DisastersTrackerClient::csvEscape)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    private static Map<String, Integer>
    snapshotDisasterDeathCounts() {
        Map<String, Integer> counts =
                new LinkedHashMap<>();

        for (DisasterDeathCsvField field :
                DISASTER_DEATH_CSV_FIELDS) {

            TrackedDisaster tracked =
                    trackedDisasters.get(
                            field.disasterName()
                                    .toLowerCase(
                                            Locale.ROOT
                                    )
                    );

            counts.put(
                    field.disasterName(),
                    tracked == null
                            ? 0
                            : tracked.chatDeaths
            );
        }

        return Map.copyOf(counts);
    }

    private static int disasterDeathCount(
            GameSummary game,
            String disasterName
    ) {
        if (game.disasterDeathCounts() == null) {
            return 0;
        }

        Integer count =
                game.disasterDeathCounts().get(
                        disasterName
                );

        return count == null
                ? 0
                : count;
    }

    private static String disasterChatDeathsSummary(
            GameSummary game
    ) {
        List<String> values =
                new ArrayList<>();

        /*
         * Keep the compact human-readable summary for convenience, while the
         * fixed numeric columns below are the canonical analysis interface.
         */
        for (String disaster :
                game.allDisasters()) {

            values.add(
                    disaster
                            + "="
                            + disasterDeathCount(
                            game,
                            disaster
                    )
            );
        }

        values.add(
                "Fall="
                        + game.fallDeaths()
        );

        values.add(
                "Void="
                        + game.voidDeaths()
        );

        values.add(
                "Misc="
                        + game.miscDeaths()
        );

        values.add(
                "Chats="
                        + game.chatMessages()
        );

        return String.join(
                " | ",
                values
        );
    }

    private static void addRawWaveCsvCells(
            List<String> cells,
            WaveSummary wave
    ) {
        if (wave == null) {
            for (int i = 0; i < 7; i++) {
                cells.add("");
            }
            return;
        }

        cells.add(intString(wave.timeLeftSeconds()));
        cells.add(intString(wave.playersAtRollRaw()));
        cells.add(String.join(" | ", wave.disasters()));
        cells.add(intString(wave.playersAfterRaw()));

        Integer deaths =
                subtract(
                        wave.playersAtRollRaw(),
                        wave.playersAfterRaw()
                );

        cells.add(intString(deaths));
        cells.add(
                fractionString(
                        wave.playersAfterRaw(),
                        wave.playersAtRollRaw()
                )
        );
        cells.add(
                fractionString(
                        deaths,
                        wave.playersAtRollRaw()
                )
        );
    }

    private static Integer subtract(Integer a, Integer b) {
        if (a == null || b == null) {
            return null;
        }

        return a - b;
    }

    private static String fractionString(
            Integer numerator,
            Integer denominator
    ) {
        if (numerator == null
                || denominator == null
                || denominator == 0) {
            return "";
        }

        return Double.toString(
                (double) numerator / denominator
        );
    }

    private static String intString(Integer value) {
        return value == null
                ? ""
                : Integer.toString(value);
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "\"\"";
        }

        return "\""
                + value.replace("\"", "\"\"")
                + "\"";
    }

    private static String canonicalDisasterName(
            String line
    ) {
        if (line == null
                || line.isBlank()) {
            return null;
        }

        String cleaned =
                stripFormatting(
                        line
                ).strip();

        if (cleaned.isBlank()
                || NEXT_DISASTER_PATTERN.matcher(
                cleaned
        ).matches()
                || cleaned.toLowerCase(
                Locale.ROOT
        ).contains(
                "hypixel.net"
        )) {
            return null;
        }

        for (DisasterDeathCsvField field :
                DISASTER_DEATH_CSV_FIELDS) {

            if (field.disasterName()
                    .equalsIgnoreCase(
                            cleaned
                    )) {
                return field.disasterName();
            }
        }

        /*
         * Only exact canonical names are valid. During a sidebar rebuild the
         * client can briefly expose a team prefix without its suffix (for
         * example "The Floor is L"). Unknown/partial labels are ignored and
         * a later complete snapshot is allowed to introduce the disaster.
         */
        return null;
    }

    private static boolean isRealDisaster(String line) {
        return canonicalDisasterName(
                line
        ) != null;
    }

    private static List<String> realDisasters(List<String> source) {
        LinkedHashSet<String> canonical =
                new LinkedHashSet<>();

        for (String line : source) {
            String disaster =
                    canonicalDisasterName(
                            line
                    );

            if (disaster != null) {
                canonical.add(
                        disaster
                );
            }
        }

        return List.copyOf(
                canonical
        );
    }

    private static SidebarLine makeSidebarLine(
            Scoreboard scoreboard,
            PlayerScoreEntry entry
    ) {
        PlayerTeam team =
                scoreboard.getPlayersTeam(
                        entry.owner()
                );

        Component renderedName =
                PlayerTeam.formatNameForTeam(
                        team,
                        entry.ownerName()
                );

        String raw =
                renderedName.getString();

        StringBuilder visualText =
                new StringBuilder();

        List<Integer> visualColors =
                new ArrayList<>();

        try {
            renderedName.getVisualOrderText().accept(
                    (index, style, codePoint) -> {
                        visualText.appendCodePoint(
                                codePoint
                        );

                        int rgb =
                                style.getColor() == null
                                        ? -1
                                        : style.getColor().getValue();

                        int charCount =
                                Character.charCount(
                                        codePoint
                                );

                        for (int i = 0;
                             i < charCount;
                             i++) {
                            visualColors.add(rgb);
                        }

                        return true;
                    }
            );
        } catch (Exception exception) {
            LOGGER.debug(
                    "Could not inspect sidebar colors",
                    exception
            );
        }

        return new SidebarLine(
                stripFormatting(raw),
                raw,
                entry.owner(),
                entry.value(),
                visualText.toString(),
                List.copyOf(
                        visualColors
                )
        );
    }

    private static String stripFormatting(String text) {
        if (text == null) {
            return "";
        }

        return text
                .replaceAll("§.", "")
                .replace('\u00A0', ' ')
                .strip();
    }

    private static String makeFingerprint(
            String title,
            List<SidebarLine> lines
    ) {
        StringBuilder builder = new StringBuilder(title);

        for (SidebarLine line : lines) {
            builder.append('\n')
                    .append(line.rawText())
                    .append('|')
                    .append(line.score());
        }

        return builder.toString();
    }

    private static void appendJson(Path file, Object value) {
        try {
            Files.createDirectories(OUTPUT_DIRECTORY);

            Files.writeString(
                    file,
                    GSON.toJson(value) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );

        } catch (IOException exception) {
            LOGGER.error("Could not write {}", file, exception);
        }
    }


    private static void removeObsoleteLiveGamesCsvColumn(
            String columnName
    ) {
        try {
            if (!Files.exists(GAMES_CSV_FILE)
                    || Files.size(GAMES_CSV_FILE) == 0) {
                return;
            }

            List<String> lines =
                    Files.readAllLines(
                            GAMES_CSV_FILE,
                            StandardCharsets.UTF_8
                    );

            if (lines.isEmpty()) {
                return;
            }

            List<String> headerCells =
                    parseCsvCellsForMigration(
                            lines.get(0)
                    );

            int obsoleteIndex =
                    headerCells.indexOf(
                            columnName
                    );

            if (obsoleteIndex < 0) {
                return;
            }

            headerCells.remove(
                    obsoleteIndex
            );

            List<String> migrated =
                    new ArrayList<>();

            migrated.add(
                    String.join(
                            ",",
                            headerCells
                    )
            );

            for (int lineIndex = 1;
                 lineIndex < lines.size();
                 lineIndex++) {

                String line =
                        lines.get(lineIndex);

                if (line.isBlank()) {
                    migrated.add(line);
                    continue;
                }

                List<String> cells =
                        parseCsvCellsForMigration(
                                line
                        );

                if (obsoleteIndex < cells.size()) {
                    cells.remove(
                            obsoleteIndex
                    );
                }

                migrated.add(
                        cells.stream()
                                .map(
                                        DisastersTrackerClient::csvEscape
                                )
                                .reduce(
                                        (a, b) ->
                                                a + "," + b
                                )
                                .orElse("")
                );
            }

            Path temp =
                    OUTPUT_DIRECTORY.resolve(
                            "games.csv.remove-obsolete"
                    );

            Files.write(
                    temp,
                    migrated,
                    StandardCharsets.UTF_8
            );

            try {
                Files.move(
                        temp,
                        GAMES_CSV_FILE,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temp,
                        GAMES_CSV_FILE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

            LOGGER.info(
                    "Removed obsolete games.csv column: {}",
                    columnName
            );

        } catch (IOException exception) {
            LOGGER.error(
                    "Could not remove obsolete games.csv column {}",
                    columnName,
                    exception
            );
        }
    }

    private static List<String> parseCsvCellsForMigration(
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

    private static void migrateLiveGamesCsvIfNeeded() {
        try {
            if (!Files.exists(GAMES_CSV_FILE)
                    || Files.size(GAMES_CSV_FILE) == 0) {
                return;
            }

            List<String> lines =
                    Files.readAllLines(
                            GAMES_CSV_FILE,
                            StandardCharsets.UTF_8
                    );

            if (lines.isEmpty()) {
                return;
            }

            List<String> oldHeader =
                    parseCsvCellsForMigration(
                            lines.get(0)
                    );

            List<String> canonicalHeader =
                    parseCsvCellsForMigration(
                            csvHeader()
                    );

            boolean headerAlreadyCanonical =
                    oldHeader.equals(
                            canonicalHeader
                    );

            List<String> schema4Columns =
                    canonicalHeader.stream()
                            .filter(
                                    column ->
                                            !isPerWaveDisasterTrainingColumn(
                                                    column
                                            )
                            )
                            .toList();

            List<String> schema3Columns =
                    schema4Columns.stream()
                            .filter(
                                    column ->
                                            !isPerWaveCauseColumn(
                                                    column
                                            )
                            )
                            .toList();

            List<String> migrated =
                    new ArrayList<>();

            migrated.add(
                    String.join(
                            ",",
                            canonicalHeader
                    )
            );

            int rewrittenRows = 0;

            for (int lineIndex = 1;
                 lineIndex < lines.size();
                 lineIndex++) {

                String line =
                        lines.get(lineIndex);

                if (line.isBlank()) {
                    continue;
                }

                List<String> sourceCells =
                        parseCsvCellsForMigration(
                                line
                        );

                int schemaVersion =
                        parseMigrationInt(
                                sourceCells.isEmpty()
                                        ? ""
                                        : sourceCells.get(0)
                        );

                List<String> sourceColumns;

                if (headerAlreadyCanonical) {
                    sourceColumns =
                            canonicalHeader;
                } else if (schemaVersion >= 5) {
                    /*
                     * v0.9.49/v0.9.50 rows were WRITTEN in canonical order,
                     * even though the old migration had left the file HEADER
                     * in append-history order.
                     */
                    sourceColumns =
                            canonicalHeader;
                } else if (schemaVersion == 4) {
                    sourceColumns =
                            schema4Columns;
                } else if (schemaVersion == 3) {
                    sourceColumns =
                            schema3Columns;
                } else {
                    /*
                     * Schema 1/2 rows predate the ordering regression and are
                     * aligned to the historical file header.
                     */
                    sourceColumns =
                            oldHeader;
                }

                Map<String, String> values =
                        new LinkedHashMap<>();

                int paired =
                        Math.min(
                                sourceCells.size(),
                                sourceColumns.size()
                        );

                for (int i = 0;
                     i < paired;
                     i++) {
                    values.put(
                            sourceColumns.get(i),
                            sourceCells.get(i)
                    );
                }

                /*
                 * The experiment intentionally treats every previously-seen
                 * disaster as still relevant in later waves. Reconstruct the
                 * stored wave_N_active_disasters cumulatively from the
                 * authoritative wave_N_disasters start events. This also
                 * repairs old schema-5 rows whose active list depended on
                 * strike-through parsing.
                 */
                if (schemaVersion >= 5) {
                    LinkedHashSet<String> cumulative =
                            new LinkedHashSet<>();

                    for (int wave = 1;
                         wave <= 3;
                         wave++) {

                        cumulative.addAll(
                                parseDisasterCellForMigration(
                                        values.get(
                                                "wave_"
                                                        + wave
                                                        + "_disasters"
                                        )
                                )
                        );

                        values.put(
                                "wave_"
                                        + wave
                                        + "_active_disasters",
                                String.join(
                                        " | ",
                                        cumulative
                                )
                        );
                    }
                }

                String summary =
                        values.getOrDefault(
                                "disaster_chat_deaths",
                                ""
                        );

                Map<String, Integer> oldDeathCounts =
                        parseHistoricalDeathSummary(
                                summary
                        );

                List<String> canonicalCells =
                        new ArrayList<>();

                for (String column :
                        canonicalHeader) {

                    String value =
                            values.getOrDefault(
                                    column,
                                    ""
                            );

                    if (value.isBlank()) {
                        value =
                                historicalBackfillValue(
                                        column,
                                        summary,
                                        oldDeathCounts
                                );
                    }

                    canonicalCells.add(
                            value
                    );
                }

                migrated.add(
                        canonicalCells.stream()
                                .map(
                                        DisastersTrackerClient::csvEscape
                                )
                                .reduce(
                                        (a, b) ->
                                                a + "," + b
                                )
                                .orElse("")
                );

                rewrittenRows++;
            }

            Path temp =
                    OUTPUT_DIRECTORY.resolve(
                            "games.csv.migrating"
                    );

            Files.write(
                    temp,
                    migrated,
                    StandardCharsets.UTF_8
            );

            try {
                Files.move(
                        temp,
                        GAMES_CSV_FILE,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temp,
                        GAMES_CSV_FILE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

            LOGGER.info(
                    "Canonicalized games.csv header/order and cumulative seen-disaster state for {} rows",
                    rewrittenRows
            );

        } catch (IOException exception) {
            LOGGER.error(
                    "Could not migrate games.csv schema",
                    exception
            );
        }
    }

    private static int parseMigrationInt(
            String value
    ) {
        try {
            return Integer.parseInt(
                    value == null
                            ? ""
                            : value.strip()
            );
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean isPerWaveCauseColumn(
            String column
    ) {
        return column != null
                && column.matches(
                "wave_[123]_(?:fall|void|misc)"
        );
    }

    private static boolean isPerWaveDisasterTrainingColumn(
            String column
    ) {
        return column != null
                && (
                column.matches(
                        "wave_[123]_active_disasters"
                )
                        || column.matches(
                        "wave_[123]_deaths_.+"
                )
        );
    }

    private static List<String> parseDisasterCellForMigration(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return List.of();
        }

        return Arrays.stream(
                        value.split("\\s*\\|\\s*")
                )
                .map(String::strip)
                .filter(part -> !part.isBlank())
                .toList();
    }

    private static Map<String, Integer>
    parseHistoricalDeathSummary(
            String summary
    ) {
        Map<String, Integer> counts =
                new LinkedHashMap<>();

        if (summary == null
                || summary.isBlank()) {
            return counts;
        }

        for (String part :
                summary.split("\\|")) {

            String cleaned =
                    part.strip();

            int equalsIndex =
                    cleaned.lastIndexOf('=');

            if (equalsIndex <= 0
                    || equalsIndex
                    >= cleaned.length() - 1) {
                continue;
            }

            String name =
                    cleaned.substring(
                            0,
                            equalsIndex
                    ).strip();

            String countText =
                    cleaned.substring(
                            equalsIndex + 1
                    ).strip();

            try {
                counts.put(
                        name.toLowerCase(
                                Locale.ROOT
                        ),
                        Integer.parseInt(
                                countText
                        )
                );
            } catch (NumberFormatException ignored) {
                // Leave malformed historical values blank.
            }
        }

        return counts;
    }

    private static String historicalBackfillValue(
            String column,
            String summary,
            Map<String, Integer> counts
    ) {
        /*
         * Rows from before chat-attribution existed have no summary evidence.
         * Keep those new columns blank rather than pretending they were zeros.
         */
        if (summary == null
                || summary.isBlank()) {
            return "";
        }

        for (DisasterDeathCsvField field :
                DISASTER_DEATH_CSV_FIELDS) {

            if (!field.columnName()
                    .equals(column)) {
                continue;
            }

            return Integer.toString(
                    counts.getOrDefault(
                            field.disasterName()
                                    .toLowerCase(
                                            Locale.ROOT
                                    ),
                            0
                    )
            );
        }

        /*
         * Non-death columns added by older schema revisions cannot be
         * reconstructed from disaster_chat_deaths.
         */
        return "";
    }

    private static boolean headerContainsCsvColumn(
            String header,
            String column
    ) {
        for (String part : header.split(",")) {
            String cleaned =
                    part.strip()
                            .replace("\"", "");

            if (cleaned.equals(column)) {
                return true;
            }
        }

        return false;
    }

    private static void resetCurrentMatch() {
        matchActive = false;
        gameFinalized = false;
        sidebarGapStartedAtMs = 0L;
        suppressCurrentGameStats = false;

        currentGameId = null;
        gameStartTimestamp = null;
        currentHeader = null;
        currentMap = null;

        gameStartPlayersRaw = null;
        gameStartPlayersModel = null;
        gameStartTotalHealth = null;
        tabHealthRoster.clear();

        lobbyCapacity = null;
        previousTimeLeft = null;

        lastPlayersAliveRaw = null;
        lastPlayersAliveModel = null;
        lastRecorderAlive = true;

        recorderDeathDetected = false;
        recorderDeathMessage = null;
        recorderDeathTimestamp = null;

        werewolfAdjustmentActive = false;
        werewolfKilledMessageDetected = false;
        werewolfPlayerName = null;
        recorderIsWerewolfForGame = false;
        werewolfTransformationDeathPending = false;
        werewolfTransformationDeathCounted = false;
        werewolfSelectionMessage = null;

        observedFromStart = false;

        recorderMovingForGame =
                ModelDataManager.isRecorderMoving();

        predictionModelForGame =
                ModelDataManager.getPredictionModel();

        percentageBasedForGame =
                ModelDataManager.isPercentageBasedAverages();

        waveSpecificModeForGame =
                ModelDataManager.getWaveSpecificMode();

        waveSpecificForGame =
                waveSpecificModeForGame
                        != ModelDataManager.WaveSpecificMode.OFF;

        playerCountBasisForGame =
                recorderMovingForGame
                        ? "total_including_recorder"
                        : "excluding_recorder_when_alive";

        seenDisasters.clear();
        trackedDisasters.clear();
        activeDisastersForChat.clear();
        unmatchedChatMessages = 0;
        fallChatDeaths = 0;
        voidChatDeaths = 0;
        miscChatDeaths = 0;
        waves.clear();
        endPlayerPredictions.clear();
        endPlayerPredictionRawValues.clear();
        userPredictions.clear();
    }

    private static final class UserPredictionState {
        private String displayName;
        private final List<Double> guesses =
                new ArrayList<>(
                        Arrays.asList(
                                null,
                                null,
                                null
                        )
                );

        private UserPredictionState(
                String displayName
        ) {
            this.displayName =
                    displayName;
        }
    }

    private static final class WaveState {
        private final int wave;
        private final Integer timeLeftSeconds;

        private final Integer playersAtRollRaw;
        private final Integer playersAtRollModel;
        private final Integer totalHealthAtRoll;
        private final List<Integer> playerHealthAtRoll;
        private final boolean recorderAliveAtRoll;

        private final List<String> disasters;
        private final List<String> activeDisastersAtRoll;
        private final long startedAtMs;

        private Integer playersAfterRaw;
        private Integer playersAfterModel;

        private final Map<String, Integer> disasterDeaths =
                new LinkedHashMap<>();

        private int fallDeaths = 0;
        private int voidDeaths = 0;
        private int explicitMiscChatDeaths = 0;
        private int miscDeaths = 0;
        private boolean causeAccountingFinalized = false;

        private WaveState(
                int wave,
                Integer timeLeftSeconds,
                Integer playersAtRollRaw,
                Integer playersAtRollModel,
                Integer totalHealthAtRoll,
                List<Integer> playerHealthAtRoll,
                boolean recorderAliveAtRoll,
                List<String> disasters,
                List<String> activeDisastersAtRoll
        ) {
            this.wave = wave;
            this.timeLeftSeconds = timeLeftSeconds;
            this.playersAtRollRaw = playersAtRollRaw;
            this.playersAtRollModel = playersAtRollModel;
            this.totalHealthAtRoll = totalHealthAtRoll;
            this.playerHealthAtRoll =
                    playerHealthAtRoll == null
                            ? List.of()
                            : Collections.unmodifiableList(
                                    new ArrayList<>(
                                            playerHealthAtRoll
                                    )
                            );
            this.recorderAliveAtRoll = recorderAliveAtRoll;
            this.disasters = disasters;
            this.startedAtMs =
                    System.currentTimeMillis();
            this.activeDisastersAtRoll =
                    activeDisastersAtRoll == null
                            ? List.of()
                            : List.copyOf(
                                    activeDisastersAtRoll
                            );
        }

        private WaveSummary toSummary() {
            return new WaveSummary(
                    wave,
                    timeLeftSeconds,
                    playersAtRollRaw,
                    playersAtRollModel,
                    totalHealthAtRoll,
                    playerHealthAtRoll,
                    playersAfterRaw,
                    playersAfterModel,
                    recorderAliveAtRoll,
                    disasters,
                    activeDisastersAtRoll,
                    Map.copyOf(
                            disasterDeaths
                    ),
                    fallDeaths,
                    voidDeaths,
                    miscDeaths
            );
        }
    }

    private static final class TrackedDisaster {
        private final String name;
        private final Character colorCode;
        private final Integer colorRgb;
        private final List<Integer> colorPattern;
        private int chatDeaths;

        private TrackedDisaster(
                String name,
                Character colorCode,
                Integer colorRgb,
                List<Integer> colorPattern
        ) {
            this.name = name;
            this.colorCode = colorCode;
            this.colorRgb = colorRgb;
            this.colorPattern =
                    colorPattern == null
                            ? List.of()
                            : List.copyOf(
                                    colorPattern
                            );
            this.chatDeaths = 0;
        }
    }

    private record TabHealthSnapshot(
            Integer modeledTotalHealth,
            List<Integer> slotHealth
    ) {}

    private record DisasterDeathAttribution(
            String timestamp,
            String gameId,
            String disaster,
            String colorCode,
            Integer colorRgb,
            String message,
            Integer timeLeftSeconds,
            int cumulativeDeaths
    ) {}

    private record SidebarLine(
            String text,
            String rawText,
            String owner,
            int score,
            String visualText,
            List<Integer> visualColors
    ) {}

    private record RawSnapshot(
            String timestamp,
            String title,
            List<SidebarLine> lines
    ) {}

    private record ParsedSidebar(
            String header,
            String map,
            Integer lobbyPlayers,
            Integer lobbyCapacity,
            Integer timeLeftSeconds,
            Integer playersAlive,
            boolean hasDisastersSection,
            List<String> allDisasters,
            List<String> activeDisasters
    ) {}

    private record ParsedSnapshot(
            String timestamp,
            String gameId,
            String header,
            String map,
            boolean recorderMoving,
            String playerCountBasis,
            Integer gameStartPlayersRaw,
            Integer gameStartPlayersModel,
            Integer lobbyCapacity,
            Integer timeLeftSeconds,
            Integer playersAliveRaw,
            Integer playersAliveModel,
            boolean recorderAlive,
            List<String> allDisasters,
            List<String> activeDisasters,
            List<String> newDisasters
    ) {}

    private record RecorderDeathDetection(
            String timestamp,
            String gameId,
            String username,
            String message,
            Integer timeLeftSeconds,
            Integer playersAliveRaw,
            Integer playersAliveModelAfterDeath
    ) {}

    private record WaveSummary(
            int wave,
            Integer timeLeftSeconds,
            Integer playersAtRollRaw,
            Integer playersAtRollModel,
            Integer totalHealthAtRoll,
            List<Integer> playerHealthAtRoll,
            Integer playersAfterRaw,
            Integer playersAfterModel,
            boolean recorderAliveAtRoll,
            List<String> disasters,
            List<String> activeDisastersAtRoll,
            Map<String, Integer> disasterDeaths,
            int fallDeaths,
            int voidDeaths,
            int miscDeaths
    ) {}

    private record GameSummary(
            String gameId,
            String startTimestamp,
            String endTimestamp,
            String header,
            String map,

            boolean recorderMoving,
            String playerCountBasis,

            boolean recorderAliveAtGameStart,
            Integer gameStartPlayersRaw,
            Integer gameStartPlayersModel,
            Integer gameStartTotalHealth,
            Integer lobbyCapacity,
            boolean observedFromStart,

            Integer beforeWave1LossesRaw,
            Integer beforeWave1LossesModel,

            Integer endPlayersRaw,
            Integer endPlayersModel,
            boolean recorderAliveEnd,

            List<WaveSummary> waves,
            List<String> allDisasters,

            String predictionModel,
            boolean percentageBasedAverages,
            boolean waveSpecific,
            String waveSpecificMode,
            List<Integer> endPlayerPredictions,
            Double ticketToRideLoss,
            int fallDeaths,
            int voidDeaths,
            int miscDeaths,
            int chatMessages,
            Map<String, Integer> disasterDeathCounts
    ) {}

    private record DisasterDeathCsvField(
            String disasterName,
            String columnName
    ) {}
}
