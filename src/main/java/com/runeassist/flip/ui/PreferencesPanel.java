package com.runeassist.flip.ui;

import com.runeassist.flip.controller.*;
import com.runeassist.flip.model.SuggestionPreferencesManager;
import com.runeassist.flip.rs.AccountSuggestionPreferencesRS;
import com.runeassist.flip.model.SuggestionManager;
import com.runeassist.flip.ui.components.ItemSearchMultiSelect;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.LinkBrowser;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

import static com.runeassist.flip.ui.UIUtilities.*;

@Slf4j
@Singleton
public class PreferencesPanel extends JPanel {
    private static final Option[] MIN_PREDICTED_PROFIT_OPTIONS = new Option[]{
            new Option("Auto (off)", 0L),
            new Option("20K", SuggestionPreferencesManager.DEFAULT_MIN_PREDICTED_PROFIT),
            new Option("50K", 50_000L),
            new Option("100K", 100_000L),
            new Option("200K", 200_000L),
            new Option("500K", 500_000L),
            new Option("1M", 1_000_000L)
    };

    private static final Option[] RESERVED_SLOTS_OPTIONS = new Option[]{
            new Option("Auto", null),
            new Option("0", 0),
            new Option("1", 1),
            new Option("2", 2),
            new Option("3", 3),
            new Option("4", 4),
            new Option("5", 5),
            new Option("6", 6),
            new Option("7", 7),
            new Option("8", 8)
    };

    private static final Option[] DUMP_ALERT_MIN_PROFIT_OPTIONS = new Option[]{
            new Option("Off", null),
            new Option("100K+", 100_000L),
            new Option("200K+", 200_000L),
            new Option("500K+", 500_000L),
            new Option("1M+", 1_000_000L),
            new Option("2M+", 2_000_000L),
            new Option("5M+", 5_000_000L)
    };

    private final SuggestionPreferencesManager preferencesManager;
    private final AccountSuggestionPreferencesRS accountPreferences;
    private final FlipHistorySyncService flipHistorySyncService;
    @SuppressWarnings("unused") // Guice: keep stream controller alive with prefs UI
    private final DumpsStreamController dumpsStreamController;
    private final ItemController itemController;
    private final ScheduledExecutorService executorService;
    private final PreferencesToggleButton sellOnlyModeToggleButton;
    private final PreferencesToggleButton buyAndHoldToggleButton;
    private final PreferencesToggleButton f2pOnlyModeToggleButton;
    private final ItemSearchMultiSelect blocklistDropdownPanel;
    private final JComboBox<String> profileSelector;
    private final JButton addProfileButton;
    private final JButton deleteProfileButton;
    private final JComboBox<Option> reservedSlotsDropdown;
    private final JComboBox<Option> dumpAlertsDropdown;
    private final JPanel preferencesContent;
    private final JPanel loginPromptPanel;
    private final JComboBox<Option> minPredictedProfitDropdown;
    private final JLabel historyStatusLabel;
    private final JButton redeemBtn;
    private boolean suppressMinProfitEvents;
    private boolean suppressReservedSlotsEvents;
    private boolean suppressDumpAlertsEvents;

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
        this.itemController = itemController;
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

        preferencesContent = new PrefsBody();

        JLabel preferencesTitle = new JLabel("Suggestion Settings");
        preferencesTitle.setForeground(Color.WHITE);
        preferencesTitle.setFont(preferencesTitle.getFont().deriveFont(Font.BOLD));
        preferencesTitle.setHorizontalAlignment(SwingConstants.LEFT);
        preferencesContent.add(preferencesTitle);
        addVerticalGap(preferencesContent, 8);

        loginPromptPanel = darkPanel(new GridBagLayout(), ColorScheme.DARKER_GRAY_COLOR);
        JLabel loginPromptLabel = new JLabel("<html><center>Log in to the game<br>to alter suggestion settings.</center></html>");
        loginPromptLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        loginPromptPanel.add(loginPromptLabel);

        add(scrollPreferences(preferencesContent), "preferences");
        add(loginPromptPanel, "login");

        // Profile selector panel
        JPanel profilePanel = transparentPanel(new BorderLayout());
        profilePanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));

        // Panel for dropdown and buttons
        JPanel profileControlPanel = new JPanel();
        profileControlPanel.setLayout(new BoxLayout(profileControlPanel, BoxLayout.X_AXIS));
        profileControlPanel.setOpaque(false);

        // Initialize profile model with default
        profileSelector = new JComboBox<>();
        setFixedSize(profileSelector, 160, 25);
        profileSelector.addActionListener(e -> {
            String selectedProfile = (String) profileSelector.getSelectedItem();
            if (selectedProfile != null && !selectedProfile.equals(preferencesManager.getCurrentProfile())) {
                preferencesManager.setCurrentProfile(selectedProfile);
                refresh();
            }
        });

        // Add button for creating new profiles
        addProfileButton = new JButton("+");
        setFixedSize(addProfileButton, 15, 25);
        addProfileButton.setToolTipText("Add new profile");
        addProfileButton.addActionListener(e -> {
            String newProfileName = JOptionPane.showInputDialog(
                    SwingUtilities.getWindowAncestor(this),
                    "Enter new profile name (must be valid file name):",
                    "New preferences profile",
                    JOptionPane.PLAIN_MESSAGE);
            if (newProfileName != null && !newProfileName.trim().isEmpty()) {
                newProfileName = newProfileName.trim();
                try {
                    preferencesManager.addProfile(newProfileName);
                    refresh();
                } catch (IOException ex) {
                    log.error("adding new profile: {}", newProfileName, ex);
                    JOptionPane.showMessageDialog(
                            SwingUtilities.getWindowAncestor(this),
                            "Error adding new profile: "+ ex.getMessage(),
                            "Add profile failed",
                            JOptionPane.WARNING_MESSAGE);
                }
            }
        });

        // Delete button for removing custom profiles
        deleteProfileButton = new JButton("-");
        setFixedSize(deleteProfileButton, 15, 25);
        deleteProfileButton.setToolTipText("Delete current profile");
        deleteProfileButton.addActionListener(e -> {
            String selectedProfile = (String) profileSelector.getSelectedItem();
            if (selectedProfile != null) {
                int result = JOptionPane.showConfirmDialog(
                        SwingUtilities.getWindowAncestor(this),
                        "Delete profile '" + selectedProfile + "'?",
                        "Delete Profile",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE);
                if (result == JOptionPane.YES_OPTION) {
                    ((DefaultComboBoxModel<String>) profileSelector.getModel()).removeElement(selectedProfile);
                    try {
                        preferencesManager.deleteSelectedProfile();
                        profileSelector.setSelectedItem(preferencesManager.getCurrentProfile());
                    } catch (IOException ex) {
                        log.error("removing profile: {}", selectedProfile, ex);
                        JOptionPane.showMessageDialog(
                                SwingUtilities.getWindowAncestor(this),
                                "Error deleting profile: "+ ex.getMessage(),
                                "Remove profile failed",
                                JOptionPane.WARNING_MESSAGE);
                    }
                    refresh();
                }
            }
        });

        profileControlPanel.add(profileSelector);
        addHorizontalGap(profileControlPanel, 5);
        profileControlPanel.add(addProfileButton);
        addHorizontalGap(profileControlPanel, 2);
        profileControlPanel.add(deleteProfileButton);

        profilePanel.add(profileControlPanel, BorderLayout.LINE_START);
        preferencesContent.add(profilePanel);

        // Blocklist dropdown panel
        blocklistDropdownPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(5, 0, 5, 0),
                blocklistDropdownPanel.getBorder()));
        preferencesContent.add(blocklistDropdownPanel);

        // Buy and hold toggle
        buyAndHoldToggleButton = new PreferencesToggleButton("Disable holds", "Enable holds");
        preferencesContent.add(formRow("Enable holds", buyAndHoldToggleButton));
        buyAndHoldToggleButton.addItemListener(i -> {
            preferencesManager.setBuyAndHold(buyAndHoldToggleButton.isSelected());
            suggestionManager.setSuggestionNeeded(true);
        });
        addVerticalGap(preferencesContent, 3);

        // Sell-only mode toggle
        sellOnlyModeToggleButton = new PreferencesToggleButton("Disable sell-only mode", "Enable sell-only mode");
        preferencesContent.add(formRow("Sell-only mode", sellOnlyModeToggleButton));
        sellOnlyModeToggleButton.addItemListener(i -> {
            preferencesManager.setSellOnlyMode(sellOnlyModeToggleButton.isSelected());
            suggestionManager.setSuggestionNeeded(true);
        });
        addVerticalGap(preferencesContent, 3);

        // F2P-only mode toggle
        f2pOnlyModeToggleButton = new PreferencesToggleButton("Disable F2P-only mode",  "Enable F2P-only mode");
        preferencesContent.add(formRow("F2P-only mode", f2pOnlyModeToggleButton));
        f2pOnlyModeToggleButton.addItemListener(i -> {
            preferencesManager.setF2pOnlyMode(f2pOnlyModeToggleButton.isSelected());
            suggestionManager.setSuggestionNeeded(true);
        });

        // Min predicted profit
        minPredictedProfitDropdown = new JComboBox<>(new DefaultComboBoxModel<>(MIN_PREDICTED_PROFIT_OPTIONS));
        setFixedSize(minPredictedProfitDropdown, 75, 25);
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
        JPanel minProfitRow = formRow("Min. predicted profit", minPredictedProfitDropdown);
        String minProfitTip = "Default is 20K projected GP. Auto turns the floor off.";
        minPredictedProfitDropdown.setToolTipText(minProfitTip);
        minProfitRow.setToolTipText(minProfitTip);
        preferencesContent.add(minProfitRow);
        addVerticalGap(preferencesContent, 3);

        // Dump alerts dropdown
        dumpAlertsDropdown = new JComboBox<>(new DefaultComboBoxModel<>(DUMP_ALERT_MIN_PROFIT_OPTIONS));
        setFixedSize(dumpAlertsDropdown, 75, 25);
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
        preferencesContent.add(formRow("Dump alerts", dumpAlertsDropdown));
        addVerticalGap(preferencesContent, 6);

        // Reserved slots
        reservedSlotsDropdown = new JComboBox<>(new DefaultComboBoxModel<>(RESERVED_SLOTS_OPTIONS));
        setFixedSize(reservedSlotsDropdown, 75, 25);
        reservedSlotsDropdown.addActionListener(e -> {
            if (suppressReservedSlotsEvents) {
                return;
            }
            Option option = (Option) reservedSlotsDropdown.getSelectedItem();
            preferencesManager.setReservedSlots(option == null || option.value == null ? null : option.value.intValue());
            suggestionManager.setSuggestionNeeded(true);
        });
        preferencesContent.add(formRow("Reserved slots", reservedSlotsDropdown));
        addVerticalGap(preferencesContent, 10);

        JLabel historyTitle = new JLabel("Account & history");
        historyTitle.setForeground(Color.WHITE);
        historyTitle.setFont(historyTitle.getFont().deriveFont(Font.BOLD));
        preferencesContent.add(historyTitle);
        addVerticalGap(preferencesContent, 4);

        historyStatusLabel = new JLabel();
        historyStatusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        historyStatusLabel.setFont(historyStatusLabel.getFont().deriveFont(11f));
        historyStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        preferencesContent.add(historyStatusLabel);
        addVerticalGap(preferencesContent, 6);

        JButton openWebsiteBtn = new JButton("Open website");
        openWebsiteBtn.setToolTipText("Graphs, history, pairing, and support live on the dashboard");
        openWebsiteBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        openWebsiteBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        RuneAssistColors.stylePrimaryButton(openWebsiteBtn);
        openWebsiteBtn.addActionListener(e -> LinkBrowser.browse(flipHistorySyncService.websiteUrl()));
        preferencesContent.add(openWebsiteBtn);
        addVerticalGap(preferencesContent, 4);

        redeemBtn = new JButton("Enter pairing code");
        redeemBtn.setToolTipText("Redeem a code from another device or the website");
        redeemBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        redeemBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        RuneAssistColors.styleGhostButton(redeemBtn);
        redeemBtn.addActionListener(e -> redeemPairing());
        preferencesContent.add(redeemBtn);

        for (Component c : preferencesContent.getComponents()) {
            if (c instanceof JComponent && !(c instanceof Box.Filler)) {
                stretchWidth((JComponent) c);
            }
        }
        preferencesContent.add(Box.createVerticalGlue());

        flipHistorySyncService.addStatusListener(this::refreshHistoryStatus);
        refreshHistoryStatus();
    }

    private void refreshHistoryStatus() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshHistoryStatus);
            return;
        }
        historyStatusLabel.setText("<html><body style='width:" + statusWrapPx() + "px'>"
                + flipHistorySyncService.statusMessage() + "</body></html>");
        historyStatusLabel.setForeground(flipHistorySyncService.isLinked()
                ? ColorScheme.GRAND_EXCHANGE_PRICE
                : ColorScheme.LIGHT_GRAY_COLOR);
        stretchWidth(historyStatusLabel);
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


    private static JScrollPane scrollPreferences(JPanel content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(true);
        scroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(12);
        return scroll;
    }

    private static void stretchWidth(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        int height = Math.max(component.getPreferredSize().height, 16);
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
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
        syncReservedSlots(preferencesManager.getReservedSlots());
        syncDumpAlerts(preferencesManager.isReceiveDumpSuggestions(), preferencesManager.getDumpMinPredictedProfit());
        syncMinPredictedProfit(preferencesManager.getMinPredictedProfit());
        deleteProfileButton.setVisible(!preferencesManager.isDefaultProfileSelected());
        List<String> correctOptions = preferencesManager.getAvailableProfiles();
        DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) profileSelector.getModel();
        model.removeAllElements();
        model.addAll(correctOptions);
        model.setSelectedItem(preferencesManager.getCurrentProfile());
    }

    private void syncMinPredictedProfit(Long value) {
        try {
            suppressMinProfitEvents = true;
            minPredictedProfitDropdown.setSelectedItem(findMinProfitOption(value));
        } finally {
            suppressMinProfitEvents = false;
        }
    }

    private void syncDumpAlerts(boolean enabled, Long minProfit) {
        try {
            suppressDumpAlertsEvents = true;
            dumpAlertsDropdown.setSelectedItem(findDumpAlertOption(enabled, minProfit));
        } finally {
            suppressDumpAlertsEvents = false;
        }
    }

    private void syncReservedSlots(Integer value) {
        try {
            suppressReservedSlotsEvents = true;
            reservedSlotsDropdown.setSelectedItem(findReservedSlotsOption(value));
        } finally {
            suppressReservedSlotsEvents = false;
        }
    }

    private Option findMinProfitOption(Long value) {
        long effective = value == null
                ? SuggestionPreferencesManager.DEFAULT_MIN_PREDICTED_PROFIT
                : value;
        Option fallback = null;
        for (int i = 0; i < minPredictedProfitDropdown.getItemCount(); i++) {
            Option option = minPredictedProfitDropdown.getItemAt(i);
            if (option.value == null) {
                continue;
            }
            if (option.value.longValue() == effective) {
                return option;
            }
            if (option.value.longValue() == SuggestionPreferencesManager.DEFAULT_MIN_PREDICTED_PROFIT) {
                fallback = option;
            }
        }
        return fallback != null ? fallback : minPredictedProfitDropdown.getItemAt(1);
    }

    private Option findDumpAlertOption(boolean enabled, Long minProfit) {
        if (!enabled) {
            return dumpAlertsDropdown.getItemAt(0);
        }
        Long effective = minProfit != null ? minProfit : 100_000L;
        for (int i = 0; i < dumpAlertsDropdown.getItemCount(); i++) {
            Option option = dumpAlertsDropdown.getItemAt(i);
            if (Objects.equals(option.value, effective)) {
                return option;
            }
        }
        return dumpAlertsDropdown.getItemAt(1);
    }

    private Option findReservedSlotsOption(Integer value) {
        for (int i = 0; i < reservedSlotsDropdown.getItemCount(); i++) {
            Option option = reservedSlotsDropdown.getItemAt(i);
            if (Objects.equals(option.value, value)) {
                return option;
            }
        }
        return reservedSlotsDropdown.getItemAt(0);
    }

    private int statusWrapPx() {
        int width = getWidth();
        if (width <= 0) {
            width = MainPanel.CONTENT_WIDTH;
        }
        return Math.max(140, width - 48);
    }

    private static final class PrefsBody extends JPanel implements Scrollable {
        PrefsBody() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(true);
            setBackground(ColorScheme.DARKER_GRAY_COLOR);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 12;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(12, visible.height - 12);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static final class Option {
        private final String label;
        private final Number value;

        private Option(String label, Number value) {
            this.label = label;
            this.value = value;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
