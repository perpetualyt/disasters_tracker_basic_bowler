package dev.perpetualyt.disasters;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class PerphetConfigScreen extends Screen {

    private final Screen parent;

    private Button recorderMovingButton;
    private Button recorderAliveOverlayButton;
    private Button overlay1Button;
    private Button overlay2Button;
    private Button disasterDeathsOverlayButton;
    private Button gamePercentileOverlayButton;
    private Button predictionModelButton;
    private Button waveSpecificButton;
    private Button percentageBasedButton;
    private EditBox predictionWindowSecondsBox;

    private int predictionWindowLabelX = -1;
    private int predictionWindowLabelY = -1;

    public PerphetConfigScreen(
            Screen parent
    ) {
        super(
                Component.literal(
                        "Perphet Settings"
                )
        );

        this.parent = parent;
    }

    @Override
    protected void init() {
        /*
         * Compact two-column layout so every option fits on ordinary
         * Minecraft GUI scales without scrolling.
         */
        int outerMargin = 16;
        int gap = 8;
        int maxPanelWidth = 560;

        int panelWidth =
                Math.min(
                        maxPanelWidth,
                        Math.max(
                                280,
                                this.width - 2 * outerMargin
                        )
                );

        int panelLeft =
                (this.width - panelWidth) / 2;

        boolean twoColumns =
                panelWidth >= 300;

        int columnWidth =
                twoColumns
                        ? (panelWidth - gap) / 2
                        : panelWidth;

        int leftColumn =
                panelLeft;

        int rightColumn =
                twoColumns
                        ? panelLeft + columnWidth + gap
                        : panelLeft;

        int top =
                twoColumns
                        ? 58
                        : 50;

        int buttonHeight =
                twoColumns
                        ? 20
                        : 18;

        int rowStep;

        if (twoColumns) {
            rowStep = 23;
        } else {
            /*
             * Height-aware fallback: compress the vertical step enough that
             * all controls plus Done remain on-screen.
             */
            int usableHeight =
                    Math.max(
                            162,
                            this.height - top - 36
                    );

            rowStep =
                    Math.max(
                            18,
                            Math.min(
                                    22,
                                    usableHeight / 11
                            )
                    );
        }

        if (twoColumns) {
            recorderMovingButton =
                    addToggleButton(
                            leftColumn,
                            top,
                            columnWidth,
                            buttonHeight,
                            recorderMovingText(),
                            () -> ModelDataManager.setRecorderMoving(
                                    !ModelDataManager.isRecorderMoving()
                            ),
                            () -> recorderMovingText()
                    );


            waveSpecificButton =
                    addToggleButton(
                            leftColumn,
                            top + rowStep,
                            columnWidth,
                            buttonHeight,
                            waveSpecificText(),
                            ModelDataManager::cycleWaveSpecificMode,
                            () -> waveSpecificText()
                    );

            percentageBasedButton =
                    addToggleButton(
                            leftColumn,
                            top + 2 * rowStep,
                            columnWidth,
                            buttonHeight,
                            percentageBasedText(),
                            () -> ModelDataManager
                                    .setPercentageBasedAverages(
                                            !ModelDataManager
                                                    .isPercentageBasedAverages()
                                    ),
                            () -> percentageBasedText()
                    );

            predictionModelButton =
                    addToggleButton(
                            leftColumn,
                            top + 3 * rowStep,
                            columnWidth,
                            buttonHeight,
                            predictionModelText(),
                            ModelDataManager::cyclePredictionModel,
                            () -> predictionModelText()
                    );

            overlay1Button =
                    addToggleButton(
                            rightColumn,
                            top,
                            columnWidth,
                            buttonHeight,
                            overlay1Text(),
                            () -> ModelDataManager.setOverlay1Enabled(
                                    !ModelDataManager.isOverlay1Enabled()
                            ),
                            () -> overlay1Text()
                    );

            overlay2Button =
                    addToggleButton(
                            rightColumn,
                            top + rowStep,
                            columnWidth,
                            buttonHeight,
                            overlay2Text(),
                            () -> ModelDataManager.setOverlay2Enabled(
                                    !ModelDataManager.isOverlay2Enabled()
                            ),
                            () -> overlay2Text()
                    );

            disasterDeathsOverlayButton =
                    addToggleButton(
                            rightColumn,
                            top + 2 * rowStep,
                            columnWidth,
                            buttonHeight,
                            disasterDeathsOverlayText(),
                            () -> ModelDataManager
                                    .setDisasterDeathsOverlayEnabled(
                                            !ModelDataManager
                                                    .isDisasterDeathsOverlayEnabled()
                                    ),
                            () -> disasterDeathsOverlayText()
                    );

            recorderAliveOverlayButton =
                    addToggleButton(
                            rightColumn,
                            top + 3 * rowStep,
                            columnWidth,
                            buttonHeight,
                            recorderAliveOverlayText(),
                            () -> ModelDataManager
                                    .setRecorderAliveOverlayEnabled(
                                            !ModelDataManager
                                                    .isRecorderAliveOverlayEnabled()
                                    ),
                            () -> recorderAliveOverlayText()
                    );

            gamePercentileOverlayButton =
                    addToggleButton(
                            rightColumn,
                            top + 4 * rowStep,
                            columnWidth,
                            buttonHeight,
                            gamePercentileOverlayText(),
                            () -> ModelDataManager
                                    .setGamePercentileOverlayEnabled(
                                            !ModelDataManager
                                                    .isGamePercentileOverlayEnabled()
                                    ),
                            () -> gamePercentileOverlayText()
                    );

            addPredictionWindowBox(
                    rightColumn,
                    top + 5 * rowStep,
                    columnWidth,
                    buttonHeight
            );

        } else {
            int y = top;

            recorderMovingButton =
                    addToggleButton(
                            leftColumn,
                            y,
                            columnWidth,
                            buttonHeight,
                            recorderMovingText(),
                            () -> ModelDataManager.setRecorderMoving(
                                    !ModelDataManager.isRecorderMoving()
                            ),
                            () -> recorderMovingText()
                    );
            y += rowStep;

            recorderAliveOverlayButton =
                    addToggleButton(
                            leftColumn,
                            y,
                            columnWidth,
                            buttonHeight,
                            recorderAliveOverlayText(),
                            () -> ModelDataManager
                                    .setRecorderAliveOverlayEnabled(
                                            !ModelDataManager
                                                    .isRecorderAliveOverlayEnabled()
                                    ),
                            () -> recorderAliveOverlayText()
                    );
            y += rowStep;

            gamePercentileOverlayButton =
                    addToggleButton(
                            leftColumn,
                            y,
                            columnWidth,
                            buttonHeight,
                            gamePercentileOverlayText(),
                            () -> ModelDataManager
                                    .setGamePercentileOverlayEnabled(
                                            !ModelDataManager
                                                    .isGamePercentileOverlayEnabled()
                                    ),
                            () -> gamePercentileOverlayText()
                    );
            y += rowStep;

            addPredictionWindowBox(
                    leftColumn,
                    y,
                    columnWidth,
                    buttonHeight
            );
            y += rowStep;


            overlay1Button =
                    addToggleButton(
                            leftColumn,
                            y,
                            columnWidth,
                            buttonHeight,
                            overlay1Text(),
                            () -> ModelDataManager.setOverlay1Enabled(
                                    !ModelDataManager.isOverlay1Enabled()
                            ),
                            () -> overlay1Text()
                    );
            y += rowStep;

            overlay2Button =
                    addToggleButton(
                            leftColumn,
                            y,
                            columnWidth,
                            buttonHeight,
                            overlay2Text(),
                            () -> ModelDataManager.setOverlay2Enabled(
                                    !ModelDataManager.isOverlay2Enabled()
                            ),
                            () -> overlay2Text()
                    );
            y += rowStep;

            disasterDeathsOverlayButton =
                    addToggleButton(
                            leftColumn,
                            y,
                            columnWidth,
                            buttonHeight,
                            disasterDeathsOverlayText(),
                            () -> ModelDataManager
                                    .setDisasterDeathsOverlayEnabled(
                                            !ModelDataManager
                                                    .isDisasterDeathsOverlayEnabled()
                                    ),
                            () -> disasterDeathsOverlayText()
                    );
            y += rowStep;

            predictionModelButton =
                    addToggleButton(
                            leftColumn,
                            y,
                            columnWidth,
                            buttonHeight,
                            predictionModelText(),
                            ModelDataManager::cyclePredictionModel,
                            () -> predictionModelText()
                    );
            y += rowStep;

            waveSpecificButton =
                    addToggleButton(
                            leftColumn,
                            y,
                            columnWidth,
                            buttonHeight,
                            waveSpecificText(),
                            ModelDataManager::cycleWaveSpecificMode,
                            () -> waveSpecificText()
                    );
            y += rowStep;

            percentageBasedButton =
                    addToggleButton(
                            leftColumn,
                            y,
                            columnWidth,
                            buttonHeight,
                            percentageBasedText(),
                            () -> ModelDataManager
                                    .setPercentageBasedAverages(
                                            !ModelDataManager
                                                    .isPercentageBasedAverages()
                                    ),
                            () -> percentageBasedText()
                    );
        }

        int doneY =
                Math.min(
                        this.height - 24,
                        twoColumns
                                ? top + 6 * rowStep + 4
                                : top + 11 * rowStep + 2
                );

        addRenderableWidget(
                Button.builder(
                        Component.literal("Done"),
                        button -> onClose()
                ).bounds(
                        panelLeft,
                        doneY,
                        panelWidth,
                        20
                ).build()
        );
    }

    private void addPredictionWindowBox(
            int x,
            int y,
            int width,
            int height
    ) {
        int labelWidth =
                Math.min(
                        92,
                        Math.max(
                                70,
                                width / 2
                        )
                );

        predictionWindowLabelX =
                x + 4;

        predictionWindowLabelY =
                y
                        + Math.max(
                        4,
                        (height - 9) / 2
                );

        int boxX =
                x + labelWidth;

        int boxWidth =
                Math.max(
                        38,
                        width
                                - labelWidth
                );

        predictionWindowSecondsBox =
                new EditBox(
                        this.font,
                        boxX,
                        y,
                        boxWidth,
                        height,
                        Component.literal(
                                "Prediction window seconds"
                        )
                );

        predictionWindowSecondsBox.setMaxLength(
                7
        );

        predictionWindowSecondsBox.setValue(
                formatPredictionWindowSeconds(
                        ModelDataManager
                                .getPredictionWindowSeconds()
                )
        );

        addRenderableWidget(
                predictionWindowSecondsBox
        );
    }

    private static String formatPredictionWindowSeconds(
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
                java.util.Locale.ROOT,
                "%.2f",
                value
        );
    }

    private void commitPredictionWindowSeconds() {
        if (predictionWindowSecondsBox == null) {
            return;
        }

        try {
            double value =
                    Double.parseDouble(
                            predictionWindowSecondsBox
                                    .getValue()
                                    .strip()
                    );

            ModelDataManager
                    .setPredictionWindowSeconds(
                            value
                    );

        } catch (Exception ignored) {
            /*
             * Leave the last valid config value unchanged.
             */
        }

        predictionWindowSecondsBox.setValue(
                formatPredictionWindowSeconds(
                        ModelDataManager
                                .getPredictionWindowSeconds()
                )
        );
    }

    private Button addToggleButton(
            int x,
            int y,
            int width,
            int height,
            Component initialText,
            Runnable action,
            java.util.function.Supplier<Component> refreshedText
    ) {
        Button button =
                Button.builder(
                        initialText,
                        clicked -> {
                            action.run();
                            clicked.setMessage(
                                    refreshedText.get()
                            );
                        }
                ).bounds(
                        x,
                        y,
                        width,
                        height
                ).build();

        addRenderableWidget(
                button
        );

        return button;
    }

    private Component recorderMovingText() {
        return Component.literal(
                "Rec moving: "
                        + (
                        ModelDataManager.isRecorderMoving()
                                ? "ON"
                                : "OFF"
                )
        );
    }

    private Component recorderAliveOverlayText() {
        return Component.literal(
                "Rec status: "
                        + (
                        ModelDataManager
                                .isRecorderAliveOverlayEnabled()
                                ? "ON"
                                : "OFF"
                )
        );
    }

    private Component gamePercentileOverlayText() {
        return Component.literal(
                "Game percentile: "
                        + (
                        ModelDataManager
                                .isGamePercentileOverlayEnabled()
                                ? "ON"
                                : "OFF"
                )
        );
    }


    private Component overlay1Text() {
        return Component.literal(
                "Live pred: "
                        + (
                        ModelDataManager
                                .isOverlay1Enabled()
                                ? "ON"
                                : "OFF"
                )
        );
    }

    private Component overlay2Text() {
        return Component.literal(
                "Final: "
                        + (
                        ModelDataManager
                                .isOverlay2Enabled()
                                ? "ON"
                                : "OFF"
                )
        );
    }

    private Component disasterDeathsOverlayText() {
        return Component.literal(
                "Deaths: "
                        + (
                        ModelDataManager
                                .isDisasterDeathsOverlayEnabled()
                                ? "ON"
                                : "OFF"
                )
        );
    }

    private Component predictionModelText() {
        String shortName =
                switch (
                        ModelDataManager.getPredictionModel()
                ) {
            case KNN ->
                    "KNN";
            case WAVE_AVERAGE_DEATHS ->
                    "Wave avg";
            case EXACT_COMBO_AVERAGE_DEATHS ->
                    "Exact hist";
            case DISASTER_PERCENT_CORRELATION ->
                    "Disaster %";
            case DISASTER_CHAT_DEATH_HISTORY ->
                    "Chat DEDS";
        };

        return Component.literal(
                "Model: "
                        + shortName
        );
    }

    private Component waveSpecificText() {
        return Component.literal(
                "Wave mode: "
                        + ModelDataManager
                        .getWaveSpecificMode()
                        .displayName()
        );
    }

    private Component percentageBasedText() {
        return Component.literal(
                "Percent avg: "
                        + (
                        ModelDataManager
                                .isPercentageBasedAverages()
                                ? "ON"
                                : "OFF"
                )
        );
    }

    @Override
    public void onClose() {
        commitPredictionWindowSeconds();
        ModelDataManager.saveConfig();

        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(
                    this.parent
            );
        }
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTicks
    ) {
        super.extractRenderState(
                graphics,
                mouseX,
                mouseY,
                partialTicks
        );

        graphics.centeredText(
                this.font,
                this.title,
                this.width / 2,
                18,
                0xFFFFFFFF
        );

        if (this.width >= 332) {
            graphics.centeredText(
                    this.font,
                    Component.literal(
                            "Counting / model                         Overlays"
                    ),
                    this.width / 2,
                    39,
                    0xFFAAAAAA
            );
        }

        if (predictionWindowLabelX >= 0
                && predictionWindowLabelY >= 0) {

            graphics.text(
                    this.font,
                    "Pred window (s):",
                    predictionWindowLabelX,
                    predictionWindowLabelY,
                    0xFFDDDDDD,
                    false
            );
        }

        if (this.height >= 190) {
            graphics.centeredText(
                    this.font,
                    Component.literal(
                            "Rec moving OFF excludes you while alive."
                    ),
                    this.width / 2,
                    this.height - 10,
                    0xFF888888
            );
        }
    }
}
