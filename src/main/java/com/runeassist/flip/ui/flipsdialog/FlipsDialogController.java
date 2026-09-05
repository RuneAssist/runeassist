package com.runeassist.flip.ui.flipsdialog;

import com.runeassist.flip.controller.ApiRequestHandler;
import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.controller.FlipHistorySyncService;
import com.runeassist.flip.controller.ItemController;
import com.runeassist.flip.manager.PriceGraphConfigManager;
import com.runeassist.flip.model.*;
import com.runeassist.flip.rs.*;
import com.runeassist.flip.ui.graph.model.PriceLine;
import com.runeassist.flip.ui.RuneAssistColors;
import com.runeassist.flip.ui.RuneAssistTabbedPaneUI;
import com.google.inject.name.Named;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.LinkBrowser;

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
    private final ApiRequestHandler apiRequestHandler;
    private final PriceGraphConfigManager priceGraphConfigManager;
    private final OsrsLoginManager osrsLoginManager;
    private final SuggestionManager suggestionManager;
    private final OsrsLoginRS osrsLoginRS;
    private final PortfolioStateRS portfolioStateRS;
    private final BankStateRS bankStateRS;
    private final com.runeassist.flip.HeldCostTracker heldCostTracker;
    private final FlipHistorySyncService flipHistorySyncService;

    public PriceGraphPanel priceGraphPanel;
    private JTabbedPane tabbedPane;
    private JDialog dialog;
    private VisualizeFlipPanel visualizeFlipPanel;

    // Tab indices: Portfolio, Price graph, Visualize flip, Transactions, Analytics (web)
    private static final int TAB_PORTFOLIO = 0;
    private static final int TAB_PRICE_GRAPH = 1;
    private static final int TAB_VISUALIZE_FLIP = 2;
    private static final int TAB_TRANSACTIONS = 3;
    private static final int TAB_WEB_ANALYTICS = 4;

    @Inject
    public FlipsDialogController(
            @Named("runeAssistExecutor") ScheduledExecutorService executorService,
            ItemController itemController,
            RuneAssistConfig config,
            ApiRequestHandler apiRequestHandler,
            PriceGraphConfigManager priceGraphConfigManager,
            OsrsLoginManager osrsLoginManager,
            SuggestionManager suggestionManager,
            OsrsLoginRS osrsLoginRS,
            PortfolioStateRS portfolioStateRS,
            BankStateRS bankStateRS,
            com.runeassist.flip.HeldCostTracker heldCostTracker,
            FlipHistorySyncService flipHistorySyncService) {
        this.itemController = itemController;
        this.executorService = executorService;
        this.config = config;
        this.apiRequestHandler = apiRequestHandler;
        this.priceGraphConfigManager = priceGraphConfigManager;
        this.osrsLoginManager = osrsLoginManager;
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

            visualizeFlipPanel = new VisualizeFlipPanel(
                    itemController,
                    priceGraphConfigManager,
                    config,
                    apiRequestHandler,
                    flipHistorySyncService,
                    osrsLoginManager
            );
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
                    itemId -> showPriceGraphTab(itemId, false, null)
            );
            priceGraphPanel = new PriceGraphPanel(
                    itemController,
                    priceGraphConfigManager,
                    config,
                    apiRequestHandler,
                    osrsLoginManager,
                    suggestionManager
            );
            TransactionsPanel transactionsPanel = new TransactionsPanel(
                    flipHistorySyncService,
                    itemController,
                    osrsLoginManager,
                    executorService
            );
            tabbedPane.addTab("Portfolio", portfolioPanel);
            tabbedPane.addTab("Price graph", priceGraphPanel);
            tabbedPane.addTab("Visualize flip", visualizeFlipPanel);
            tabbedPane.addTab("Transactions", transactionsPanel);
            tabbedPane.addTab("Analytics", new WebAnalyticsPanel(this::openWebAnalytics));

            JDialog dialog = new JDialog(windowAncestor);
            dialog.setTitle("RuneAssist Flipping");
            dialog.setResizable(true);
            dialog.setMinimumSize(new Dimension(800, 600));

            tabbedPane.addChangeListener(e -> {
                int selectedIndex = tabbedPane.getSelectedIndex();
                switch (selectedIndex) {
                    case TAB_PORTFOLIO:
                        portfolioPanel.onTabShown();
                        break;
                    case TAB_PRICE_GRAPH:
                        priceGraphPanel.onTabShown();
                        break;
                    case TAB_TRANSACTIONS:
                        transactionsPanel.onTabShown();
                        break;
                    default:
                        break;
                }
            });
            dialog.setContentPane(tabbedPane);

            GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
            Rectangle bounds = env.getMaximumWindowBounds(); // Excludes taskbar
            dialog.setSize(bounds.width, bounds.height);
            dialog.setLocation(bounds.x, bounds.y);

            this.dialog = dialog;
            dialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            dialog.setModalityType(Dialog.ModalityType.MODELESS);
            dialog.setVisible(false);
        });
    }

    public void showPriceGraphTab(Integer openOnPriceGraphItemId, boolean suggestionPriceGraph, PriceLine priceLine) {
        tabbedPane.setSelectedIndex(TAB_PRICE_GRAPH);
        if(openOnPriceGraphItemId != null) {
            priceGraphPanel.isShowingSuggestionPriceData = false;
            priceGraphPanel.searchBox.setItem(new ItemIdName(openOnPriceGraphItemId, itemController.getItemName(openOnPriceGraphItemId)));
            priceGraphPanel.offerPriceLine = priceLine;
        } else if (suggestionPriceGraph)  {
            priceGraphPanel.showSuggestionPriceGraph();
        } else {
            priceGraphPanel.showLandingCard();
        }
        dialog.setVisible(true);
    }

    public void openSuggestionPriceGraph() {
        Suggestion suggestion = suggestionManager.getSuggestion();
        if (config.priceGraphWebsite().equals(RuneAssistConfig.PriceGraphWebsite.RUNEASSIST)) {
            if (isSuggestionWithoutGraphData(suggestion)) {
                showPriceGraphTab(suggestion.getItemId(), false, null);
            } else if (suggestion != null && !suggestion.isWaitSuggestion()) {
                showPriceGraphTab(null, true, null);
            } else {
                showPriceGraphTab(null, false, null);
            }
            return;
        }

        if (suggestion == null || suggestion.isWaitSuggestion()) {
            return;
        }
        String url = config.priceGraphWebsite().getUrl(suggestion.getName(), suggestion.getItemId());
        LinkBrowser.browse(url);
    }

    private boolean isSuggestionWithoutGraphData(Suggestion suggestion) {
        if (suggestion == null || suggestion.isWaitSuggestion()) {
            return false;
        }
        // Fall back to a direct item fetch when the bundled/prefetched suggestion graph
        // is not ready yet (low-data mode, still in flight, or soft-failed attach).
        return priceGraphPanel == null || priceGraphPanel.suggestionPriceData == null
                || priceGraphPanel.suggestionPriceData.itemId != suggestion.getItemId();
    }


    public void showPortfolioTab() {
        tabbedPane.setSelectedIndex(TAB_PORTFOLIO);
        dialog.setVisible(true);
    }


    public void showWebAnalyticsTab() {
        tabbedPane.setSelectedIndex(TAB_WEB_ANALYTICS);
        dialog.setVisible(true);
    }

    /** Open website dashboard analytics (null section = full dashboard). */
    public void openWebAnalytics(String section) {
        LinkBrowser.browse(WebAnalyticsLinks.url(flipHistorySyncService.websiteUrl(), section));
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
