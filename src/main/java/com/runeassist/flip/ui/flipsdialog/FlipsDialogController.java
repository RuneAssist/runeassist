package com.runeassist.flip.ui.flipsdialog;

import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.controller.FlipHistorySyncService;
import com.runeassist.flip.controller.ItemController;
import com.runeassist.flip.model.*;
import com.runeassist.flip.rs.*;
import com.runeassist.flip.ui.RuneAssistColors;
import com.runeassist.flip.ui.RuneAssistTabbedPaneUI;
import com.google.inject.name.Named;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@Singleton
public class FlipsDialogController {

    private final ItemController itemController;
    private final ExecutorService executorService;
    private final RuneAssistConfig config;
    private final OsrsLoginRS osrsLoginRS;
    private final PortfolioStateRS portfolioStateRS;
    private final BankStateRS bankStateRS;
    private final SuggestionManager suggestionManager;
    private final com.runeassist.flip.HeldCostTracker heldCostTracker;
    private final FlipHistorySyncService flipHistorySyncService;

    public PriceGraphPanel priceGraphPanel;
    private JTabbedPane tabbedPane;
    private JDialog dialog;
    private VisualizeFlipPanel visualizeFlipPanel;

    private static final int TAB_PORTFOLIO = 0;
    private static final int TAB_PRICE_GRAPH = 1;
    private static final int TAB_VISUALIZE_FLIP = 2;

    @Inject
    public FlipsDialogController(
            @Named("runeAssistExecutor") ScheduledExecutorService executorService,
            ItemController itemController,
            RuneAssistConfig config,
            SuggestionManager suggestionManager,
            OsrsLoginRS osrsLoginRS,
            PortfolioStateRS portfolioStateRS,
            BankStateRS bankStateRS,
            com.runeassist.flip.HeldCostTracker heldCostTracker,
            FlipHistorySyncService flipHistorySyncService) {
        this.itemController = itemController;
        this.executorService = executorService;
        this.config = config;
        this.suggestionManager = suggestionManager;
        this.osrsLoginRS = osrsLoginRS;
        this.portfolioStateRS = portfolioStateRS;
        this.bankStateRS = bankStateRS;
        this.heldCostTracker = heldCostTracker;
        this.flipHistorySyncService = flipHistorySyncService;
    }

    public void initDialog(Window windowAncestor) {
        SwingUtilities.invokeLater(() -> {
            tabbedPane = new JTabbedPane();
            tabbedPane.setBackground(RuneAssistColors.SHELL);
            tabbedPane.setForeground(RuneAssistColors.ACCENT);
            tabbedPane.setOpaque(true);
            tabbedPane.setUI(new RuneAssistTabbedPaneUI());
            tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

            visualizeFlipPanel = new VisualizeFlipPanel(itemController, config);
            PortfolioPanel portfolioPanel = new PortfolioPanel(
                    itemController,
                    config,
                    heldCostTracker,
                    flipHistorySyncService,
                    executorService,
                    suggestionManager,
                    osrsLoginRS,
                    portfolioStateRS,
                    bankStateRS,
                    this::openItemPriceGraph
            );
            priceGraphPanel = new PriceGraphPanel(itemController, config, suggestionManager);
            tabbedPane.addTab("Portfolio", portfolioPanel);
            tabbedPane.addTab("Price graph", priceGraphPanel);
            tabbedPane.addTab("Visualize flip", visualizeFlipPanel);

            JDialog dialog = new JDialog(windowAncestor);
            dialog.setTitle("RuneAssist Flipping");
            dialog.setResizable(true);
            dialog.setMinimumSize(new Dimension(800, 600));

            tabbedPane.addChangeListener(e -> {
                if (tabbedPane.getSelectedIndex() == TAB_PORTFOLIO) {
                    portfolioPanel.onTabShown();
                } else if (tabbedPane.getSelectedIndex() == TAB_PRICE_GRAPH) {
                    priceGraphPanel.onTabShown();
                }
            });
            dialog.setContentPane(tabbedPane);

            GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
            Rectangle bounds = env.getMaximumWindowBounds();
            dialog.setSize(bounds.width, bounds.height);
            dialog.setLocation(bounds.x, bounds.y);

            this.dialog = dialog;
            dialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            dialog.setModalityType(Dialog.ModalityType.MODELESS);
            dialog.setVisible(false);
        });
    }

    public void openItemPriceGraph(int itemId) {
        String name = itemController.getItemName(itemId);
        PriceGraphWebsite.open(config, name, itemId);
        if (priceGraphPanel != null) {
            priceGraphPanel.showItem(itemId);
        }
    }

    public void showPriceGraphTab(Integer itemId) {
        if (itemId != null && itemId > 0) {
            openItemPriceGraph(itemId);
        }
        if (tabbedPane != null) {
            tabbedPane.setSelectedIndex(TAB_PRICE_GRAPH);
        }
        if (priceGraphPanel != null) {
            if (itemId != null && itemId > 0) {
                priceGraphPanel.showItem(itemId);
            } else {
                priceGraphPanel.showLandingCard();
            }
        }
        if (dialog != null) {
            dialog.setVisible(true);
        }
    }

    public void openSuggestionPriceGraph() {
        Suggestion suggestion = suggestionManager.getSuggestion();
        if (suggestion == null || suggestion.isWaitSuggestion()) {
            showPriceGraphTab(null);
            return;
        }
        PriceGraphWebsite.open(config, suggestion.getName(), suggestion.getItemId());
        if (priceGraphPanel != null) {
            priceGraphPanel.showItem(suggestion.getItemId());
        }
    }

    public void showPortfolioTab() {
        tabbedPane.setSelectedIndex(TAB_PORTFOLIO);
        dialog.setVisible(true);
    }

    public void showVisualizeFlip(FlipV2 flip) {
        if (flip == null) {
            return;
        }
        visualizeFlipPanel.showFlipVisualization(flip);
        tabbedPane.setSelectedIndex(TAB_VISUALIZE_FLIP);
        dialog.setVisible(true);
    }
}
