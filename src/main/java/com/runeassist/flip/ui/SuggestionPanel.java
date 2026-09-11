package com.runeassist.flip.ui;

import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.controller.*;
import com.runeassist.flip.HubPluginConflict;
import com.runeassist.flip.model.*;
import com.runeassist.flip.ui.flipsdialog.FlipsDialogController;
import com.runeassist.flip.util.ProfitCalculator;
import joptsimple.internal.Strings;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static com.runeassist.flip.ui.UIUtilities.*;
import static com.runeassist.flip.util.Constants.MIN_GP_NEEDED_TO_FLIP;


@Singleton
@Slf4j
public class SuggestionPanel extends JPanel {
    private static final int DEFAULT_PANEL_HEIGHT = 148;
    private static final int STATUS_PANEL_HEIGHT = 96;
    private static final int FLAGS_ROW_HEIGHT = 18;
    private static final String CARD_STRUCTURED = "structured";
    private static final String CARD_MESSAGE = "message";
    private static final String CARD_SPINNER = "spinner";

    private final RuneAssistConfig config;
    private final SuggestionManager suggestionManager;
    private final SuggestionPreferencesManager suggestionPreferencesManager;
    private final AccountStatusManager accountStatusManager;
    public final PauseButton pauseButton;
    private final JButton blockButton = new JButton();
    private final OsrsLoginManager osrsLoginManager;
    private final Client client;
    private final PausedManager pausedManager;
    private final GrandExchangeUncollectedManager uncollectedManager;
    private final ClientThread clientThread;
    private final HighlightController highlightController;
    private final ItemManager itemManager;
    private final GrandExchange grandExchange;
    private final FlipsDialogController flipsDialogController;
    private final ProfitCalculator profitCalculator;
    private final SuggestionController suggestionController;


    private final JLabel suggestionText = new JLabel();
    private final JLabel suggestionIcon = new JLabel();
    private final JPanel suggestionTextContainer = new JPanel();
    private final JLabel additionalInfoText = new JLabel();
    public final Spinner spinner = new Spinner();
    private JButton skipButton;
    private final JPanel buttonContainer = new JPanel();
    private final JPanel suggestedActionPanel;
    private final JPanel cardHeader;
    private static final String COLLECT_MESSAGE = "Collect items";
    // Read by the client thread; a hidden Swing child can still report isVisible().
    private final AtomicReference<String> displayedMessage = new AtomicReference<>();
    private final CardLayout bodyLayout = new CardLayout();
    private final JPanel bodyCards = new JPanel(bodyLayout);
    private final JLabel headlineLabel = new JLabel();
    private final JLabel qtyPriceLabel = new JLabel();
    private final JPanel flagsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));

    private String serverMessage = "";
    private final WaitRefreshState waitRefreshState = new WaitRefreshState();
    private volatile boolean loadingStatusVisible;
    private final Timer loadingStatusTimer = new Timer(8000, e -> {
        if (loadingStatusVisible) {
            suggestionText.setText("<html><center>Server slow—still waiting…</center></html>");
        }
    });

    public void setServerMessage(String serverMessage) {
        this.serverMessage = serverMessage == null ? "" : serverMessage;
    }


    @Inject
    public SuggestionPanel(RuneAssistConfig config,
                           SuggestionManager suggestionManager,
                           SuggestionPreferencesManager suggestionPreferencesManager,
                           AccountStatusManager accountStatusManager,
                           PauseButton pauseButton,
                           OsrsLoginManager osrsLoginManager,
                           Client client, PausedManager pausedManager,
                           GrandExchangeUncollectedManager uncollectedManager,
                           ClientThread clientThread,
                           HighlightController highlightController,
                           ItemManager itemManager,
                           GrandExchange grandExchange, FlipsDialogController flipsDialogController, ProfitCalculator profitCalculator, SuggestionController suggestionController) {
        this.config = config;
        this.suggestionManager = suggestionManager;
        this.suggestionPreferencesManager = suggestionPreferencesManager;
        this.accountStatusManager = accountStatusManager;
        this.pauseButton = pauseButton;
        this.osrsLoginManager = osrsLoginManager;
        this.client = client;
        this.pausedManager = pausedManager;
        this.uncollectedManager = uncollectedManager;
        this.clientThread = clientThread;
        this.highlightController = highlightController;
        this.itemManager = itemManager;
        this.grandExchange = grandExchange;
        this.flipsDialogController = flipsDialogController;
        this.profitCalculator = profitCalculator;
        this.suggestionController = suggestionController;

        Dimension size = new Dimension(MainPanel.CONTENT_WIDTH, DEFAULT_PANEL_HEIGHT);
        setPreferredSize(size);
        setMinimumSize(new Dimension(MainPanel.CONTENT_WIDTH, STATUS_PANEL_HEIGHT));
        loadingStatusTimer.setRepeats(false);
        setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        setLayout(new BorderLayout());
        setBackground(RuneAssistColors.SHELL);

        suggestedActionPanel = darkPanel(new BorderLayout(), RuneAssistColors.CARD);
        suggestedActionPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 16, 8));
        cardHeader = buildHeader();
        suggestedActionPanel.add(cardHeader, BorderLayout.NORTH);

        bodyCards.setOpaque(true);
        bodyCards.setBackground(RuneAssistColors.CARD);
        bodyCards.add(buildStructuredCard(), CARD_STRUCTURED);
        bodyCards.add(buildMessageCard(), CARD_MESSAGE);
        bodyCards.add(spinner, CARD_SPINNER);
        suggestedActionPanel.add(bodyCards, BorderLayout.CENTER);

        setupButtonContainer();
        suggestedActionPanel.add(buttonContainer, BorderLayout.SOUTH);

        add(suggestedActionPanel, BorderLayout.CENTER);
        showMessageCard();
    }

    private JPanel buildHeader() {
        JPanel header = darkPanel(new BorderLayout(), RuneAssistColors.CARD);
        header.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 22));
        header.setPreferredSize(new Dimension(210, 18));
        headlineLabel.setForeground(Color.WHITE);
        SuggestionCardText.styleText(headlineLabel, true);
        headlineLabel.setHorizontalAlignment(SwingConstants.CENTER);
        constrainWidth(headlineLabel);
        suggestionIcon.setVisible(false);
        suggestionIcon.setOpaque(true);
        suggestionIcon.setBackground(RuneAssistColors.CARD);
        suggestionIcon.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        JPanel titleRow = darkPanel(new BorderLayout(), RuneAssistColors.CARD);
        titleRow.add(headlineLabel, BorderLayout.CENTER);
        header.add(titleRow, BorderLayout.CENTER);
        return header;
    }

    private JPanel buildStructuredCard() {
        qtyPriceLabel.setForeground(RuneAssistColors.TEXT);
        SuggestionCardText.styleText(qtyPriceLabel, false);
        qtyPriceLabel.setAlignmentX(LEFT_ALIGNMENT);
        constrainWidth(qtyPriceLabel);

        additionalInfoText.setHorizontalAlignment(SwingConstants.LEFT);
        additionalInfoText.setForeground(RuneAssistColors.TEXT);
        SuggestionCardText.styleText(additionalInfoText, false);
        additionalInfoText.setText("");
        additionalInfoText.setAlignmentX(LEFT_ALIGNMENT);
        additionalInfoText.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        constrainWidth(additionalInfoText);

        flagsRow.setOpaque(true);
        flagsRow.setBackground(RuneAssistColors.CARD);
        flagsRow.setAlignmentX(LEFT_ALIGNMENT);
        flagsRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));
        // Pin height so BoxLayout doesn't collapse chips to border-only slivers.
        Dimension flagsSize = new Dimension(MainPanel.CONTENT_WIDTH - 20, FLAGS_ROW_HEIGHT);
        flagsRow.setMinimumSize(flagsSize);
        flagsRow.setPreferredSize(flagsSize);
        constrainWidth(flagsRow);
        return SuggestionCardText.tradeBody(suggestionIcon, qtyPriceLabel, additionalInfoText);
    }

    private static void constrainWidth(JComponent component) {
        int inner = MainPanel.CONTENT_WIDTH - 20;
        component.setMaximumSize(new Dimension(inner, Integer.MAX_VALUE));
    }

    private JPanel buildMessageCard() {
        suggestionTextContainer.setLayout(new BorderLayout());
        suggestionTextContainer.add(suggestionText, BorderLayout.CENTER);
        suggestionTextContainer.setOpaque(true);
        suggestionTextContainer.setBackground(RuneAssistColors.CARD);
        suggestionText.setHorizontalAlignment(SwingConstants.CENTER);
        suggestionText.setVerticalAlignment(SwingConstants.CENTER);
        suggestionText.setForeground(RuneAssistColors.TEXT);
        SuggestionCardText.styleText(suggestionText, false);
        suggestionText.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        return suggestionTextContainer;
    }

    private void setHeadline(String text) {
        cardHeader.setVisible(true);
        headlineLabel.setVisible(true);
        if (text == null || text.isBlank()) {
            headlineLabel.setText(" ");
            return;
        }
        headlineLabel.setText("<html>" + htmlEscape(text) + "</html>");
        headlineLabel.revalidate();
    }

    private void setHeadline(String action, String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            setHeadline(action);
            return;
        }
        setHeadline(action + "  " + itemName);
    }

    private void showStructuredCard() {
        stopLoadingStatus();
        displayedMessage.set(null);
        bodyLayout.show(bodyCards, CARD_STRUCTURED);
        int contentHeight = bodyCards.getComponent(0).getPreferredSize().height;
        setPreferredSize(new Dimension(MainPanel.CONTENT_WIDTH,
                Math.max(DEFAULT_PANEL_HEIGHT, contentHeight + 60)));
        revalidate();
    }

    private void showMessageCard() {
        bodyLayout.show(bodyCards, CARD_MESSAGE);
        setPreferredSize(new Dimension(MainPanel.CONTENT_WIDTH, STATUS_PANEL_HEIGHT));
        revalidate();
    }

    private void setupButtonContainer() {
        buttonContainer.setLayout(new FlowLayout(FlowLayout.CENTER, 14, 0));
        buttonContainer.setBackground(RuneAssistColors.CARD);
        buttonContainer.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

        pauseButton.setToolTipText("Pause suggestions");
        pauseButton.setPreferredSize(new Dimension(22, 22));
        pauseButton.setMargin(new Insets(0, 0, 0, 0));

        skipButton = new JButton(new ImageIcon(ImageUtil.loadImageResource(getClass(), "/skip.png")));
        skipButton.setPreferredSize(new Dimension(22, 22));
        skipButton.setBorderPainted(false);
        skipButton.setContentAreaFilled(false);
        skipButton.setFocusPainted(false);
        skipButton.setToolTipText("Skip suggestion");
        skipButton.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        skipButton.setMargin(new Insets(1, 6, 1, 6));
        skipButton.addActionListener(e -> suggestionController.skipSuggestion());

        BufferedImage graphIcon = ImageUtil.loadImageResource(getClass(), "/graph.png");
        buttonContainer.add(buildButton(graphIcon, "Price graph", flipsDialogController::openSuggestionPriceGraph));
        BufferedImage portfolioIcon = ImageUtil.loadImageResource(getClass(), "/pie-chart.png");
        buttonContainer.add(buildButton(portfolioIcon, "Open portfolio", flipsDialogController::showPortfolioTab));
        buttonContainer.add(pauseButton);

        BufferedImage blockImg = ImageUtil.loadImageResource(getClass(), "/block.png");
        ImageIcon blockIcon = new ImageIcon(blockImg);
        ImageIcon blockIconHover = new ImageIcon(ImageUtil.luminanceScale(blockImg, BUTTON_HOVER_LUMINANCE));
        blockButton.setIcon(blockIcon);
        blockButton.setToolTipText("Block this item");
        blockButton.setFocusPainted(false);
        blockButton.setBorderPainted(false);
        blockButton.setContentAreaFilled(false);
        blockButton.setPreferredSize(new Dimension(22, 22));
        blockButton.addActionListener(e -> confirmAndBlock());
        addHoverIcons(blockButton, () -> blockIcon, () -> blockIconHover);
        buttonContainer.add(blockButton);
        buttonContainer.add(skipButton);
    }

    private void confirmAndBlock() {
        Suggestion s = suggestionManager.getSuggestion();
        if (s == null) {
            log.debug("No current suggestion to block.");
            return;
        }

        String itemName = s.getName() != null ? s.getName() : "this item";
        int choice = JOptionPane.showConfirmDialog(
                blockButton,
                "Do you want to block " + itemName + "?",
                "Confirm Block",
                JOptionPane.YES_NO_OPTION
        );

        if (choice == JOptionPane.YES_OPTION) {
            suggestionPreferencesManager.blockItem(s.getItemId());
            log.debug("Blocked item with ID {} ({})", s.getItemId(), itemName);
            suggestionManager.setSuggestionNeeded(true);
        } else {
            log.debug("User canceled blocking for {}", itemName);
        }
    }


    private void setItemIcon(int itemId) {
        AsyncBufferedImage image = itemManager.getImage(itemId);
        if (image != null) {
            image.addTo(suggestionIcon);
            suggestionIcon.setVisible(true);
        }
    }

    private void setAdditionalInfoText(String text, String tooltip) {
        additionalInfoText.setVisible(true);
        if (text == null || text.isEmpty()) {
            additionalInfoText.setText("");
        } else {
            additionalInfoText.setText("<html><body width='196'><center>" + text + "</center></body></html>");
        }
        additionalInfoText.setToolTipText(tooltip);
        headlineLabel.setToolTipText(tooltip);
        qtyPriceLabel.setToolTipText(tooltip);
    }

    private String qtyAndPriceLine(Suggestion suggestion, NumberFormat formatter) {
        return formatter.format(suggestion.getQuantity()) + "  ×  "
                + formatter.format(suggestion.getPrice()) + " gp";
    }

    public void updateSuggestion(Suggestion suggestion) {
        waitRefreshState.clear();
        NumberFormat formatter = NumberFormat.getNumberInstance();
        suggestionIcon.setVisible(false);
        additionalInfoText.setText("");
        clearSuggestionTooltips();
        SuggestionType suggestionType = suggestion.getType();
        if (suggestionType == null) {
            suggestionManager.setSuggestionNeeded(true);
            showFetchingWait();
            return;
        }
        switch (suggestionType) {
            case WAIT:
                paintWait(suggestion);
                return;
            case ABORT:
                setHeadline("Abort", suggestion.getName());
                qtyPriceLabel.setText("Abort this offer");
                setItemIcon(suggestion.getItemId());
                break;
            case BUY:
                boolean trial = suggestion.getFlags() != null && suggestion.getFlags().contains("probe");
                setHeadline(suggestion.isHold() ? "Hold" : trial ? "Trial buy" : "Buy", suggestion.getName());
                qtyPriceLabel.setText(qtyAndPriceLine(suggestion, formatter));
                setItemIcon(suggestion.getItemId());
                break;
            case SELL:
            case MODIFY_BUY:
            case MODIFY_SELL:
                if (suggestion.isModifySuggestion()) {
                    setHeadline("Modify", suggestion.getName());
                    qtyPriceLabel.setText("to  " + formatter.format(suggestion.getPrice()) + " gp"
                            + "  ·  " + formatter.format(suggestion.getQuantity()));
                } else {
                    String action = shouldSellFromBank(suggestion) ? "Bank sell"
                            : suggestion.isSellSuggestion() ? "Sell" : "Buy";
                    setHeadline(action, suggestion.getName());
                    qtyPriceLabel.setText(qtyAndPriceLine(suggestion, formatter));
                }
                setItemIcon(suggestion.getItemId());
                break;
            case DECANT:
                setHeadline("Decant", suggestion.getName());
                qtyPriceLabel.setText("Decant now");
                setItemIcon(suggestion.getItemId());
                break;
            default:
                suggestionManager.setSuggestionNeeded(true);
                showFetchingWait();
                return;
        }
        String action;
        switch (suggestionType) {
            case BUY: action = suggestion.isHold() ? "Buy and hold"
                    : suggestion.getFlags() != null && suggestion.getFlags().contains("probe") ? "Trial buy" : "Buy"; break;
            case SELL: action = shouldSellFromBank(suggestion) ? "Sell from bank" : "Sell"; break;
            case MODIFY_BUY: action = "Update buy"; break;
            case MODIFY_SELL: action = "Update sell"; break;
            case ABORT: action = "Abort offer"; break;
            default: action = "Decant";
        }
        headlineLabel.setVisible(false);
        cardHeader.setVisible(false);
        qtyPriceLabel.setText(SuggestionCardText.instruction(action, suggestion.getName(),
                suggestion.getQuantity(), suggestion.getPrice(), suggestion.isBuySuggestion() || suggestion.isSellSuggestion()));
        populateFlags(null);

        if (!suggestion.isWaitSuggestion()) {
            setButtonsVisible(true);
        }
        if (suggestion.isBuySuggestion()) {
            String profit = formatEstimatedProfit(suggestion.getExpectedProfit(), suggestion.getExpectedDuration(), false);
            setAdditionalInfoText(profit, formatSuggestionTooltip(suggestion, suggestion.getExpectedProfit()));
        } else if (suggestion.isSellSuggestion()) {
            Long profit = profitCalculator.calculateSuggestionProfit(suggestion);
            if (profit == null && suggestion.getExpectedProfit() != null) {
                profit = Math.round(suggestion.getExpectedProfit());
            }
            String profitText = profit == null ? ""
                    : formatEstimatedProfit((double) profit, suggestion.getExpectedDuration(), true);
            setAdditionalInfoText(
                    profitText,
                    formatSuggestionTooltip(suggestion, profit == null ? null : (double) profit)
            );
        } else {
            setAdditionalInfoText("", formatSuggestionTooltip(suggestion, null));
        }

        showStructuredCard();
    }

    private void paintWait(Suggestion suggestion) {
        setHeadline("Wait");
        suggestionIcon.setVisible(false);
        populateFlags(null);
        String message = suggestion.getMessage();
        if (Strings.isNullOrEmpty(message)) {
            message = "Wait";
        }
        qtyPriceLabel.setText("");
        String why = suggestion.getWhy();
        if (Strings.isNullOrEmpty(why)) {
            why = formatWaitSlotStatus();
        }
        setAdditionalInfoText(SuggestionCardText.waitStatus(message),
                "<html><body width='260'>" + SuggestionCardText.details(message, why) + "</body></html>");
        setButtonsVisible(false);
        showStructuredCard();
        waitRefreshState.showing(suggestion, osrsLoginManager.getAccountHash());
        cardHeader.setVisible(false);
        setPreferredSize(new Dimension(MainPanel.CONTENT_WIDTH, STATUS_PANEL_HEIGHT));
        revalidate();
    }

    private boolean shouldSellFromBank(Suggestion suggestion) {
        AccountStatus accountStatus = accountStatusManager.getAccountStatus();
        return accountStatus != null && accountStatus.shouldSellFromBank(suggestion);
    }

    private void showStaticSuggestion(String headline, String message) {
        setHeadline(headline);
        populateFlags(null);
        setMessage(message);
    }

    public void suggestHubConflict() {
        showStaticSuggestion("Wait",
                "<FONT COLOR=gray>" + HubPluginConflict.WAIT_MESSAGE + "</FONT>");
    }

    public void suggestCollect() {
        showStaticSuggestion("Collect", COLLECT_MESSAGE);
    }

    public void suggestAddGp() {
        NumberFormat formatter = NumberFormat.getNumberInstance();
        showStaticSuggestion("Add gp",
                "Add at least <FONT COLOR=" + RuneAssistColors.hex(RuneAssistColors.ACCENT) + ">"
                        + formatter.format(MIN_GP_NEEDED_TO_FLIP)
                        + "</FONT> gp");
    }

    public void suggestScanningForDumps() {
        showStaticSuggestion("Scan", "Waiting for dumps...");
    }

    public void suggestOpenGe() {
        showStaticSuggestion("Open GE", "Open the Grand Exchange to continue");
    }

    public void suggestAway() {
        setServerMessage("");
        showStaticSuggestion("Away", "Open the GE for suggestions");
    }

    public void setIsPausedMessage() {
        showStaticSuggestion("Paused", "Suggestions are paused");
    }

    public void setMessage(String message) {
        stopLoadingStatus();
        waitRefreshState.clear();
        additionalInfoText.setVisible(false);
        clearSuggestionTooltips();
        displayedMessage.set(message);
        setButtonsVisible(false);

        suggestionText.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
        suggestionText.setText("<html><center>" + message + "<br>" + serverMessage + "</center></html>");
        showMessageCard();
        suggestionTextContainer.revalidate();
        suggestionTextContainer.repaint();
    }

    public boolean isCollectItemsSuggested() {
        return COLLECT_MESSAGE.equals(displayedMessage.get());
    }

    public void clearCollectSuggestion() {
        // Consume the mismatch immediately, even if fetching is delayed or the EDT is busy.
        String message = displayedMessage.get();
        if (COLLECT_MESSAGE.equals(message) && displayedMessage.compareAndSet(message, null)) {
            SwingUtilities.invokeLater(() -> {
                // Do not overwrite a newer message or a structured/loading card.
                if (displayedMessage.get() == null && suggestionTextContainer.isVisible()) {
                    showFetchingWait();
                }
            });
        }
    }

    public void showLoading() {
        if (loadingStatusVisible) return;
        setServerMessage("");
        showStaticSuggestion("Wait", "Getting the next flip…");
        cardHeader.setVisible(false);
        suggestionIcon.setVisible(false);
        loadingStatusVisible = true;
        loadingStatusTimer.restart();
    }

    public void hideLoading() {
        stopLoadingStatus();
        spinner.hide();
        additionalInfoText.setVisible(true);
    }

    private void stopLoadingStatus() {
        loadingStatusVisible = false;
        loadingStatusTimer.stop();
    }

    @Override public void removeNotify() {
        stopLoadingStatus();
        super.removeNotify();
    }

    private void setButtonsVisible(boolean visible) {
        skipButton.setVisible(visible);
        blockButton.setVisible(visible);
        if (!visible) {
            suggestionIcon.setVisible(false);
        }
    }

    public void displaySuggestion() {
        Suggestion suggestion = suggestionManager.getSuggestion();
        setServerMessage("");
        if (!grandExchange.isOpen() && (suggestion == null || !suggestion.isDecantSuggestion())) {
            suggestAway();
            highlightController.removeAll();
            return;
        }
        if (suggestion == null) {
            showFetchingWait();
            suggestionManager.setSuggestionNeeded(true);
            return;
        }
        if (suggestion.getType() == null
                || (suggestion.isModifySuggestion() && suggestionController.isGhostModify(suggestion))) {
            log.info("dropping unactionable suggestion type={} item={}",
                    suggestion.getType(), suggestion.getItemId());
            if (suggestion.isModifySuggestion() && suggestion.actionedTick == -1) {
                suggestion.actionedTick = 0;
            }
            accountStatusManager.clearOwnedModify();
            suggestionManager.setSuggestionNeeded(true);
            showFetchingWait();
            return;
        }
        AccountStatus accountStatus = accountStatusManager.getAccountStatus();
        if(accountStatus == null) {
            showFetchingWait();
            suggestionManager.setSuggestionNeeded(true);
            return;
        }
        if (!suggestionController.isSellAvailableNow(suggestion)) {
            suggestionManager.setSuggestion(null);
            suggestionManager.setSuggestionNeeded(true);
            highlightController.removeAll();
            showFetchingWait();
            return;
        }
        boolean collectNeeded = accountStatus.isCollectNeeded(suggestion, grandExchange.isSetupOfferOpen());
        if(collectNeeded && !uncollectedManager.HasUncollected(osrsLoginManager.getAccountHash())) {
            log.warn("tick {} collect is suggested but there is nothing to collect! suggestion: {} {} {}", client.getTickCount(), suggestion.getType(), suggestion.getQuantity(), suggestion.getItemId());
        }
        if (HubPluginConflict.WAIT_MESSAGE.equals(suggestion.getMessage())) {
            suggestHubConflict();
        } else if (collectNeeded) {
            suggestCollect();
        } else if (suggestion.isWaitSuggestion() && !grandExchange.isOpen() && accountStatus.emptySlotExists()) {
            suggestOpenGe();
        } else if (suggestion.isWaitSuggestion() && accountStatus.moreGpNeeded()) {
            suggestAddGp();
        } else if (suggestion.isWaitSuggestion()
                && grandExchange.isOpen()
                && accountStatus.emptySlotExists()
                && suggestionPreferencesManager.isReceiveDumpSuggestions()
                && Strings.isNullOrEmpty(suggestion.getMessage())) {
            suggestScanningForDumps();
        }  else {
            updateSuggestion(suggestion);
        }
        highlightController.redraw();
    }

    private void showFetchingWait() {
        showStaticSuggestion("Wait", "Getting the next flip…");
    }

    public void refresh() {
        log.debug("refreshing suggestion panel {}", client.getGameState());
        if (!ensureEdt(this::refresh)) return;
        pauseButton.updateState();
        if (pausedManager.isPaused()) {
            hideLoading();
            setIsPausedMessage();
            return;
        }

        String errorMessage = osrsLoginManager.getInvalidStateDisplayMessage();
        if (errorMessage != null) {
            hideLoading();
            setServerMessage("");
            showStaticSuggestion("Login", errorMessage);
            return;
        }

        Suggestion current = suggestionManager.getSuggestion();
        if (suggestionController.getTradingContext().isAway()
                && (current == null || !current.isDecantSuggestion())) {
            hideLoading();
            suggestAway();
            return;
        }

        if(suggestionManager.isSuggestionRequestInProgress() || suggestionManager.isSuggestionRefreshPending()) {
            // Routine polling must not blank an already displayed WAIT card.
            // Only retain that exact result on the same account; startup, state
            // changes and actionable trades still use the normal loading path.
            if (!waitRefreshState.canKeepWhileRefreshing(suggestionManager.getSuggestion(),
                    osrsLoginManager.getAccountHash())) {
                showLoading();
            }
            return;
        }
        hideLoading();

        if(!client.isClientThread()) {
            clientThread.invoke(this::displaySuggestion);
        } else {
            displaySuggestion();
        }
    }

    private void populateFlags(Suggestion suggestion) {
        flagsRow.removeAll();
        List<String> flags = suggestion == null ? Collections.emptyList() : suggestion.getFlags();
        if (flags == null || flags.isEmpty()) {
            flagsRow.setVisible(false);
            flagsRow.revalidate();
            flagsRow.repaint();
            return;
        }
        flagsRow.setVisible(true);
        for (String flag : flags) {
            if (flag == null || flag.isEmpty()) {
                continue;
            }
            flagsRow.add(RuneAssistColors.flagChip(flag));
        }
        flagsRow.revalidate();
        flagsRow.repaint();
    }

    private String formatEstimatedProfit(Double expectedProfit, Double duration, boolean lossColor) {
        if (expectedProfit == null) {
            return "";
        }
        Color color = lossColor && expectedProfit < 0
                ? config.lossAmountColor() : config.profitAmountColor();
        String text = boldColor(formatProfit(expectedProfit), color) + " profit";
        if (duration != null && Double.isFinite(duration) && duration > 0) {
            text += " in ~" + formatSuggestionDuration(duration);
        } else {
            text = "Est. " + text;
        }
        return text;
    }

    private String formatWaitSlotStatus() {
        AccountStatus status = accountStatusManager.getAccountStatus();
        if (status == null || status.getOffers() == null) {
            return "";
        }
        int max = status.isWorldMember() || status.isAccountMember() ? 8 : 3;
        int used = 0;
        Offer best = null;
        for (Offer offer : status.getOffers()) {
            if (offer == null || offer.getStatus() == OfferStatus.EMPTY) {
                continue;
            }
            used++;
            if (offer.isActive() && offer.getAmountTraded() > 0) {
                if (best == null || offer.getAmountTraded() > best.getAmountTraded()) {
                    best = offer;
                }
            }
        }
        String text = used + "/" + max + " slots";
        if (best != null && best.getAmountTotal() > 0) {
            text += " · " + NumberFormat.getIntegerInstance().format(best.getAmountTraded())
                    + "/" + NumberFormat.getIntegerInstance().format(best.getAmountTotal())
                    + " filling";
        }
        return text;
    }

    private String formatLimitLine(Suggestion suggestion) {
        int ge = suggestion.getGeLimit();
        int left = suggestion.getRemainingLimit();
        if (ge <= 0) {
            return "<br>limit unknown";
        }
        if (!suggestion.isLimitKnown() || left < 0) {
            // Wiki cap known; live remaining not tracked yet.
            return "<br>limit " + UIUtilities.quantityToRSDecimalStack(ge, false);
        }
        return "<br>limit " + UIUtilities.quantityToRSDecimalStack(left, false)
                + " / " + UIUtilities.quantityToRSDecimalStack(ge, false) + " left";
    }

    private String formatSuggestionTooltip(Suggestion suggestion, Double suggestionProfit) {
        String roiLine = formatRoiTooltipLine(suggestion, suggestionProfit);
        String costLine = formatCostTooltipLine(suggestion);
        StringBuilder tooltip = new StringBuilder("<html>");
        String profitBasis = SuggestionCardText.profitBasis(suggestion);
        if (!profitBasis.isEmpty()) appendTooltipLine(tooltip, profitBasis);
        String details = SuggestionCardText.details(suggestion.getMessage(), suggestion.getWhy());
        if (!details.isEmpty()) appendTooltipLine(tooltip, details);
        appendTooltipLine(tooltip, roiLine);
        appendTooltipLine(tooltip, costLine);
        if (suggestion.getExpectedDuration() != null && suggestion.getExpectedDuration() > 0) {
            appendTooltipLine(tooltip, "Estimated fill: " + formatSuggestionDuration(suggestion.getExpectedDuration()));
        }
        if (suggestion.isBuySuggestion()) appendTooltipLine(tooltip, formatLimitLine(suggestion).replaceFirst("^<br>", ""));
        if (suggestion.getFlags() != null) {
            for (String flag : suggestion.getFlags()) {
                if (!Strings.isNullOrEmpty(flag)) appendTooltipLine(tooltip, htmlEscape(flag));
            }
        }
        return tooltip.append("</html>").toString().replace("<html>", "<html><body width='260'>")
                .replace("</html>", "</body></html>");
    }

    private void appendTooltipLine(StringBuilder tooltip, String line) {
        if (line == null) {
            return;
        }
        if (tooltip.length() > "<html>".length()) {
            tooltip.append("<br>");
        }
        tooltip.append(line);
    }

    private String formatCostTooltipLine(Suggestion suggestion) {
        Long cost = profitCalculator.calculateSuggestionCostBasis(suggestion);
        if (cost == null) {
            return null;
        }
        return "Cost: <font color='#FFFFFF'>" + UIUtilities.quantityToRSDecimalStack(cost, false) + " gp</font>";
    }

    private String formatRoiTooltipLine(Suggestion suggestion, Double suggestionProfit) {
        if (suggestionProfit == null) {
            return null;
        }
        Double roi = profitCalculator.calculateSuggestionRoi(suggestion, suggestionProfit);
        if (roi == null) {
            return null;
        }
        Color roiColor = UIUtilities.getProfitColor(roi, config);
        return "ROI: <font color='" + colorHex(roiColor) + "'>" + formatRoi(roi) + "</font>";
    }

    private static String htmlEscape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void clearSuggestionTooltips() {
        additionalInfoText.setToolTipText(null);
        suggestionText.setToolTipText(null);
        headlineLabel.setToolTipText(null);
        qtyPriceLabel.setToolTipText(null);
    }

    private String boldColor(String text, Color color) {
        return "<b><font color='" + colorHex(color) + "'>" + text + "</font></b>";
    }

    private String formatRoi(double roi) {
        return String.format(Locale.ENGLISH, "%.2f%%", roi * 100.0d);
    }

    private String formatProfit(double profit) {
        if (Math.abs(profit) >= 1_000_000) {
            return String.format("%.1fM", profit / 1_000_000).replace(".0", "");
        } else if (Math.abs(profit) >= 1_000) {
            return String.format("%.1fK", profit / 1_000).replace(".0", "");
        } else {
            return String.format("%.0f", profit);
        }
    }
}
