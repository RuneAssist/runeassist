package com.runeassist.flip.ui.flipsdialog;

import com.runeassist.flip.controller.ApiRequestHandler;
import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.controller.ItemController;
import com.runeassist.flip.manager.PriceGraphConfigManager;
import com.runeassist.flip.model.FlipV2;
import com.runeassist.flip.model.VisualizeFlipResponse;
import com.runeassist.flip.ui.graph.*;
import com.runeassist.flip.ui.graph.model.Data;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.function.Consumer;

@Slf4j
public class VisualizeFlipPanel extends JPanel {

    private final ItemController itemController;
    private final ApiRequestHandler apiRequestHandler;

    private final JLabel errorLabel = new JLabel();
    private final GraphPanel graphPanel;
    private final FlipStatsPanel statsPanel;
    private final CardLayout contentCardLayout = new CardLayout();

    private volatile FlipV2 currentFlip;

    public VisualizeFlipPanel(ItemController itemController,
                              PriceGraphConfigManager configManager,
                              RuneAssistConfig pluginConfig,
                              ApiRequestHandler apiRequestHandler) {
        this.itemController = itemController;
        this.apiRequestHandler = apiRequestHandler;

        setLayout(contentCardLayout);
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        graphPanel = new GraphPanel(configManager);
        statsPanel = new FlipStatsPanel(configManager, pluginConfig);
        statsPanel.setBackground(configManager.getConfig().backgroundColor);
        statsPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(DialogUi.centeredMessage("Open a flip from the side panel to visualize it here.", ColorScheme.DARK_GRAY_COLOR, true, 16f), Cards.LANDING_CARD.name());
        add(DialogUi.loadingCard("Loading price data...", ColorScheme.DARK_GRAY_COLOR), Cards.LOADING_CARD.name());
        add(DialogUi.splitGraphCard(graphPanel, statsPanel), Cards.GRAPH_CARD.name());
        add(DialogUi.errorCard(errorLabel, () -> {
            if (currentFlip != null) {
                showFlipVisualization(currentFlip);
            }
        }), Cards.ERROR_CARD.name());

        contentCardLayout.show(this, Cards.LANDING_CARD.name());
    }

    public void showFlipVisualization(FlipV2 flip) {
        if (flip == null) {
            return;
        }
        currentFlip = flip;
        contentCardLayout.show(this, Cards.LOADING_CARD.name());
        Consumer<String> onFailure = (String errorMessage) -> {
            SwingUtilities.invokeLater(() -> showErrorCard(errorMessage));
        };
        apiRequestHandler.asyncGetRuneAssistGraph(flip.getItemId(),
            (Data d) -> {
                if (d == null || !d.hasPriceSeries()) {
                    onFailure.accept("No price history for this item.");
                    return;
                }
                d.clearPredictionData();
                // FlipV2 aggregates supply buy/sell markers when per-lot ledger rows are absent.
                VisualizeFlipResponse overlay = VisualizeFlipResponse.fromLocalLots(d, flip, Collections.emptyList());
                SwingUtilities.invokeLater(() -> showGraphCard(new DataManager(overlay.getGraphData(), overlay), flip));
            },
            (Throwable e) -> {
                String detail = e != null && e.getMessage() != null && !e.getMessage().isEmpty()
                        ? e.getMessage()
                        : "check your connection and try again";
                log.warn("visualize flip graph failed for item {}", flip.getItemId(), e);
                onFailure.accept("Could not load price history from RuneAssist (" + detail + ").");
            });
    }

    private void showErrorCard(String errorMessage) {
        errorLabel.setText("<html><center>" + errorMessage + "</center></html>");
        contentCardLayout.show(this, Cards.ERROR_CARD.name());
    }

    private void showGraphCard(DataManager dm, FlipV2 f) {
        graphPanel.setData(dm);
        contentCardLayout.show(this, Cards.GRAPH_CARD.name());
        statsPanel.populate(f, itemController);
    }

    enum Cards {
        LANDING_CARD,
        GRAPH_CARD,
        LOADING_CARD,
        ERROR_CARD
    }
}
