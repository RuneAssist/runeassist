package com.runeassist.flip.ui.flipsdialog;

import com.runeassist.flip.controller.ApiRequestHandler;
import com.runeassist.flip.config.FlippingCopilotConfig;
import com.runeassist.flip.controller.ItemController;
import com.runeassist.flip.manager.PriceGraphConfigManager;
import com.runeassist.flip.model.*;
import com.runeassist.flip.rs.*;
import com.runeassist.flip.ui.graph.model.PriceLine;
import com.runeassist.flip.ui.RuneAssistColors;
import com.runeassist.flip.ui.RuneAssistTabbedPaneUI;
import com.google.inject.name.Named;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
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
    private final FlipManager flipsManager;
    private final ExecutorService executorService;
    private final CopilotLoginRS copilotLoginRS;
    private final FlippingCopilotConfig config;
    private final ApiRequestHandler apiRequestHandler;
    private final PriceGraphConfigManager priceGraphConfigManager;
    private final OsrsLoginManager osrsLoginManager;
    private final SuggestionManager suggestionManager;
    private final OsrsLoginRS osrsLoginRS;
    private final PortfolioStateRS portfolioStateRS;
    private final BankStateRS bankStateRS;
    private final GeHistoryStateRS geHistoryStateRS;
    private final ClientThread clientThread;
    private final TransactionManager transactionManager;
    private final LocalFlipLedger localFlipLedger;
    private final OfferManager offerManager;

    public PriceGraphPanel priceGraphPanel;
    private JTabbedPane tabbedPane;
    private JDialog dialog;
    private FlipsPanel flipsPanel;
    private MissedFlipsPanel missedFlipsPanel;
    private VisualizeFlipPanel visualizeFlipPanel;

    @Inject
    public FlipsDialogController(
            @Named("copilotExecutor") ScheduledExecutorService executorService,
            ItemController itemController,
            FlipManager flipsManager,
            CopilotLoginRS copilotLoginRS,
            FlippingCopilotConfig config,
            ApiRequestHandler apiRequestHandler,
            PriceGraphConfigManager priceGraphConfigManager,
            OsrsLoginManager osrsLoginManager,
            SuggestionManager suggestionManager,
            OsrsLoginRS osrsLoginRS,
            PortfolioStateRS portfolioStateRS,
            BankStateRS bankStateRS,
            GeHistoryStateRS geHistoryStateRS,
            ClientThread clientThread,
            TransactionManager transactionManager,
            LocalFlipLedger localFlipLedger,
            OfferManager offerManager) {
        this.itemController = itemController;
        this.flipsManager = flipsManager;
        this.executorService = executorService;
        this.copilotLoginRS = copilotLoginRS;
        this.config = config;
        this.apiRequestHandler = apiRequestHandler;
        this.priceGraphConfigManager = priceGraphConfigManager;
        this.osrsLoginManager = osrsLoginManager;
        this.suggestionManager = suggestionManager;
        this.osrsLoginRS = osrsLoginRS;
        this.portfolioStateRS = portfolioStateRS;
        this.bankStateRS = bankStateRS;
        this.geHistoryStateRS = geHistoryStateRS;
        this.clientThread = clientThread;
        this.transactionManager = transactionManager;
        this.localFlipLedger = localFlipLedger;
        this.offerManager = offerManager;
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
                    localFlipLedger
            );
            flipsPanel = new FlipsPanel(flipsManager, itemController, copilotLoginRS,
                    executorService, config, apiRequestHandler, (f) -> {
                showVisualizeFlip(f);
            });
            missedFlipsPanel = new MissedFlipsPanel(osrsLoginRS, flipsManager, itemController, copilotLoginRS,
                    executorService, geHistoryStateRS, localFlipLedger, offerManager);
            ItemAggregatePanel itemsPanel = new ItemAggregatePanel(flipsManager, itemController,
                    copilotLoginRS, executorService, config);
            AccountsAggregatePanel accountsPanel = new AccountsAggregatePanel(copilotLoginRS,
                    executorService, config, apiRequestHandler, flipsManager);
            ProfitPanel profitPanel = new ProfitPanel(flipsManager, executorService, copilotLoginRS, config);
            PortfolioPanel portfolioPanel = new PortfolioPanel(
                    itemController,
                    config,
                    apiRequestHandler,
                    suggestionManager,
                    copilotLoginRS,
                    osrsLoginRS,
                    portfolioStateRS,
                    bankStateRS,
                    clientThread,
                    itemId -> showPriceGraphTab(itemId, false, null)
            );
            TransactionsPanel transactionsPanel = new TransactionsPanel(copilotLoginRS, itemController,
                    executorService, apiRequestHandler, osrsLoginManager, config, flipsManager,
                    transactionManager, localFlipLedger);
            priceGraphPanel = new PriceGraphPanel(
                    itemController,
                    priceGraphConfigManager,
                    config,
                    apiRequestHandler,
                    osrsLoginManager,
                    suggestionManager
            );
            tabbedPane.addTab("Portfolio", portfolioPanel);
            tabbedPane.addTab("Flips", flipsPanel);
            tabbedPane.addTab("Items", itemsPanel);
            tabbedPane.addTab("Accounts", accountsPanel);
            tabbedPane.addTab("Profit graph", profitPanel);
            tabbedPane.addTab("Transactions", transactionsPanel);
            tabbedPane.addTab("Price graph", priceGraphPanel);
            tabbedPane.addTab("Visualize flip", visualizeFlipPanel);
            tabbedPane.addTab("Missed flips", missedFlipsPanel);


            JDialog dialog = new JDialog(windowAncestor);
            dialog.setTitle("RuneAssist Flipping");
            dialog.setResizable(true);
            dialog.setMinimumSize(new Dimension(800, 600));


            tabbedPane.addChangeListener(e -> {
                int selectedIndex = tabbedPane.getSelectedIndex();
                switch (selectedIndex) {
                    case 0:
                        portfolioPanel.onTabShown();
                        break;
                    case 1:
                        flipsPanel.onTabShown();
                        break;
                    case 2:
                        itemsPanel.onTabShown();
                        break;
                    case 3:
                        accountsPanel.onTabShown();
                        break;
                    case 4:
                        profitPanel.refreshGraph(true);
                        break;
                    case 5:
                        transactionsPanel.loadTransactionsIfNeeded();
                        break;
                    case 6:
                        priceGraphPanel.onTabShown();
                        break;
                    case 8:
                        missedFlipsPanel.onTabShown();
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
        tabbedPane.setSelectedIndex(6);
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
        if (config.priceGraphWebsite().equals(FlippingCopilotConfig.PriceGraphWebsite.FLIPPING_COPILOT)) {
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
        return suggestion != null && !suggestion.isWaitSuggestion() && suggestion.isDumpAlert;
    }


    public void showPortfolioTab() {
        tabbedPane.setSelectedIndex(0);
        dialog.setVisible(true);
    }

    public void showVisualizeFlip(FlipV2 flip) {
        if (flip == null) {
            return;
        }
        visualizeFlipPanel.showFlipVisualization(flip);
        tabbedPane.setSelectedIndex(7);
        dialog.setVisible(true);
    }
}
