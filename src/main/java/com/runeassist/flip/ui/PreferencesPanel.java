package com.runeassist.flip.ui;

import com.runeassist.flip.controller.*;
import com.runeassist.flip.model.SuggestionPreferencesManager;
import com.runeassist.flip.rs.AccountSuggestionPreferencesRS;
import com.runeassist.flip.model.SuggestionManager;
import com.runeassist.flip.ui.components.ItemSearchMultiSelect;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.LinkBrowser;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;

import static com.runeassist.flip.ui.UIUtilities.*;
import java.util.List;

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
    private final BugReportClient bugReportClient;
    private final net.runelite.api.Client client;
    private final ClientThread clientThread;
    private final DrawManager drawManager;
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
    private boolean suppressMinProfitEvents;
    private boolean suppressReservedSlotsEvents;
    private boolean suppressDumpAlertsEvents;

    @Inject
    public PreferencesPanel(
            SuggestionManager suggestionManager,
            SuggestionPreferencesManager preferencesManager,
            ItemController itemController,
            AccountSuggestionPreferencesRS accountPreferences,
            BugReportClient bugReportClient,
            net.runelite.api.Client client,
            ClientThread clientThread,
            DrawManager drawManager,
            @Named("runeAssistExecutor") ScheduledExecutorService executorService) {
        super();
        this.preferencesManager = preferencesManager;
        this.accountPreferences = accountPreferences;
        this.bugReportClient = bugReportClient;
        this.client = client;
        this.clientThread = clientThread;
        this.drawManager = drawManager;
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

        JLabel supportTitle = new JLabel("Support");
        supportTitle.setForeground(Color.WHITE);
        supportTitle.setFont(supportTitle.getFont().deriveFont(Font.BOLD));
        preferencesContent.add(supportTitle);
        addVerticalGap(preferencesContent, 4);

        JLabel openDashboardLink = RuneAssistColors.caption("Open dashboard");
        openDashboardLink.setForeground(RuneAssistColors.ACCENT);
        openDashboardLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        openDashboardLink.setAlignmentX(Component.LEFT_ALIGNMENT);
        openDashboardLink.setToolTipText(bugReportClient.websiteUrl());
        openDashboardLink.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                LinkBrowser.browse(bugReportClient.websiteUrl());
            }
        });
        preferencesContent.add(openDashboardLink);
        addVerticalGap(preferencesContent, 4);

        JLabel reportBugLink = RuneAssistColors.caption("Report a bug");
        reportBugLink.setForeground(RuneAssistColors.ACCENT);
        reportBugLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        reportBugLink.setAlignmentX(Component.LEFT_ALIGNMENT);
        reportBugLink.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                captureScreenshotThenPrompt();
            }
        });
        preferencesContent.add(reportBugLink);

        for (Component c : preferencesContent.getComponents()) {
            if (c instanceof JComponent && !(c instanceof Box.Filler)) {
                stretchWidth((JComponent) c);
            }
        }
        preferencesContent.add(Box.createVerticalGlue());

        // Bug report + dashboard links only (no local flip-history cloud sync).
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

    private static final int SCREENSHOT_MAX_WIDTH = 1280;

    /** Grabs the next rendered frame before showing the report dialog, so there's something to
     * attach/preview immediately rather than the player having to take and find a screenshot
     * themselves. Only reachable from the "Report a bug" link, which is itself only reachable
     * while logged into the game (see the "preferences"/"login" CardLayout switch in the
     * constructor) -- so a frame should always be available to capture. */
    private void captureScreenshotThenPrompt() {
        drawManager.requestNextFrameListener(image -> {
            byte[] png = null;
            try {
                BufferedImage captured = new BufferedImage(
                        image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
                Graphics g = captured.getGraphics();
                g.drawImage(image, 0, 0, null);
                g.dispose();
                png = encodePng(scaleDown(captured, SCREENSHOT_MAX_WIDTH));
            } catch (Exception e) {
                log.warn("bug report screenshot capture failed", e);
            }
            byte[] finalPng = png;
            SwingUtilities.invokeLater(() -> promptAndSubmitBugReport(finalPng));
        });
    }

    private static BufferedImage scaleDown(BufferedImage src, int maxWidth) {
        if (src.getWidth() <= maxWidth) {
            return src;
        }
        int newHeight = Math.round(src.getHeight() * (maxWidth / (float) src.getWidth()));
        BufferedImage scaled = new BufferedImage(maxWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scaled.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(src, 0, 0, maxWidth, newHeight, null);
        g2.dispose();
        return scaled;
    }

    private static byte[] encodePng(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            log.warn("bug report screenshot encode failed", e);
            return null;
        }
    }

    private static final String BUG_REPORT_HOST = "runeassist.ares-server.co.uk";

    /**
     * Consent for the bug-report network call lives in this dialog's OK button — see
     * {@link BugReportClient#reportBug}. Screenshot defaults OFF.
     */
    private void promptAndSubmitBugReport(byte[] screenshotPng) {
        JTextArea textArea = new JTextArea(6, 30);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(new JScrollPane(textArea));

        JLabel disclosure = new JLabel("<html><body style='width:260px'>Sends this text, your RSN and the "
                + "screenshot to " + BUG_REPORT_HOST + " (RuneAssist's server).</body></html>");
        disclosure.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        disclosure.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(Box.createVerticalStrut(6));
        content.add(disclosure);

        JCheckBox includeScreenshot = new JCheckBox("Include screenshot", false);
        if (screenshotPng != null) {
            try {
                BufferedImage preview = ImageIO.read(new ByteArrayInputStream(screenshotPng));
                Image thumb = preview.getScaledInstance(220, -1, Image.SCALE_SMOOTH);
                JLabel thumbLabel = new JLabel(new ImageIcon(thumb));
                content.add(Box.createVerticalStrut(6));
                content.add(includeScreenshot);
                content.add(thumbLabel);
            } catch (IOException e) {
                log.warn("bug report screenshot preview failed", e);
            }
        }

        int result = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(this),
                content,
                "Report a bug — what happened?",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        String message = textArea.getText() == null ? "" : textArea.getText().trim();
        if (message.isEmpty()) {
            return;
        }
        byte[] attach = (screenshotPng != null && includeScreenshot.isSelected()) ? screenshotPng : null;
        clientThread.invoke(() -> {
            net.runelite.api.Player localPlayer = client.getLocalPlayer();
            String displayName = localPlayer != null ? localPlayer.getName() : null;
            submitBugReport(displayName, message, attach);
        });
    }

    private void submitBugReport(String displayName, String message, byte[] attach) {
        bugReportClient.reportBug(displayName, message, attach, ok -> {
            JOptionPane.showMessageDialog(
                    SwingUtilities.getWindowAncestor(this),
                    ok ? "Thanks — logged." : "Couldn't submit right now (check your connection and try again).",
                    "Report a bug",
                    ok ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
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
