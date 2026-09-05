package com.runeassist.flip.ui;

import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.controller.*;
import com.runeassist.flip.model.*;
import com.runeassist.flip.rs.AccountLoginRS;
import com.runeassist.flip.rs.PortfolioStateRS;
import com.runeassist.flip.ui.components.AccountDropdown;
import com.runeassist.flip.ui.components.IntervalDropdown;
import com.runeassist.flip.ui.flipsdialog.FlipsDialogController;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;


import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

import static com.runeassist.flip.ui.UIUtilities.BUTTON_HOVER_LUMINANCE;

@Slf4j
@Singleton
public class StatsPanelV2 extends JPanel {
    private static final int SUB_INFO_ROW_VERTICAL_PADDING = 3;
    private static final int SUB_INFO_ROW_HEIGHT = 18;

    public final BufferedImage ARROW_ICON = ImageUtil.loadImageResource(getClass(),"/small_open_arrow.png");
    public final Icon OPEN_ICON = new ImageIcon(ARROW_ICON);
    public final Icon CLOSE_ICON = new ImageIcon(ImageUtil.rotateImage(ARROW_ICON, Math.toRadians(90)));
    public final BufferedImage FLIPS_DIALOG_ICON = ImageUtil.recolorImage(ImageUtil.resizeImage(ImageUtil.loadImageResource(getClass(),"/popout-flips.png"), 20, 20),ColorScheme.LIGHT_GRAY_COLOR);
    public final Icon FLIPS_DIALOG = new ImageIcon(FLIPS_DIALOG_ICON);
    public final Icon HIGHLIGHTED_FLIPS_DIALOG = new ImageIcon(ImageUtil.luminanceScale(FLIPS_DIALOG_ICON, BUTTON_HOVER_LUMINANCE));

    private JPanel sessionTimeRow;
    private JPanel hourlyProfitRow;

    // dependencies
    private final AccountLoginRS accountLoginRS;
    private final OsrsLoginManager osrsLoginManager;
    private final RuneAssistConfig config;
    private final FlipManager flipManager;
    private final SessionManager sessionManager;
    private final WebHookController webHookController;
    private final ClientThread clientThread;
    private final FlipsDialogController flipsDialogController;
    private final PortfolioStateRS portfolioStateRS;
    private final FlipHistorySyncService flipHistorySyncService;

    // state
    private IntervalDropdown intervalDropdown;
    private final AccountDropdown accountDropdown;
    private final JButton sessionResetButton = new JButton("  Reset session ");
    private JPanel profitAndSubInfoPanel;
    private JPanel subInfoPanel;
    private final JPanel flipsPanel = new JPanel();
    private final JLabel totalProfitVal = new JLabel("0 gp");
    private final JLabel roiVal = new JLabel("-0.00%");
    private final JLabel flipsMadeVal = new JLabel("0");
    private final JLabel openFlipsVal = new JLabel("0");
    private final JLabel unrealizedProfitVal = new JLabel("0 gp");
    private final JLabel sessionTimeVal = new JLabel("00:00:00");
    private final JLabel hourlyProfitVal = new JLabel("0 gp/hr");
    private final JLabel portfolioValueVal = new JLabel("0 gp");
    private final Paginator paginator;
    private final JButton flipsDialogButton = new JButton();

    private volatile boolean lastValidState = false;

    // Modified constructor
    @Inject
    public StatsPanelV2(AccountLoginRS accountLoginRS,
                        OsrsLoginManager osrsLoginManager,
                        RuneAssistConfig config,
                        FlipManager FlipManager,
                        SessionManager sessionManager,
                        WebHookController webHookController,
                        ClientThread clientThread,
                        FlipsDialogController flipsDialogController,
                        PortfolioStateRS portfolioStateRS,
                        FlipHistorySyncService flipHistorySyncService) {
        this.accountLoginRS = accountLoginRS;
        this.osrsLoginManager = osrsLoginManager;
        this.sessionManager = sessionManager;
        this.webHookController = webHookController;
        this.config = config;
        this.flipManager = FlipManager;
        this.clientThread = clientThread;
        this.flipsDialogController = flipsDialogController;
        this.portfolioStateRS = portfolioStateRS;
        this.flipHistorySyncService = flipHistorySyncService;
        setLayout(new BorderLayout());
        setBackground(RuneAssistColors.SHELL);

        setupTimeIntervalDropdown();
        setupSessionResetButton();
        accountDropdown = new AccountDropdown(
                () -> accountLoginRS.get().displayNameToAccountId,
                flipManager::setIntervalAccount,
                AccountDropdown.ALL_ACCOUNTS_DROPDOWN_OPTION
        );
        accountDropdown.setMaximumSize(new Dimension(Integer.MAX_VALUE, accountDropdown.getPreferredSize().height));
        setupProfitAndSubInfoPanel();
        setupFlipsDialogButton();

        flipsPanel.setLayout(new BoxLayout(flipsPanel, BoxLayout.Y_AXIS));
        flipsPanel.setBackground(RuneAssistColors.CARD);
        flipsPanel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

        JScrollPane scrollPane = new JScrollPane(flipsPanel);
        scrollPane.setBackground(RuneAssistColors.CARD);
        scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(2, 0));
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);

        JPanel mainPanel = UIUtilities.verticalPanel(RuneAssistColors.SHELL);

        mainPanel.add(profitAndSubInfoPanel);

        JPanel flipsHeader = new JPanel(new BorderLayout());
        flipsHeader.setOpaque(false);
        flipsHeader.setBorder(BorderFactory.createEmptyBorder(8, 2, 4, 0));
        flipsHeader.add(RuneAssistColors.kicker("RECENT FLIPS"), BorderLayout.WEST);
        flipsHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        mainPanel.add(flipsHeader);
        mainPanel.add(scrollPane);

        add(mainPanel, BorderLayout.CENTER);

        paginator = new Paginator((i) -> refresh(true, lastValidState));

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBackground(RuneAssistColors.SHELL);
        bottomPanel.add(paginator, BorderLayout.CENTER);
        bottomPanel.add(flipsDialogButton, BorderLayout.EAST);

        add(bottomPanel, BorderLayout.SOUTH);

        flipManager.setFlipsChangedCallback(() -> refresh(true, osrsLoginManager.isValidLoginState()));
    }

    private void setupFlipsDialogButton() {
        flipsDialogButton.setIcon(FLIPS_DIALOG);
        flipsDialogButton.setOpaque(true);
        flipsDialogButton.setEnabled(true);
        flipsDialogButton.setFocusable(true);
        flipsDialogButton.setBorder(BorderFactory.createEmptyBorder(0,0,0,5));
        flipsDialogButton.setBackground(RuneAssistColors.SHELL);
        flipsDialogButton.setToolTipText("Open flips dialog");

        flipsDialogButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                log.debug("opening flips dialog");
                flipsDialogController.showPortfolioTab();
            }
        });
        UIUtilities.addHoverIcons(flipsDialogButton, () -> FLIPS_DIALOG, () -> HIGHLIGHTED_FLIPS_DIALOG);
    }

    private void setupSessionResetButton() {
        sessionResetButton.setBorder(BorderFactory.createEmptyBorder());
        sessionResetButton.addActionListener((l) -> {
            final int result = JOptionPane.showOptionDialog(SwingUtilities.getWindowAncestor(this), "<html>Are you sure you want to reset the session?</html>",
                    "Are you sure?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, new String[]{"Yes", "No"}, "No");
            if (result == JOptionPane.YES_OPTION) {
                // send discord message before resetting session stats
                clientThread.invoke(() -> {
                    if (osrsLoginManager.isValidLoginState()) {
                        String displayName = osrsLoginManager.getPlayerDisplayName();
                        Integer accountId = displayName == null
                                ? null
                                : accountLoginRS.get().getAccountId(displayName);
                        if (accountId == null || accountId == -1) {
                            accountId = displayName == null ? null : FlipHistorySyncService.accountIdFor(displayName);
                        }
                        if (accountId != null && accountId != -1 && displayName != null) {
                            webHookController.sendMessage(flipManager.calculateStats(sessionManager.getCachedSessionData().startTime, accountId), sessionManager.getCachedSessionData(), displayName, true);
                        }
                        sessionManager.resetSession();
                        if (IntervalTimeUnit.SESSION.equals(intervalDropdown.getSelectedIntervalTimeUnit())) {
                            flipManager.setIntervalStartTime(sessionManager.getCachedSessionData().startTime);
                        }
                        refresh(true, osrsLoginManager.isValidLoginState());
                    }
                });
            }
        });
    }

    private void setupTimeIntervalDropdown() {
        intervalDropdown = new IntervalDropdown((intervalTimeUnit, intervalValue) -> {
            long startTime = IntervalDropdown.calculateStartTime(intervalTimeUnit, intervalValue, sessionManager.getCachedSessionData().startTime);
            flipManager.setIntervalStartTime((int) startTime);
        }, IntervalDropdown.ALL_TIME, true);
    }

    public void resetIntervalDropdownToSession() {
        intervalDropdown.resetToSession();
    }

    private JPanel metricCell(String caption, JLabel value, Color valueColor) {
        JPanel cell = UIUtilities.verticalPanel(RuneAssistColors.CARD);
        cell.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JLabel cap = RuneAssistColors.caption(caption);
        value.setFont(FontManager.getRunescapeSmallFont());
        value.setForeground(valueColor);
        cell.add(cap);
        cell.add(value);
        cell.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        return cell;
    }

    private JPanel metricCell(String caption, JLabel value, Color valueColor, Runnable onClick) {
        JPanel cell = metricCell(caption, value, valueColor);
        if (onClick == null) {
            return cell;
        }
        cell.setToolTipText("Open portfolio");
        MouseAdapter clickListener = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                onClick.run();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                cell.setBackground(RuneAssistColors.CARD.brighter());
                cell.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                cell.setBackground(RuneAssistColors.CARD);
                cell.setCursor(Cursor.getDefaultCursor());
            }
        };
        cell.addMouseListener(clickListener);
        return cell;
    }

    private JPanel buildSubInfoPanel() {
        JPanel columns = new JPanel(new GridLayout(1, 2, 6, 0));
        columns.setBackground(RuneAssistColors.CARD);

        JPanel realized = UIUtilities.verticalPanel(RuneAssistColors.CARD);
        realized.add(RuneAssistColors.kicker("REALIZED"));
        realized.add(metricCell("Flips", flipsMadeVal, ColorScheme.LIGHT_GRAY_COLOR));
        realized.add(metricCell("ROI", roiVal, UIUtilities.TOMATO));
        hourlyProfitRow = metricCell("Per hour", hourlyProfitVal, Color.WHITE);
        realized.add(hourlyProfitRow);

        JPanel unrealized = UIUtilities.verticalPanel(RuneAssistColors.CARD);
        unrealized.add(RuneAssistColors.kicker("UNREALIZED"));
        unrealized.add(metricCell("Unrealized", unrealizedProfitVal, ColorScheme.LIGHT_GRAY_COLOR, flipsDialogController::showPortfolioTab));
        unrealized.add(metricCell("Open", openFlipsVal, ColorScheme.LIGHT_GRAY_COLOR));
        unrealized.add(metricCell("Portfolio", portfolioValueVal, ColorScheme.LIGHT_GRAY_COLOR, flipsDialogController::showPortfolioTab));

        columns.add(realized);
        columns.add(unrealized);
        columns.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        return columns;
    }

    private void setupProfitAndSubInfoPanel() {
        profitAndSubInfoPanel = UIUtilities.verticalPanel(RuneAssistColors.CARD);
        profitAndSubInfoPanel.setBorder(RuneAssistColors.cardBorder());

        JPanel sessionHeader = new JPanel(new BorderLayout(0, 0));
        sessionHeader.setOpaque(false);
        sessionHeader.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        sessionHeader.add(intervalDropdown, BorderLayout.CENTER);
        sessionHeader.add(sessionResetButton, BorderLayout.EAST);
        sessionHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, sessionHeader.getPreferredSize().height));

        sessionTimeRow = new JPanel(new BorderLayout());
        sessionTimeRow.setOpaque(false);
        sessionTimeVal.setFont(FontManager.getRunescapeSmallFont());
        sessionTimeVal.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
        sessionTimeRow.add(RuneAssistColors.caption("Session"), BorderLayout.WEST);
        sessionTimeRow.add(sessionTimeVal, BorderLayout.EAST);
        sessionTimeRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));

        profitAndSubInfoPanel.add(sessionHeader);
        profitAndSubInfoPanel.add(accountDropdown);
        profitAndSubInfoPanel.add(sessionTimeRow);
        UIUtilities.addVerticalGap(profitAndSubInfoPanel, 6);

        JLabel profitKicker = RuneAssistColors.kicker("PROFIT");
        totalProfitVal.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
        totalProfitVal.setFont(FontManager.getRunescapeBoldFont().deriveFont(22f));
        totalProfitVal.setHorizontalAlignment(SwingConstants.LEFT);

        profitAndSubInfoPanel.add(profitKicker);
        UIUtilities.addVerticalGap(profitAndSubInfoPanel, 2);
        profitAndSubInfoPanel.add(totalProfitVal);

        subInfoPanel = buildSubInfoPanel();
        profitAndSubInfoPanel.add(subInfoPanel);
        profitAndSubInfoPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 240));
    }


    // called when:
    //
    // - time interval drop down changed (Swing EDT thread)
    // - session reset button pressed (Swing EDT thread)
    // - transaction processing downstream (ScheduledExecutorService)
    // - FlipTrackerV2 initialisation (ScheduledExecutorService)
    // - session stats updated (ScheduledExecutorService)
    // - plugin config changed (Client thread)
    // - page changed (Swing EDT thread)
    //
    public void refresh() {
        refresh(true, lastValidState);
    }

    public void refresh(boolean flipsMaybeChanged, boolean validLoginState) {
        if (!UIUtilities.ensureEdt(() -> refresh(flipsMaybeChanged, validLoginState))) return;
        lastValidState = validLoginState;
        if (!validLoginState) {
            totalProfitVal.setText("0 gp");
            roiVal.setText("-0.00%");
            flipsMadeVal.setText("0");
            openFlipsVal.setText("0");
            unrealizedProfitVal.setText("0 gp");
            sessionTimeVal.setText("00:00:00");
            hourlyProfitVal.setText("0 gp/hr");
            portfolioValueVal.setText("0 gp");
            flipsPanel.removeAll();
            paginator.setTotalPages(1);
            setSessionStatsVisible(false);
            accountDropdown.setVisible(false);
            return;
        }

        accountDropdown.setSelectedAccountId(flipManager.getIntervalAccount());
        accountDropdown.setVisible(true);
        accountDropdown.refresh();

        SessionData sd = sessionManager.getCachedSessionData();
        Stats stats = flipManager.getIntervalStats();
        paginator.setTotalPages(1 + stats.flipsMade / 50);
        long s = System.nanoTime();
        if (flipsMaybeChanged) {
            flipsPanel.removeAll();
            String displayName = osrsLoginManager.getPlayerDisplayName();
            flipManager.getPageFlips(paginator.getPageNumber(), 50)
                    .forEach(f -> flipsPanel.add(new FlipPanel(
                            f,
                            config,
                            () -> flipsDialogController.showVisualizeFlip(f),
                            menu -> FlipRepairMenus.addStandardActions(
                                    menu, this, f, displayName, flipHistorySyncService, true, false))));
            Integer accountId = flipManager.getIntervalAccount();
            if (accountId == null && displayName != null) {
                accountId = LocalFlipLedger.accountIdFor(displayName);
            }
            java.util.List<FlipV2> missed = accountId == null
                    ? java.util.Collections.emptyList()
                    : flipManager.getMissedFlipsForAccount(accountId);
            if (!missed.isEmpty()) {
                JLabel missedHeader = RuneAssistColors.kicker("MISSED / GHOST");
                missedHeader.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
                flipsPanel.add(missedHeader);
                int shown = 0;
                for (FlipV2 f : missed) {
                    if (shown >= 20) {
                        break;
                    }
                    boolean disappeared = PortfolioId.isDisappeared(f.getPortfolioId());
                    flipsPanel.add(new FlipPanel(
                            f,
                            config,
                            () -> flipsDialogController.showVisualizeFlip(f),
                            menu -> FlipRepairMenus.addStandardActions(
                                    menu, this, f, displayName, flipHistorySyncService, disappeared, true)));
                    shown++;
                }
            }
            // labels displayed to the user
            roiVal.setText(String.format("%.3f%%", stats.calculateRoi() * 100));
            roiVal.setForeground(UIUtilities.getProfitColor(stats.profit, config));
            int openCount = flipManager.countOpenInInterval();
            int closedCount = Math.max(0, stats.flipsMade - openCount);
            flipsMadeVal.setText(String.format("%d", closedCount));
            openFlipsVal.setText(String.format("%d", openCount));
            totalProfitVal.setText(UIUtilities.formatProfit(stats.profit));
            totalProfitVal.setForeground(UIUtilities.getProfitColor(stats.profit, config));
            totalProfitVal.setToolTipText("Realized profit from closed sells. Open buys stay at 0 until you sell.");
            log.debug("populating flips took {}ms", (System.nanoTime() - s) / 1000_000);
        }

        PortfolioSummaryData summaryData = portfolioStateRS.get().getSummaryData();
        long portfolioValue = summaryData.getPortfolioMarketValue();
        portfolioValueVal.setText(UIUtilities.quantityToRSDecimalStack(Math.abs(portfolioValue), true) + " gp");
        long unrealizedProfit = summaryData.getUnrealizedProfit();
        unrealizedProfitVal.setText(UIUtilities.formatProfit(unrealizedProfit));
        unrealizedProfitVal.setForeground(UIUtilities.getProfitColor(unrealizedProfit, config));

        long seconds = Math.max(0L, sd.durationMillis / 1000);
        sessionTimeVal.setText(String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60));
        float hoursFloat = (((float) seconds) / 3600.0f);
        long hourlyProfit = hoursFloat == 0 ? 0 : (long) (stats.profit / hoursFloat);
        hourlyProfitVal.setText(UIUtilities.formatProfitWithoutGp(hourlyProfit) + " gp/hr");
        hourlyProfitVal.setForeground(UIUtilities.getProfitColor(hourlyProfit, config));
        setSessionStatsVisible(true);
    }

    private void setSessionStatsVisible(boolean visible) {
        if (sessionTimeRow != null) {
            sessionTimeRow.setVisible(visible);
        }
        if (hourlyProfitRow != null) {
            hourlyProfitRow.setVisible(visible);
        }
    }
}
