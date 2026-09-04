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

import static com.runeassist.flip.ui.UIUtilities.*;
import static com.runeassist.flip.util.Constants.MIN_GP_NEEDED_TO_FLIP;


@Singleton
@Slf4j
public class SuggestionPanel extends JPanel {
    private static final int DEFAULT_PANEL_HEIGHT = 168;
    private static final int HEADER_TRAILING_INSET = 52;
    private static final String CARD_STRUCTURED = "structured";
    private static final String CARD_MESSAGE = "message";
    private static final String CARD_SPINNER = "spinner";

    // dependencies
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
    private final PremiumInstanceController premiumInstanceController;
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
    private String innerSuggestionMessage;
    private final CardLayout bodyLayout = new CardLayout();
    private final JPanel bodyCards = new JPanel(bodyLayout);
    private final JLabel headlineLabel = new JLabel();
    private final JLabel qtyPriceLabel = new JLabel();
    private final JPanel flagsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));

    private String serverMessage = "";

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
                           GrandExchange grandExchange,  PremiumInstanceController premiumInstanceController, FlipsDialogController flipsDialogController, ProfitCalculator profitCalculator, SuggestionController suggestionController) {
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
        this.premiumInstanceController = premiumInstanceController;
        this.flipsDialogController = flipsDialogController;
        this.profitCalculator = profitCalculator;
        this.suggestionController = suggestionController;

        Dimension size = new Dimension(MainPanel.CONTENT_WIDTH, DEFAULT_PANEL_HEIGHT);
        setPreferredSize(size);
        setMinimumSize(new Dimension(MainPanel.CONTENT_WIDTH, 120));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        setLayout(new BorderLayout());
        setBackground(RuneAssistColors.SHELL);

        suggestedActionPanel = darkPanel(new BorderLayout(), RuneAssistColors.CARD);
        suggestedActionPanel.setBorder(RuneAssistColors.cardBorder());
        suggestedActionPanel.add(buildHeader(), BorderLayout.NORTH);

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
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, HEADER_TRAILING_INSET));
        headlineLabel.setForeground(Color.WHITE);
        headlineLabel.setFont(headlineLabel.getFont().deriveFont(Font.BOLD, 13f));
        headlineLabel.setBorder(RuneAssistColors.sectionHeaderBorder());
        constrainWidth(headlineLabel);
        suggestionIcon.setVisible(false);
        suggestionIcon.setOpaque(true);
        suggestionIcon.setBackground(RuneAssistColors.CARD);
        suggestionIcon.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        JPanel titleRow = darkPanel(new BorderLayout(), RuneAssistColors.CARD);
        titleRow.add(suggestionIcon, BorderLayout.WEST);
        titleRow.add(headlineLabel, BorderLayout.CENTER);
        header.add(titleRow, BorderLayout.CENTER);
        return header;
    }

    private JPanel buildStructuredCard() {
        JPanel structured = UIUtilities.verticalPanel(RuneAssistColors.CARD);

        qtyPriceLabel.setForeground(RuneAssistColors.ACCENT);
        qtyPriceLabel.setFont(qtyPriceLabel.getFont().deriveFont(11f));
        qtyPriceLabel.setAlignmentX(LEFT_ALIGNMENT);
        constrainWidth(qtyPriceLabel);
        structured.add(qtyPriceLabel);

        additionalInfoText.setHorizontalAlignment(SwingConstants.LEFT);
        additionalInfoText.setForeground(RuneAssistColors.MUTED);
        additionalInfoText.setText("");
        additionalInfoText.setAlignmentX(LEFT_ALIGNMENT);
        additionalInfoText.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        constrainWidth(additionalInfoText);
        structured.add(additionalInfoText);

        flagsRow.setOpaque(true);
        flagsRow.setBackground(RuneAssistColors.CARD);
        flagsRow.setAlignmentX(LEFT_ALIGNMENT);
        flagsRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));
        constrainWidth(flagsRow);
        structured.add(flagsRow);
        return structured;
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
        suggestionText.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        return suggestionTextContainer;
    }

    private void setHeadline(String text) {
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
        bodyLayout.show(bodyCards, CARD_STRUCTURED);
    }

    private void showMessageCard() {
        bodyLayout.show(bodyCards, CARD_MESSAGE);
    }

    private void setupButtonContainer() {
        buttonContainer.setLayout(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttonContainer.setBackground(RuneAssistColors.CARD);
        buttonContainer.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

        pauseButton.setToolTipText("Pause suggestions");
        pauseButton.setPreferredSize(new Dimension(22, 22));
        pauseButton.setMargin(new Insets(0, 0, 0, 0));
        buttonContainer.add(pauseButton);

        skipButton = new JButton("Skip");
        skipButton.setToolTipText("Skip suggestion");
        skipButton.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        RuneAssistColors.styleGhostButton(skipButton);
        skipButton.setMargin(new Insets(1, 6, 1, 6));
        skipButton.addActionListener(e -> suggestionController.skipSuggestion());
        buttonContainer.add(skipButton);

        BufferedImage graphIcon = ImageUtil.loadImageResource(getClass(), "/graph.png");
        buttonContainer.add(buildButton(graphIcon, "Price graph", flipsDialogController::openSuggestionPriceGraph));
        BufferedImage portfolioIcon = ImageUtil.loadImageResource(getClass(), "/pie-chart.png");
        buttonContainer.add(buildButton(portfolioIcon, "Open portfolio", flipsDialogController::showPortfolioTab));

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
            additionalInfoText.setText("<html><body width='196'>" + text + "</body></html>");
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
                setHeadline(suggestion.isHold() ? "Hold" : "Buy", suggestion.getName());
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
                qtyPriceLabel.setText(!Strings.isNullOrEmpty(suggestion.getMessage())
                        ? suggestion.getMessage() : "Decant now");
                setItemIcon(suggestion.getItemId());
                break;
            default:
                suggestionManager.setSuggestionNeeded(true);
                showFetchingWait();
                return;
        }
        populateFlags(suggestion);
        String why = suggestion.getWhy();
        boolean hasWhy = !Strings.isNullOrEmpty(why);
        String additionalInfoMessage = "";
        if (!suggestion.isWaitSuggestion() && !hasWhy && !Strings.isNullOrEmpty(suggestion.getMessage())) {
            additionalInfoMessage = suggestion.getMessage();
        }

        innerSuggestionMessage = "";
        if (!suggestion.isWaitSuggestion()) {
            setButtonsVisible(true);
        }
        String whyHtml = hasWhy ? htmlEscape(why) : "";
        if (suggestion.isBuySuggestion()) {
            String text = whyHtml;
            String profit = formatExpectedProfitAndDuration(suggestion.getExpectedProfit(), suggestion.getExpectedDuration());
            if (!profit.isEmpty()) {
                text = text.isEmpty() ? profit : text + "<br>" + profit;
            }
            if (!hasWhy) {
                text += additionalInfoMessage;
            }
            if (!containsIgnoreCase(why, "limit")) {
                text += formatLimitLine(suggestion);
            }
            setAdditionalInfoText(
                    text,
                    formatSuggestionTooltip(suggestion, suggestion.getExpectedProfit())
            );
        } else if (suggestion.isSellSuggestion()) {
            String text = whyHtml;
            Long profit = profitCalculator.calculateSuggestionProfit(suggestion);
            if (profit == null && suggestion.getExpectedProfit() != null) {
                profit = Math.round(suggestion.getExpectedProfit());
            }
            if (profit != null) {
                String profitText = formatSellProfitLossAndDuration((double) profit, suggestion.getExpectedDuration());
                text = text.isEmpty() ? profitText : text + "<br>" + profitText;
            }
            if (!hasWhy) {
                text += additionalInfoMessage;
            }
            setAdditionalInfoText(
                    text,
                    formatSuggestionTooltip(suggestion, profit == null ? null : (double) profit)
            );
        } else {
            String waitText = hasWhy ? whyHtml : additionalInfoMessage;
            if (waitText == null || waitText.isEmpty()) {
                waitText = formatWaitSlotStatus();
            }
            setAdditionalInfoText(waitText, null);
        }

        showStructuredCard();
    }

    /** WAIT must always show a pill + why. Never a blank RUNEASSIST header. */
    private void paintWait(Suggestion suggestion) {
        setHeadline("Wait");
        suggestionIcon.setVisible(false);
        populateFlags(null);
        String message = suggestion.getMessage();
        if (HubPluginConflict.WAIT_MESSAGE.equals(message)) {
            showCenteredMessage("<FONT COLOR=gray>" + HubPluginConflict.WAIT_MESSAGE + "</FONT>");
            return;
        }
        if (Strings.isNullOrEmpty(message)) {
            message = "Wait";
        }
        qtyPriceLabel.setText("");
        String why = suggestion.getWhy();
        if (Strings.isNullOrEmpty(why)) {
            why = formatWaitSlotStatus();
        }
        String body = htmlEscape(message);
        if (!Strings.isNullOrEmpty(why) && !why.equals(message)) {
            body += "<br>" + htmlEscape(why);
        }
        setAdditionalInfoText(body, null);
        setButtonsVisible(false);
        showStructuredCard();
    }

    private boolean shouldSellFromBank(Suggestion suggestion) {
        AccountStatus accountStatus = accountStatusManager.getAccountStatus();
        return accountStatus != null && accountStatus.shouldSellFromBank(suggestion);
    }

    public void suggestHubConflict() {
        setHeadline("Wait");
        populateFlags(null);
        setMessage("<FONT COLOR=gray>" + HubPluginConflict.WAIT_MESSAGE + "</FONT>");
        setButtonsVisible(false);
    }

    public void suggestCollect() {
        setHeadline("Collect");
        populateFlags(null);
        setMessage("Collect items");
        setButtonsVisible(false);
    }

    public void suggestAddGp() {
        NumberFormat formatter = NumberFormat.getNumberInstance();
        setHeadline("Add gp");
        populateFlags(null);
        setMessage("Add at least <FONT COLOR=" + RuneAssistColors.hex(RuneAssistColors.ACCENT) + ">" + formatter.format(MIN_GP_NEEDED_TO_FLIP)
                + "</FONT> gp to your inventory to get a flip suggestion");
        setButtonsVisible(false);
    }

    public void suggestScanningForDumps() {
        setHeadline("Scan");
        populateFlags(null);
        setMessage("Waiting for dumps...");
        setButtonsVisible(false);
    }

    public void suggestOpenGe() {
        setHeadline("Open GE");
        populateFlags(null);
        setMessage("Open the Grand Exchange to get a flip suggestion");
        setButtonsVisible(false);
    }

    public void setIsPausedMessage() {
        setHeadline("Paused");
        populateFlags(null);
        setMessage("Suggestions are paused");
        setButtonsVisible(false);
    }

    private void showCenteredMessage(String message) {
        innerSuggestionMessage = message;
        setButtonsVisible(false);
        suggestionText.setText("<html><center>" + message + "<br>" + serverMessage + "</center></html>");
        showMessageCard();
        suggestionTextContainer.revalidate();
        suggestionTextContainer.repaint();
    }

    public void setMessage(String message) {
        additionalInfoText.setVisible(false);
        clearSuggestionTooltips();
        innerSuggestionMessage = message;
        setButtonsVisible(false);

        String displayMessage = message;
        if (message != null && message.contains("<manage>")) {
            displayMessage = message.replace("<manage>",
                    "<a href='#' style='text-decoration:underline'>manage</a>");

            boolean hasListener = false;
            for (MouseListener listener : suggestionText.getMouseListeners()) {
                if (listener instanceof ManageClickListener) {
                    hasListener = true;
                    break;
                }
            }

            if (!hasListener) {
                suggestionText.addMouseListener(new ManageClickListener());
                suggestionText.setCursor(new Cursor(Cursor.HAND_CURSOR));
            }
        } else {
            suggestionText.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
        }
        suggestionText.setText("<html><center>" + displayMessage + "<br>" + serverMessage + "</center></html>");
        showMessageCard();
        suggestionTextContainer.revalidate();
        suggestionTextContainer.repaint();
    }

    private class ManageClickListener extends MouseAdapter {
        @Override
        public void mouseClicked(MouseEvent e) {
            String text = suggestionText.getText();
            if (text.contains("manage")) {
                premiumInstanceController.loadAndOpenPremiumInstanceDialog();
            }
        }
    }

    public boolean isCollectItemsSuggested() {
        return suggestionText.isVisible() && "Collect items".equals(innerSuggestionMessage);
    }

    public void showLoading() {
        setHeadline("…");
        populateFlags(null);
        setServerMessage("");
        bodyLayout.show(bodyCards, CARD_SPINNER);
        spinner.show();
        setButtonsVisible(false);
        suggestionIcon.setVisible(false);
        additionalInfoText.setText("");
        clearSuggestionTooltips();
        additionalInfoText.setVisible(false);
        suggestionText.setText("");
    }

    public void hideLoading() {
        spinner.hide();
        additionalInfoText.setVisible(true);
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
        boolean collectNeeded = accountStatus.isCollectNeeded(suggestion, grandExchange.isSetupOfferOpen());
        if(collectNeeded && !uncollectedManager.HasUncollected(osrsLoginManager.getAccountHash())) {
            log.warn("tick {} collect is suggested but there is nothing to collect! suggestion: {} {} {}", client.getTickCount(), suggestion.getType(), suggestion.getQuantity(), suggestion.getItemId());
        }
        if (HubPluginConflict.WAIT_MESSAGE.equals(suggestion.getMessage())) {
            suggestHubConflict();
        } else if (collectNeeded) {
            setServerMessage(suggestion.getMessage());
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
        setHeadline("Wait");
        populateFlags(null);
        setMessage("Getting the next flip…");
    }

    public void refresh() {
        log.debug("refreshing suggestion panel {}", client.getGameState());
        if (!ensureEdt(this::refresh)) return;
        if (pausedManager.isPaused()) {
            hideLoading();
            setIsPausedMessage();
            return;
        }

        String errorMessage = osrsLoginManager.getInvalidStateDisplayMessage();
        if (errorMessage != null) {
            hideLoading();
            setServerMessage("");
            setHeadline("Login");
            populateFlags(null);
            setMessage(errorMessage);
            return;
        }

        if(suggestionManager.isSuggestionRequestInProgress() || suggestionManager.isSuggestionRefreshPending()) {
            showLoading();
            return;
        }
        hideLoading();

        final HttpResponseException suggestionError = suggestionManager.getSuggestionError();
        if(suggestionError != null) {
            highlightController.redraw();
            setHeadline("Error");
            populateFlags(null);
            setMessage("Error: " + suggestionError.getMessage());
            return;
        }

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

    private String formatSellProfitLossAndDuration(Double expectedProfit, Double expectedDuration) {
        String formattedProfit = formatProfit(expectedProfit);
        Color color = config.profitAmountColor();
        if(expectedProfit < 0) {
            color = config.lossAmountColor();
        }
        String text = boldColor(formattedProfit, color) + " profit";
        if (expectedDuration != null) {
            String formattedDuration = formatSuggestionDuration(expectedDuration);
            text += " in <b>" + formattedDuration + "</b>";
        }
        return text;
    }

    private String formatExpectedProfitAndDuration(Double expectedProfit, Double expectedDuration) {
        if (expectedProfit == null) {
            return "";
        }
        String formattedProfit = formatProfit(expectedProfit);
        String text = boldColor(formattedProfit, config.profitAmountColor()) + " profit";
        if (expectedDuration != null && expectedDuration > 0) {
            String formattedDuration = formatSuggestionDuration(expectedDuration);
            text += " in <b>" + formattedDuration + "</b>";
        }
        return text;
    }

    /** Slot counts when Wait has no engine why — the south card should still show numbers. */
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

    private static boolean containsIgnoreCase(String s, String needle) {
        if (s == null || needle == null || s.isEmpty()) {
            return false;
        }
        return s.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    /** Remaining 4h buy-limit vs wiki GE limit, or an honest "unknown" when we have no live fills. */
    private String formatLimitLine(Suggestion suggestion) {
        int ge = suggestion.getGeLimit();
        int left = suggestion.getRemainingLimit();
        if (!suggestion.isLimitKnown() || ge <= 0 || left < 0) {
            return "<br>limit unknown";
        }
        return "<br>limit " + UIUtilities.quantityToRSDecimalStack(left, false)
                + " / " + UIUtilities.quantityToRSDecimalStack(ge, false) + " left";
    }

    private String formatSuggestionTooltip(Suggestion suggestion, Double suggestionProfit) {
        String roiLine = formatRoiTooltipLine(suggestion, suggestionProfit);
        String costLine = formatCostTooltipLine(suggestion);
        if (roiLine == null && costLine == null) {
            return null;
        }
        StringBuilder tooltip = new StringBuilder("<html>");
        appendTooltipLine(tooltip, roiLine);
        appendTooltipLine(tooltip, costLine);
        return tooltip.append("</html>").toString();
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
