package com.runeassist.flip.ui;

import com.runeassist.flip.controller.*;
import com.runeassist.flip.model.SuggestionPreferencesManager;
import com.runeassist.flip.rs.AccountSuggestionPreferencesRS;
import com.runeassist.flip.model.SuggestionManager;
import com.runeassist.flip.ui.components.ItemSearchMultiSelect;
import com.runeassist.flip.ui.flipsdialog.WebAnalyticsLinks;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.LinkBrowser;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.runeassist.flip.ui.PrefsUi.Option;
import static com.runeassist.flip.ui.UIUtilities.*;

@Slf4j
@Singleton
public class PreferencesPanel extends JPanel {
    private static final Option[] MIN_PREDICTED_PROFIT_OPTIONS = {
            PrefsUi.option("Auto (off)", 0L),
            PrefsUi.option("20K", SuggestionPreferencesManager.DEFAULT_MIN_PREDICTED_PROFIT),
            PrefsUi.option("50K", 50_000L),
            PrefsUi.option("100K", 100_000L),
            PrefsUi.option("200K", 200_000L),
            PrefsUi.option("500K", 500_000L),
            PrefsUi.option("1M", 1_000_000L)
    };
    private static final Option[] RESERVED_SLOTS_OPTIONS = {
            PrefsUi.option("Auto", null),
            PrefsUi.option("0", 0), PrefsUi.option("1", 1), PrefsUi.option("2", 2),
            PrefsUi.option("3", 3), PrefsUi.option("4", 4), PrefsUi.option("5", 5),
            PrefsUi.option("6", 6), PrefsUi.option("7", 7), PrefsUi.option("8", 8)
    };
    private static final Option[] DUMP_ALERT_MIN_PROFIT_OPTIONS = {
            PrefsUi.option("Off", null),
            PrefsUi.option("100K+", 100_000L), PrefsUi.option("200K+", 200_000L),
            PrefsUi.option("500K+", 500_000L), PrefsUi.option("1M+", 1_000_000L),
            PrefsUi.option("2M+", 2_000_000L), PrefsUi.option("5M+", 5_000_000L)
    };
    private static final Option[] TIME_BASED_ABORT_MINUTES_OPTIONS = {
            PrefsUi.option("10m", 10),
            PrefsUi.option("15m", SuggestionPreferencesManager.DEFAULT_TIME_BASED_ABORT_MINUTES),
            PrefsUi.option("30m", 30), PrefsUi.option("60m", 60)
    };

    private final SuggestionPreferencesManager preferencesManager;
    private final AccountSuggestionPreferencesRS accountPreferences;
    private final FlipHistorySyncService flipHistorySyncService;
    @SuppressWarnings("unused") // Guice: keep stream controller alive with prefs UI
    private final DumpsStreamController dumpsStreamController;
    private final ScheduledExecutorService executorService;
    private final PreferencesToggleButton sellOnlyModeToggleButton;
    private final PreferencesToggleButton buyAndHoldToggleButton;
    private final PreferencesToggleButton f2pOnlyModeToggleButton;
    private final PreferencesToggleButton timeBasedAbortToggleButton;
    private final ItemSearchMultiSelect blocklistDropdownPanel;
    private final JComboBox<String> profileSelector;
    private final JButton addProfileButton;
    private final JButton deleteProfileButton;
    private final JComboBox<Option> reservedSlotsDropdown;
    private final JComboBox<Option> dumpAlertsDropdown;
    private final JComboBox<Option> timeBasedAbortMinutesDropdown;
    private final JPanel preferencesContent;
    private final JPanel loginPromptPanel;
    private final JComboBox<Option> minPredictedProfitDropdown;
    private final JLabel historyStatusLabel;
    private final JPanel pairingCodePanel;
    private final JTextField pairingCodeField;
    private final JLabel pairingCodeHint;
    private final JButton copyCodeButton;
    private final JButton openLinkButton;
    private final JButton linkDeviceBtn;
    private final JButton redeemBtn;
    private boolean suppressMinProfitEvents;
    private boolean suppressReservedSlotsEvents;
    private boolean suppressDumpAlertsEvents;
    private boolean suppressTimeBasedAbortMinutesEvents;

    @Inject
    public PreferencesPanel(
            SuggestionManager suggestionManager,
            SuggestionPreferencesManager preferencesManager,
            ItemController itemController,
            AccountSuggestionPreferencesRS accountPreferences,
            FlipHistorySyncService flipHistorySyncService,
            DumpsStreamController dumpsStreamController,
            @Named("runeAssistExecutor") ScheduledExecutorService executorService) {
        super();
        this.preferencesManager = preferencesManager;
        this.accountPreferences = accountPreferences;
        this.flipHistorySyncService = flipHistorySyncService;
        this.dumpsStreamController = dumpsStreamController;
        this.executorService = executorService;

        blocklistDropdownPanel = new ItemSearchMultiSelect(
                () -> new HashSet<>(preferencesManager.blockedItems()),
                itemController::allItemIds,
                itemController::search,
                (bl) -> {
                    preferencesManager.setBlockedItems(bl);
                    suggestionManager.setSuggestionNeeded(true);
                },
                "Item blocklist...",
                SwingUtilities.getWindowAncestor(this));

        setLayout(new CardLayout());
        setOpaque(true);
        setBackground(ColorScheme.DARKER_GRAY_COLOR);
        setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 28));

        preferencesContent = PrefsUi.scrollBody();
        preferencesContent.add(PrefsUi.sectionTitle("Suggestion Settings"));
        addVerticalGap(preferencesContent, 8);

        loginPromptPanel = darkPanel(new GridBagLayout(), ColorScheme.DARKER_GRAY_COLOR);
        JLabel loginPromptLabel = new JLabel("<html><center>Log in to the game<br>to alter suggestion settings.</center></html>");
        loginPromptLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        loginPromptPanel.add(loginPromptLabel);

        add(PrefsUi.verticalScroll(preferencesContent), "preferences");
        add(loginPromptPanel, "login");

        JPanel profilePanel = transparentPanel(new BorderLayout());
        profilePanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
        JPanel profileControlPanel = new JPanel();
        profileControlPanel.setLayout(new BoxLayout(profileControlPanel, BoxLayout.X_AXIS));
        profileControlPanel.setOpaque(false);

        profileSelector = new JComboBox<>();
        setFixedSize(profileSelector, 160, 25);
        profileSelector.addActionListener(e -> {
            String selectedProfile = (String) profileSelector.getSelectedItem();
            if (selectedProfile != null && !selectedProfile.equals(preferencesManager.getCurrentProfile())) {
                preferencesManager.setCurrentProfile(selectedProfile);
                refresh();
            }
        });

        addProfileButton = new JButton("+");
        setFixedSize(addProfileButton, 15, 25);
        addProfileButton.setToolTipText("Add new profile");
        addProfileButton.addActionListener(e -> addProfile());

        deleteProfileButton = new JButton("-");
        setFixedSize(deleteProfileButton, 15, 25);
        deleteProfileButton.setToolTipText("Delete current profile");
        deleteProfileButton.addActionListener(e -> deleteProfile());

        profileControlPanel.add(profileSelector);
        addHorizontalGap(profileControlPanel, 5);
        profileControlPanel.add(addProfileButton);
        addHorizontalGap(profileControlPanel, 2);
        profileControlPanel.add(deleteProfileButton);
        profilePanel.add(profileControlPanel, BorderLayout.LINE_START);
        preferencesContent.add(profilePanel);

        blocklistDropdownPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(5, 0, 5, 0),
                blocklistDropdownPanel.getBorder()));
        preferencesContent.add(blocklistDropdownPanel);

        buyAndHoldToggleButton = new PreferencesToggleButton("Disable holds", "Enable holds");
        PrefsUi.addRow(preferencesContent, "Enable holds", buyAndHoldToggleButton, 3);
        buyAndHoldToggleButton.addItemListener(i -> {
            preferencesManager.setBuyAndHold(buyAndHoldToggleButton.isSelected());
            suggestionManager.setSuggestionNeeded(true);
        });

        sellOnlyModeToggleButton = new PreferencesToggleButton("Disable sell-only mode", "Enable sell-only mode");
        PrefsUi.addRow(preferencesContent, "Sell-only mode", sellOnlyModeToggleButton, 3);
        sellOnlyModeToggleButton.addItemListener(i -> {
            preferencesManager.setSellOnlyMode(sellOnlyModeToggleButton.isSelected());
            suggestionManager.setSuggestionNeeded(true);
        });

        f2pOnlyModeToggleButton = new PreferencesToggleButton("Disable F2P-only mode", "Enable F2P-only mode");
        PrefsUi.addRow(preferencesContent, "F2P-only mode", f2pOnlyModeToggleButton, 0);
        f2pOnlyModeToggleButton.addItemListener(i -> {
            preferencesManager.setF2pOnlyMode(f2pOnlyModeToggleButton.isSelected());
            suggestionManager.setSuggestionNeeded(true);
        });

        minPredictedProfitDropdown = combo(MIN_PREDICTED_PROFIT_OPTIONS);
        minPredictedProfitDropdown.addActionListener(e -> {
            if (suppressMinProfitEvents) {
                return;
            }
            Option option = (Option) minPredictedProfitDropdown.getSelectedItem();
            long v = option == null || option.value == null
                    ? SuggestionPreferencesManager.DEFAULT_MIN_PREDICTED_PROFIT
                    : option.value.longValue();
            preferencesManager.setMinPredictedProfit(v);
            suggestionManager.setSuggestionNeeded(true);
        });
        JPanel minProfitRow = PrefsUi.addRow(preferencesContent, "Min. predicted profit", minPredictedProfitDropdown, 3);
        String minProfitTip = "Default 20K projected GP. Auto turns the floor off.";
        minPredictedProfitDropdown.setToolTipText(minProfitTip);
        minProfitRow.setToolTipText(minProfitTip);

        dumpAlertsDropdown = combo(DUMP_ALERT_MIN_PROFIT_OPTIONS);
        dumpAlertsDropdown.addActionListener(e -> {
            if (suppressDumpAlertsEvents) {
                return;
            }
            Option option = (Option) dumpAlertsDropdown.getSelectedItem();
            if (option == null || option.value == null) {
                preferencesManager.setReceiveDumpSuggestions(false);
                preferencesManager.setDumpMinPredictedProfit(null);
            } else {
                preferencesManager.setReceiveDumpSuggestions(true);
                preferencesManager.setDumpMinPredictedProfit(option.value.longValue());
            }
            suggestionManager.setSuggestionNeeded(true);
        });
        PrefsUi.addRow(preferencesContent, "Dump alerts", dumpAlertsDropdown, 6);

        timeBasedAbortToggleButton = new PreferencesToggleButton(
                "Disable aged-offer reprice", "Enable aged-offer reprice");
        PrefsUi.addRow(preferencesContent, "Aged-offer reprice", timeBasedAbortToggleButton, 3);
        timeBasedAbortToggleButton.setToolTipText(
                "When on, offers older than the minutes below may abort/reprice if the market moved. Default off.");
        timeBasedAbortToggleButton.addItemListener(i -> {
            preferencesManager.setTimeBasedAbortEnabled(timeBasedAbortToggleButton.isSelected());
            suggestionManager.setSuggestionNeeded(true);
        });

        timeBasedAbortMinutesDropdown = combo(TIME_BASED_ABORT_MINUTES_OPTIONS);
        timeBasedAbortMinutesDropdown.addActionListener(e -> {
            if (suppressTimeBasedAbortMinutesEvents) {
                return;
            }
            Option option = (Option) timeBasedAbortMinutesDropdown.getSelectedItem();
            int mins = option == null || option.value == null
                    ? SuggestionPreferencesManager.DEFAULT_TIME_BASED_ABORT_MINUTES
                    : option.value.intValue();
            preferencesManager.setTimeBasedAbortMinutes(mins);
            suggestionManager.setSuggestionNeeded(true);
        });
        JPanel ageRow = PrefsUi.addRow(preferencesContent, "Aged-offer minutes", timeBasedAbortMinutesDropdown, 6);
        ageRow.setToolTipText("Only used when Aged-offer reprice is enabled.");

        reservedSlotsDropdown = combo(RESERVED_SLOTS_OPTIONS);
        reservedSlotsDropdown.addActionListener(e -> {
            if (suppressReservedSlotsEvents) {
                return;
            }
            Option option = (Option) reservedSlotsDropdown.getSelectedItem();
            preferencesManager.setReservedSlots(option == null || option.value == null ? null : option.value.intValue());
            suggestionManager.setSuggestionNeeded(true);
        });
        PrefsUi.addRow(preferencesContent, "Reserved slots", reservedSlotsDropdown, 10);

        preferencesContent.add(PrefsUi.sectionTitle("Account & history"));
        addVerticalGap(preferencesContent, 4);

        historyStatusLabel = new JLabel();
        historyStatusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        historyStatusLabel.setFont(historyStatusLabel.getFont().deriveFont(11f));
        historyStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        preferencesContent.add(historyStatusLabel);
        addVerticalGap(preferencesContent, 6);

        pairingCodeField = new JTextField();
        pairingCodeField.setEditable(false);
        pairingCodeField.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
        pairingCodeField.setHorizontalAlignment(SwingConstants.CENTER);
        pairingCodeField.setBackground(ColorScheme.DARK_GRAY_COLOR);
        pairingCodeField.setForeground(Color.WHITE);
        pairingCodeField.setCaretColor(Color.WHITE);

        copyCodeButton = new JButton("Copy");
        RuneAssistColors.stylePrimaryButton(copyCodeButton);
        copyCodeButton.addActionListener(e -> copyPairingCode());

        openLinkButton = new JButton("Open");
        RuneAssistColors.stylePrimaryButton(openLinkButton);
        openLinkButton.addActionListener(e -> openPairingInBrowser());
        openLinkButton.setVisible(false);

        pairingCodeHint = RuneAssistColors.caption("Expires in 10 minutes.");
        pairingCodeHint.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel codeButtonRow = transparentPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        codeButtonRow.add(openLinkButton);
        codeButtonRow.add(copyCodeButton);

        JPanel codeRow = transparentPanel(new BorderLayout(6, 0));
        codeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        codeRow.add(pairingCodeField, BorderLayout.CENTER);
        codeRow.add(codeButtonRow, BorderLayout.EAST);

        pairingCodePanel = verticalPanel(ColorScheme.DARKER_GRAY_COLOR);
        pairingCodePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        pairingCodePanel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        pairingCodePanel.add(codeRow);
        addVerticalGap(pairingCodePanel, 2);
        pairingCodePanel.add(pairingCodeHint);
        pairingCodePanel.setVisible(false);
        preferencesContent.add(pairingCodePanel);
        addVerticalGap(preferencesContent, 4);

        JButton openWebsiteBtn = PrefsUi.ghostAction("Open website",
                "Graphs, history, pairing, and support on the dashboard");
        RuneAssistColors.stylePrimaryButton(openWebsiteBtn);
        openWebsiteBtn.addActionListener(e ->
                LinkBrowser.browse(WebAnalyticsLinks.url(flipHistorySyncService.websiteUrl(), null)));
        preferencesContent.add(openWebsiteBtn);
        addVerticalGap(preferencesContent, 4);

        linkDeviceBtn = PrefsUi.ghostAction("Get pairing code", "Code for another PC or the website");
        linkDeviceBtn.addActionListener(e -> startPairing());
        preferencesContent.add(linkDeviceBtn);
        addVerticalGap(preferencesContent, 4);

        redeemBtn = PrefsUi.ghostAction("Enter pairing code",
                "Redeem a code from another device or the website");
        redeemBtn.addActionListener(e -> redeemPairing());
        preferencesContent.add(redeemBtn);

        for (Component c : preferencesContent.getComponents()) {
            if (c instanceof JComponent && !(c instanceof Box.Filler)) {
                PrefsUi.stretchWidth((JComponent) c);
            }
        }
        preferencesContent.add(Box.createVerticalGlue());

        flipHistorySyncService.addStatusListener(this::refreshHistoryStatus);
        refreshHistoryStatus();
    }

    private static JComboBox<Option> combo(Option[] options) {
        JComboBox<Option> box = new JComboBox<>(new DefaultComboBoxModel<>(options));
        setFixedSize(box, 75, 25);
        return box;
    }

    private void addProfile() {
        String newProfileName = JOptionPane.showInputDialog(
                SwingUtilities.getWindowAncestor(this),
                "Enter new profile name (must be valid file name):",
                "New preferences profile",
                JOptionPane.PLAIN_MESSAGE);
        if (newProfileName == null || newProfileName.trim().isEmpty()) {
            return;
        }
        newProfileName = newProfileName.trim();
        try {
            preferencesManager.addProfile(newProfileName);
            refresh();
        } catch (IOException ex) {
            log.error("adding new profile: {}", newProfileName, ex);
            JOptionPane.showMessageDialog(
                    SwingUtilities.getWindowAncestor(this),
                    "Error adding new profile: " + ex.getMessage(),
                    "Add profile failed",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private void deleteProfile() {
        String selectedProfile = (String) profileSelector.getSelectedItem();
        if (selectedProfile == null) {
            return;
        }
        int result = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(this),
                "Delete profile '" + selectedProfile + "'?",
                "Delete Profile",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        ((DefaultComboBoxModel<String>) profileSelector.getModel()).removeElement(selectedProfile);
        try {
            preferencesManager.deleteSelectedProfile();
            profileSelector.setSelectedItem(preferencesManager.getCurrentProfile());
        } catch (IOException ex) {
            log.error("removing profile: {}", selectedProfile, ex);
            JOptionPane.showMessageDialog(
                    SwingUtilities.getWindowAncestor(this),
                    "Error deleting profile: " + ex.getMessage(),
                    "Remove profile failed",
                    JOptionPane.WARNING_MESSAGE);
        }
        refresh();
    }

    private void startPairing() {
        setPairingBusy(true);
        historyStatusLabel.setText("Getting pairing code…");
        executorService.execute(() -> {
            try {
                String code = flipHistorySyncService.startPairing();
                SwingUtilities.invokeLater(() -> {
                    setPairingBusy(false);
                    showPairingCode(code);
                });
            } catch (Exception ex) {
                log.warn("pairing start failed", ex);
                SwingUtilities.invokeLater(() -> {
                    setPairingBusy(false);
                    refreshHistoryStatus();
                    historyStatusLabel.setText("Could not get a pairing code. Check the server is reachable.");
                    JOptionPane.showMessageDialog(
                            SwingUtilities.getWindowAncestor(this),
                            "Could not get a pairing code. Check the server is reachable.",
                            "Get pairing code",
                            JOptionPane.WARNING_MESSAGE);
                });
            }
        });
    }

    private void showPairingCode(String code) {
        String trimmed = code == null ? "" : code.trim();
        if (trimmed.isEmpty() || looksLikeUrl(trimmed)) {
            pairingCodeField.setText("");
            pairingCodePanel.setVisible(false);
            openLinkButton.setVisible(false);
            refreshHistoryStatus();
            revalidate();
            repaint();
            return;
        }
        pairingCodeField.setText(trimmed);
        pairingCodeField.setCaretPosition(0);
        pairingCodeField.selectAll();
        pairingCodeHint.setText(PrefsUi.wrapHtml(
                "Enter this code on the other device or website. Expires in 10 minutes.", statusWrapPx()));
        pairingCodePanel.setVisible(true);
        openLinkButton.setVisible(true);
        PrefsUi.stretchWidth(pairingCodePanel);
        pairingCodePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                Math.max(96, pairingCodePanel.getPreferredSize().height)));
        refreshHistoryStatus();
        copyPairingCode();
        revalidate();
        repaint();
    }

    private static boolean looksLikeUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.contains("://");
    }

    private void openPairingInBrowser() {
        String code = pairingCodeField.getText();
        if (code == null || code.isEmpty()) {
            return;
        }
        LinkBrowser.browse(flipHistorySyncService.websiteLoginWithCodeUrl(code));
    }

    private void copyPairingCode() {
        String code = pairingCodeField.getText();
        if (code == null || code.isEmpty()) {
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);
        copyCodeButton.setText("Copied");
        executorService.schedule(() -> SwingUtilities.invokeLater(() -> copyCodeButton.setText("Copy")),
                2, TimeUnit.SECONDS);
    }

    private void setPairingBusy(boolean busy) {
        linkDeviceBtn.setEnabled(!busy);
        redeemBtn.setEnabled(!busy);
    }

    private void refreshHistoryStatus() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshHistoryStatus);
            return;
        }
        historyStatusLabel.setText(PrefsUi.wrapHtml(flipHistorySyncService.statusMessage(), statusWrapPx()));
        historyStatusLabel.setForeground(flipHistorySyncService.isLinked()
                ? ColorScheme.GRAND_EXCHANGE_PRICE
                : ColorScheme.LIGHT_GRAY_COLOR);
        PrefsUi.stretchWidth(historyStatusLabel);
        if (!hasPairingCode()) {
            pairingCodePanel.setVisible(false);
        }
    }

    private boolean hasPairingCode() {
        String code = pairingCodeField.getText();
        return code != null && !code.isBlank() && !looksLikeUrl(code);
    }

    private void redeemPairing() {
        String code = JOptionPane.showInputDialog(
                SwingUtilities.getWindowAncestor(this),
                "Enter the pairing code from your other device or the website:",
                "Enter pairing code",
                JOptionPane.PLAIN_MESSAGE);
        if (code == null || code.trim().isEmpty()) {
            return;
        }
        executorService.execute(() -> {
            try {
                flipHistorySyncService.redeemPairing(code);
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        SwingUtilities.getWindowAncestor(this),
                        "This client is now linked to that RuneAssist login.",
                        "Enter pairing code",
                        JOptionPane.INFORMATION_MESSAGE));
            } catch (Exception ex) {
                log.warn("pairing redeem failed", ex);
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        SwingUtilities.getWindowAncestor(this),
                        "Could not redeem that code. It may be expired or already used.",
                        "Enter pairing code",
                        JOptionPane.WARNING_MESSAGE));
            }
        });
    }

    public void refresh() {
        if (!ensureEdt(this::refresh)) return;
        CardLayout layout = (CardLayout) getLayout();
        if (!accountPreferences.hasAccount()) {
            layout.show(this, "login");
            return;
        }
        layout.show(this, "preferences");
        sellOnlyModeToggleButton.setSelected(preferencesManager.isSellOnlyMode());
        buyAndHoldToggleButton.setSelected(preferencesManager.isBuyAndHold());
        f2pOnlyModeToggleButton.setSelected(preferencesManager.isF2pOnlyMode());
        timeBasedAbortToggleButton.setSelected(preferencesManager.isTimeBasedAbortEnabled());
        suppressTimeBasedAbortMinutesEvents = true;
        try {
            timeBasedAbortMinutesDropdown.setSelectedItem(PrefsUi.findByLong(
                    timeBasedAbortMinutesDropdown,
                    preferencesManager.getTimeBasedAbortMinutes(),
                    SuggestionPreferencesManager.DEFAULT_TIME_BASED_ABORT_MINUTES,
                    1));
        } finally {
            suppressTimeBasedAbortMinutesEvents = false;
        }
        syncReservedSlots(preferencesManager.getReservedSlots());
        syncDumpAlerts(preferencesManager.isReceiveDumpSuggestions(), preferencesManager.getDumpMinPredictedProfit());
        syncMinPredictedProfit(preferencesManager.getMinPredictedProfit());
        deleteProfileButton.setVisible(!preferencesManager.isDefaultProfileSelected());
        DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) profileSelector.getModel();
        model.removeAllElements();
        model.addAll(preferencesManager.getAvailableProfiles());
        model.setSelectedItem(preferencesManager.getCurrentProfile());
    }

    private void syncMinPredictedProfit(Long value) {
        try {
            suppressMinProfitEvents = true;
            long effective = value == null
                    ? SuggestionPreferencesManager.DEFAULT_MIN_PREDICTED_PROFIT
                    : value;
            minPredictedProfitDropdown.setSelectedItem(PrefsUi.findByLong(
                    minPredictedProfitDropdown,
                    effective,
                    SuggestionPreferencesManager.DEFAULT_MIN_PREDICTED_PROFIT,
                    1));
        } finally {
            suppressMinProfitEvents = false;
        }
    }

    private void syncDumpAlerts(boolean enabled, Long minProfit) {
        try {
            suppressDumpAlertsEvents = true;
            dumpAlertsDropdown.setSelectedItem(enabled
                    ? PrefsUi.findByValue(dumpAlertsDropdown, minProfit != null ? minProfit : 100_000L, 1)
                    : dumpAlertsDropdown.getItemAt(0));
        } finally {
            suppressDumpAlertsEvents = false;
        }
    }

    private void syncReservedSlots(Integer value) {
        try {
            suppressReservedSlotsEvents = true;
            reservedSlotsDropdown.setSelectedItem(PrefsUi.findByValue(reservedSlotsDropdown, value, 0));
        } finally {
            suppressReservedSlotsEvents = false;
        }
    }

    private int statusWrapPx() {
        int width = getWidth();
        if (width <= 0) {
            width = MainPanel.CONTENT_WIDTH;
        }
        return Math.max(140, width - 48);
    }
}
