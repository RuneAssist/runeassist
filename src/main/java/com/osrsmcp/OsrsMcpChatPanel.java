package com.osrsmcp;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * RuneAssist in-panel AI chat. Drives {@link CompanionAgent} and renders the
 * conversation as a chat app (avatars + soft font). Provider/key settings live in a
 * collapsible section here so the user never has to leave the panel.
 *
 * <p>The agent fires its callbacks on a worker thread, so every
 * {@link CompanionAgent.Listener} method marshals onto the Swing EDT.
 */
@Slf4j
@Singleton
public class OsrsMcpChatPanel extends PluginPanel
{
    // RuneAssist accent — deliberately distinct from the MCP panel's orange.
    private static final Color ACCENT      = new Color(124, 138, 255); // indigo
    private static final Color USER_ACCENT = new Color(0, 170, 150);   // teal
    private static final Color ERROR_COLOR = ColorScheme.PROGRESS_ERROR_COLOR;
    private static final Color PANEL_BG     = ColorScheme.DARK_GRAY_COLOR;
    private static final Color FIELD_BG     = ColorScheme.DARKER_GRAY_COLOR;
    private static final Color META_COLOR   = ColorScheme.MEDIUM_GRAY_COLOR;
    private static final Color WARN         = ColorScheme.BRAND_ORANGE;   // modify / abort
    private static final Color GOOD_C       = new Color(0, 200, 100);     // profit / done
    private static final Color SELL_C       = new Color(0, 160, 190);     // sell action
    private static final int    BODY_WIDTH  = 158; // px; sized to the panel viewport so text never clips

    private static final Font NAME_FONT = new Font("SansSerif", Font.BOLD, 11);
    private static final Font BODY_FONT = new Font("SansSerif", Font.PLAIN, 12);
    private static final Font META_FONT = new Font("SansSerif", Font.PLAIN, 10);

    @Inject private CompanionAgent agent;
    @Inject private OsrsMcpConfig config;
    @Inject private ConfigManager configManager;
    @Inject private TelemetryService telemetry;
    @Inject private TaskService taskService;
    @Inject private PlayerDataService playerDataService;
    @Inject private FlipTrackerService flipTracker;
    @Inject private SharedFlipState sharedFlip;

    // Tabbed views: only one shows at a time so chat never renders under the other tabs.
    private static final String V_CHAT = "chat", V_FLIPS = "flips", V_GOALS = "goals", V_SETTINGS = "settings";
    private final JPanel      views      = new JPanel(new java.awt.CardLayout());
    private String            currentView = V_CHAT;
    private JPanel            composer;

    private final JPanel      messages   = new JPanel();
    private final JScrollPane scroll;
    private final JTextArea   input       = new JTextArea(2, 1);
    private final JButton     sendBtn     = new JButton("Send");
    private final JButton     newChatBtn  = new JButton("New chat");
    private final JButton     chatBtn     = new JButton("Chat");
    private final JButton     settingsBtn = new JButton("Settings");
    private final JButton     goalsBtn    = new JButton("Goals");
    private final JPanel      goalsPanel  = new JPanel();
    private final JLabel      providerLbl = new JLabel();

    // Flips view (the flip model as its own tab)
    private final JButton     flipsBtn     = new JButton("Flips");
    private final JPanel      flipsPanel   = new JPanel();
    private final JButton     findFlipsBtn = new JButton("Suggest next flip");
    private final JPanel      flipTopCard  = new JPanel();
    private final com.osrsmcp.graph.PriceGraphPanel graphPanel =
        new com.osrsmcp.graph.PriceGraphPanel(new com.google.gson.Gson());
    private final JLabel      flipStatus   = new JLabel(" ");
    // Action-card state: the last fetched suggestions, items the user skipped this session,
    // and a live snapshot of GE offers (fed by the plugin) so the card can say BUY/WAIT/MODIFY.
    private java.util.List<java.util.Map<String, Object>> lastSuggestions = null;
    private final java.util.Set<Integer> skipped = new java.util.HashSet<>();
    private volatile java.util.Map<Integer, long[]> geOffers = new java.util.HashMap<>();
    private volatile int currentPickId = -1; // item id of the shown suggestion
    private volatile long lastAutoRefresh = 0; // debounce auto-refresh on holdings changes
    private final JLabel      profitLbl    = new JLabel(" ");
    private final JPanel      flipLog      = new JPanel();
    private PortfolioWindow   portfolioWindow; // lazily created popup
    private FlipsHistoryWindow historyWindow; // lazily created popup

    // Settings controls
    private final JPanel                     settingsPanel = new JPanel();
    private final JComboBox<LlmProviderType> providerCombo =
        new JComboBox<>(new LlmProviderType[]{ LlmProviderType.ANTHROPIC, LlmProviderType.OPENAI, LlmProviderType.DEEPSEEK });
    private final JPasswordField keyField   = new JPasswordField();
    private final JTextField     modelField = new JTextField();
    private final JLabel         savedLbl   = new JLabel(" ");

    private final JLabel statusLbl = new JLabel(" ");

    private volatile boolean busy = false;
    private List<String> turnTools = new ArrayList<>();
    private JPanel lastMsg = null; // most recent message block, so the meta line tucks under it
    private String currentQuestion = "";  // for the advice->outcome telemetry record
    private int answerChars = 0;

    public OsrsMcpChatPanel()
    {
        super(false);
        setLayout(new BorderLayout());
        setBackground(PANEL_BG);

        add(buildHeader(), BorderLayout.NORTH);

        messages.setLayout(new BoxLayout(messages, BoxLayout.Y_AXIS));
        messages.setBackground(PANEL_BG);
        messages.setBorder(new EmptyBorder(8, 10, 8, 10));

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(PANEL_BG);
        wrapper.add(messages, BorderLayout.NORTH);

        scroll = new JScrollPane(wrapper,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setBackground(PANEL_BG);

        // One card per tab; only the selected one is shown, so tabs never stack.
        views.setBackground(PANEL_BG);
        views.add(scroll, V_CHAT);
        views.add(cardScroll(buildFlipsPanel()), V_FLIPS);
        views.add(cardScroll(buildGoalsPanel()), V_GOALS);
        views.add(cardScroll(buildSettingsPanel()), V_SETTINGS);
        add(views, BorderLayout.CENTER);

        composer = buildComposer();
        add(composer, BorderLayout.SOUTH);

        addMessage("RuneAssist", ACCENT, "R", "Hi! Ask me anything OSRS.", ACCENT);
        selectView(V_CHAT);
    }

    /** Wrap a tab panel so its content hugs the top and scrolls if it's taller than the view. */
    private JScrollPane cardScroll(JPanel content)
    {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(PANEL_BG);
        wrap.add(content, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(wrap,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.setBackground(PANEL_BG);
        return sp;
    }

    /** Switch tabs: show one view, keep the composer only on Chat, refresh what's shown. */
    private void selectView(String view)
    {
        currentView = view;
        ((java.awt.CardLayout) views.getLayout()).show(views, view);
        composer.setVisible(V_CHAT.equals(view));
        setTabActive(chatBtn,     V_CHAT.equals(view));
        setTabActive(flipsBtn,    V_FLIPS.equals(view));
        setTabActive(goalsBtn,    V_GOALS.equals(view));
        setTabActive(settingsBtn, V_SETTINGS.equals(view));
        if (V_GOALS.equals(view)) refreshGoals();
        else if (V_FLIPS.equals(view)) { refreshFlipLog(); refreshFlips(); }
        else if (V_SETTINGS.equals(view)) { syncSettingsFromConfig(); savedLbl.setText(" "); }
        revalidate();
        repaint();
    }

    private void setTabActive(JButton b, boolean active)
    {
        b.setBackground(active ? ACCENT : ColorScheme.MEDIUM_GRAY_COLOR);
        b.setForeground(active ? Color.BLACK : ColorScheme.LIGHT_GRAY_COLOR);
    }

    // ── header (title, provider, actions, settings) ──────────────────────────────

    private JPanel buildHeader()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(PANEL_BG);
        p.setBorder(new EmptyBorder(10, 10, 6, 10));

        // Title row: name on the left, a small "New chat" on the right.
        JPanel titleRow = new JPanel();
        titleRow.setLayout(new BoxLayout(titleRow, BoxLayout.X_AXIS));
        titleRow.setBackground(PANEL_BG);
        titleRow.setAlignmentX(LEFT_ALIGNMENT);
        JLabel title = new JLabel("RuneAssist");
        title.setForeground(Color.WHITE);
        title.setFont(new Font("SansSerif", Font.BOLD, 16));
        titleRow.add(title);
        titleRow.add(Box.createHorizontalGlue());
        styleButton(newChatBtn, false);
        newChatBtn.addActionListener(e -> { onNewChat(); selectView(V_CHAT); });
        titleRow.add(newChatBtn);
        p.add(titleRow);
        p.add(Box.createVerticalStrut(3));

        providerLbl.setFont(META_FONT);
        providerLbl.setForeground(META_COLOR);
        providerLbl.setAlignmentX(LEFT_ALIGNMENT);
        p.add(providerLbl);
        p.add(Box.createVerticalStrut(6));

        // Mutually-exclusive tabs: Chat / Flips / Goals / Settings.
        styleButton(chatBtn, false);     chatBtn.addActionListener(e -> selectView(V_CHAT));
        styleButton(flipsBtn, false);    flipsBtn.addActionListener(e -> selectView(V_FLIPS));
        styleButton(goalsBtn, false);    goalsBtn.addActionListener(e -> selectView(V_GOALS));
        styleButton(settingsBtn, false); settingsBtn.addActionListener(e -> selectView(V_SETTINGS));
        JPanel row = new JPanel(new GridLayout(0, 2, 6, 6));
        row.setBackground(PANEL_BG);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
        row.add(chatBtn);
        row.add(flipsBtn);
        row.add(goalsBtn);
        row.add(settingsBtn);
        p.add(row);
        return p;
    }

    private JPanel buildSettingsPanel()
    {
        settingsPanel.setLayout(new BoxLayout(settingsPanel, BoxLayout.Y_AXIS));
        settingsPanel.setBackground(FIELD_BG);
        settingsPanel.setAlignmentX(LEFT_ALIGNMENT);
        settingsPanel.setBorder(new CompoundBorder(
            new MatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(8, 8, 8, 8)));

        settingsPanel.add(Box.createVerticalStrut(4));
        settingsPanel.add(fieldLabel("Provider"));
        providerCombo.setAlignmentX(LEFT_ALIGNMENT);
        providerCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        providerCombo.setFont(BODY_FONT);
        providerCombo.addActionListener(e -> loadKeyForSelectedProvider());
        settingsPanel.add(providerCombo);

        settingsPanel.add(Box.createVerticalStrut(6));
        settingsPanel.add(fieldLabel("API key"));
        styleField(keyField);
        settingsPanel.add(keyField);

        settingsPanel.add(Box.createVerticalStrut(6));
        settingsPanel.add(fieldLabel("Model (optional)"));
        styleField(modelField);
        settingsPanel.add(modelField);

        settingsPanel.add(Box.createVerticalStrut(8));
        JButton save = new JButton("Save");
        styleButton(save, true);
        save.addActionListener(e -> onSaveSettings());
        settingsPanel.add(save);

        savedLbl.setFont(META_FONT);
        savedLbl.setForeground(USER_ACCENT);
        savedLbl.setAlignmentX(LEFT_ALIGNMENT);
        settingsPanel.add(Box.createVerticalStrut(4));
        settingsPanel.add(savedLbl);

        JLabel hint = new JLabel("<html><body style='width:" + BODY_WIDTH + "px'>"
            + "Your key is stored locally by RuneLite and used only to call the provider "
            + "you pick. DeepSeek is the cheapest to run.</body></html>");
        hint.setFont(META_FONT);
        hint.setForeground(META_COLOR);
        hint.setAlignmentX(LEFT_ALIGNMENT);
        settingsPanel.add(Box.createVerticalStrut(6));
        settingsPanel.add(hint);
        return settingsPanel;
    }

    private JPanel buildGoalsPanel()
    {
        goalsPanel.setLayout(new BoxLayout(goalsPanel, BoxLayout.Y_AXIS));
        goalsPanel.setBackground(FIELD_BG);
        goalsPanel.setAlignmentX(LEFT_ALIGNMENT);
        goalsPanel.setBorder(new CompoundBorder(
            new MatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(8, 8, 8, 8)));
        return goalsPanel;
    }

    private void refreshGoals()
    {
        goalsPanel.removeAll();
        java.util.List<java.util.Map<String, Object>> active = new java.util.ArrayList<>();
        java.util.List<java.util.Map<String, Object>> done = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> t : taskService.snapshot())
            (Boolean.TRUE.equals(t.get("done")) ? done : active).add(t);

        if (active.isEmpty() && done.isEmpty())
        {
            JLabel empty = new JLabel("<html><body style='width:" + BODY_WIDTH + "px'>"
                + "No goals yet. Ask RuneAssist to add one, e.g. \"add a goal: 70 Agility\".</body></html>");
            empty.setFont(META_FONT);
            empty.setForeground(META_COLOR);
            empty.setAlignmentX(LEFT_ALIGNMENT);
            goalsPanel.add(empty);
        }
        for (java.util.Map<String, Object> t : active) goalsPanel.add(goalRow(t, false));
        for (java.util.Map<String, Object> t : done)   goalsPanel.add(goalRow(t, true));

        if (!done.isEmpty())
        {
            JButton clearDone = new JButton("Clear completed");
            styleButton(clearDone, false);
            clearDone.addActionListener(e -> {
                for (java.util.Map<String, Object> t : done)
                    taskService.remove(((Number) t.get("id")).longValue());
                refreshGoals();
            });
            goalsPanel.add(Box.createVerticalStrut(6));
            goalsPanel.add(clearDone);
        }
        goalsPanel.revalidate();
        goalsPanel.repaint();
    }

    private JComponent goalRow(java.util.Map<String, Object> t, boolean done)
    {
        long id = ((Number) t.get("id")).longValue();
        boolean auto = Boolean.TRUE.equals(t.get("auto"));
        String suffix = auto ? "  (" + t.get("metric") + " " + t.get("target") + ")" : "";

        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBackground(FIELD_BG);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setBorder(new EmptyBorder(2, 0, 2, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        JCheckBox cb = new JCheckBox();
        cb.setSelected(done);
        cb.setEnabled(!done);
        cb.setBackground(FIELD_BG);
        cb.addActionListener(e -> { taskService.complete(id); refreshGoals(); });

        JLabel lbl = new JLabel("<html><body style='width:150px'>"
            + toHtml(String.valueOf(t.get("text")) + suffix) + "</body></html>");
        lbl.setFont(META_FONT);
        lbl.setForeground(done ? META_COLOR : Color.WHITE);
        lbl.setAlignmentX(LEFT_ALIGNMENT);

        row.add(cb);
        row.add(Box.createHorizontalStrut(4));
        row.add(lbl);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    // ── flips section (the flip model, collapsible like Goals/Settings) ───────────

    private JPanel buildFlipsPanel()
    {
        flipsPanel.setLayout(new BoxLayout(flipsPanel, BoxLayout.Y_AXIS));
        flipsPanel.setBackground(FIELD_BG);
        flipsPanel.setAlignmentX(LEFT_ALIGNMENT);
        flipsPanel.setBorder(new CompoundBorder(
            new MatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(8, 8, 8, 8)));
        // Profit tracker header (session / all-time) + recent flips, Flipping-Copilot style.
        profitLbl.setFont(NAME_FONT);
        profitLbl.setForeground(Color.WHITE);
        profitLbl.setAlignmentX(LEFT_ALIGNMENT);
        flipsPanel.add(profitLbl);

        flipLog.setLayout(new BoxLayout(flipLog, BoxLayout.Y_AXIS));
        flipLog.setBackground(FIELD_BG);
        flipLog.setAlignmentX(LEFT_ALIGNMENT);
        flipsPanel.add(Box.createVerticalStrut(2));
        flipsPanel.add(flipLog);

        JButton portfolioBtn = new JButton("Portfolio");
        styleButton(portfolioBtn, false);
        portfolioBtn.addActionListener(e -> openPortfolio());
        JButton historyBtn = new JButton("History");
        styleButton(historyBtn, false);
        historyBtn.addActionListener(e -> openHistory());
        JButton resetBtn = new JButton("Reset flips");
        styleButton(resetBtn, false);
        resetBtn.addActionListener(e -> { flipTracker.reset(); refreshFlipLog(); });
        JPanel btnRow = new JPanel(new GridLayout(0, 3, 6, 0));
        btnRow.setBackground(FIELD_BG);
        btnRow.setAlignmentX(LEFT_ALIGNMENT);
        btnRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        btnRow.add(portfolioBtn);
        btnRow.add(historyBtn);
        btnRow.add(resetBtn);
        flipsPanel.add(Box.createVerticalStrut(4));
        flipsPanel.add(btnRow);

        JPanel sep = new JPanel();
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        sep.setAlignmentX(LEFT_ALIGNMENT);
        flipsPanel.add(Box.createVerticalStrut(8));
        flipsPanel.add(sep);
        flipsPanel.add(Box.createVerticalStrut(8));

        // One button: find the single best flip sized to your current coins (auto).
        findFlipsBtn.setText("Suggest next flip");
        styleButton(findFlipsBtn, true);
        findFlipsBtn.addActionListener(e -> refreshFlips());
        flipsPanel.add(findFlipsBtn);

        flipStatus.setFont(META_FONT);
        flipStatus.setForeground(META_COLOR);
        flipStatus.setAlignmentX(LEFT_ALIGNMENT);
        flipsPanel.add(Box.createVerticalStrut(4));
        flipsPanel.add(flipStatus);

        // The single recommended next flip (no long list).
        flipTopCard.setLayout(new BoxLayout(flipTopCard, BoxLayout.Y_AXIS));
        flipTopCard.setBackground(ColorScheme.DARK_GRAY_COLOR);
        flipTopCard.setAlignmentX(LEFT_ALIGNMENT);
        flipTopCard.setVisible(false);
        flipsPanel.add(Box.createVerticalStrut(4));
        flipsPanel.add(flipTopCard);

        // Price-history graph for the suggested item (public wiki data via our server).
        graphPanel.setAlignmentX(LEFT_ALIGNMENT);
        flipsPanel.add(Box.createVerticalStrut(4));
        flipsPanel.add(graphPanel);

        JLabel hint = new JLabel("<html><body style='width:" + BODY_WIDTH + "px'>"
            + "Display-only. Budget is your coins; advice, not automation.</body></html>");
        hint.setFont(META_FONT);
        hint.setForeground(META_COLOR);
        hint.setAlignmentX(LEFT_ALIGNMENT);
        flipsPanel.add(Box.createVerticalStrut(6));
        flipsPanel.add(hint);
        return flipsPanel;
    }

    /** Open (creating on first use) the Flipping-Copilot-style Portfolio popup. */
    private void openPortfolio()
    {
        if (portfolioWindow == null)
            portfolioWindow = new PortfolioWindow(flipTracker, playerDataService);
        // Cash tied up in active BUY offers: price * unfilled qty, summed.
        long cashInBuyOffers = 0;
        for (long[] o : geOffers.values())
            if (o.length >= 5 && o[0] == 1) cashInBuyOffers += o[1] * Math.max(0, o[3] - o[2]);
        portfolioWindow.open(cashInBuyOffers);
    }

    /** Open (creating on first use) the Flips-history popup — every completed flip, sortable. */
    private void openHistory()
    {
        if (historyWindow == null)
            historyWindow = new FlipsHistoryWindow(flipTracker);
        historyWindow.open();
    }

    /** Refresh the profit header + recent-flip log from the tracker (client-free snapshot). */
    private void refreshFlipLog()
    {
        java.util.Map<String, Object> snap;
        try { snap = flipTracker.snapshot(); }
        catch (Exception ex) { snap = null; }
        flipLog.removeAll();
        if (snap == null)
        {
            profitLbl.setText("Flips: —");
            flipLog.revalidate(); flipLog.repaint();
            return;
        }
        long session = num(snap.get("session_profit"));
        long allTime = num(snap.get("all_time_profit"));
        profitLbl.setText("Session " + signed(session) + "  ·  Total " + signed(allTime));
        profitLbl.setForeground(session >= 0 ? new Color(0, 200, 100) : ERROR_COLOR);

        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> recent =
            (java.util.List<java.util.Map<String, Object>>) snap.get("recent");
        if (recent == null || recent.isEmpty())
        {
            JLabel none = new JLabel("No completed flips yet this account.");
            none.setFont(META_FONT);
            none.setForeground(META_COLOR);
            none.setAlignmentX(LEFT_ALIGNMENT);
            flipLog.add(none);
        }
        else
        {
            int shown = 0;
            for (java.util.Map<String, Object> f : recent)
            {
                if (shown++ >= 8) break; // keep the panel compact
                long profit = num(f.get("profit"));
                JLabel row = new JLabel(fmt(f.get("qty")) + "x " + f.get("name")
                    + "  " + signed(profit));
                row.setFont(META_FONT);
                row.setForeground(profit >= 0 ? ColorScheme.LIGHT_GRAY_COLOR : ERROR_COLOR);
                row.setAlignmentX(LEFT_ALIGNMENT);
                row.setToolTipText(f.get("name") + ": bought " + fmt(f.get("buy_at"))
                    + ", sold " + fmt(f.get("sell_at")) + ", tax " + fmt(f.get("tax")));
                flipLog.add(row);
            }
        }
        flipLog.revalidate(); flipLog.repaint();
    }

    private static long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0; }

    private static String signed(long n) { return (n >= 0 ? "+" : "-") + fmt(Math.abs(n)) + " gp"; }

    private void refreshFlips() { refreshFlips(true); }

    private void refreshFlips(boolean resetSkips)
    {
        long coins = playerDataService.cachedCoins();
        // Fall back to a sensible budget if coins aren't cached yet (e.g. logged out).
        final long capital = coins > 0 ? coins : 1_000_000L;
        if (resetSkips) skipped.clear(); // a manual fetch starts the candidate list over
        findFlipsBtn.setEnabled(false);
        flipStatus.setText(coins > 0 ? "Budget " + fmt(capital) + " · finding…" : "Finding…");
        // Held positions to consider selling (client-free snapshot, safe off-thread).
        java.util.List<java.util.Map<String, Object>> held = null;
        try
        {
            @SuppressWarnings("unchecked")
            java.util.List<java.util.Map<String, Object>> op =
                (java.util.List<java.util.Map<String, Object>>) flipTracker.snapshot().get("open_positions");
            held = op;
        }
        catch (Exception ignored) {}
        final java.util.List<java.util.Map<String, Object>> heldPos = held;

        new Thread(() ->
        {
            java.util.Map<String, Object> res;
            // Ask for a handful so we can skip items whose 4h buy limit you've maxed.
            try { res = playerDataService.buildFlipSuggestions(capital, 0, 0, 8); }
            catch (Exception ex) { res = null; }
            java.util.List<java.util.Map<String, Object>> sells;
            try { sells = playerDataService.buildHeldSellSuggestions(heldPos); }
            catch (Exception ex) { sells = null; }
            final java.util.Map<String, Object> r = res;
            final java.util.List<java.util.Map<String, Object>> fsells = sells;
            SwingUtilities.invokeLater(() -> renderFlips(r, fsells));
        }, "runeassist-flips").start();
    }

    @SuppressWarnings("unchecked")
    private void renderFlips(java.util.Map<String, Object> r, java.util.List<java.util.Map<String, Object>> sells)
    {
        findFlipsBtn.setEnabled(true);
        java.util.List<java.util.Map<String, Object>> buys =
            (r != null && r.get("error") == null)
                ? (java.util.List<java.util.Map<String, Object>>) r.get("suggestions") : null;

        // Order: profitable sells first (lock gains), then buys, then loss-cutting sells last
        // (surfaced but never leading — you decide whether to cut).
        java.util.List<java.util.Map<String, Object>> profitableSells = new java.util.ArrayList<>();
        java.util.List<java.util.Map<String, Object>> lossSells = new java.util.ArrayList<>();
        if (sells != null) for (java.util.Map<String, Object> s : sells)
            (Boolean.TRUE.equals(s.get("loss")) ? lossSells : profitableSells).add(s);
        java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
        list.addAll(profitableSells);
        if (buys != null) list.addAll(buys);
        list.addAll(lossSells);

        if (list.isEmpty())
        {
            flipStatus.setText(r != null && r.get("error") != null
                ? String.valueOf(r.get("error")) : "No flips right now.");
            flipTopCard.setVisible(false);
            revalidate(); repaint();
            return;
        }
        long coins = playerDataService.cachedCoins();
        flipStatus.setText(coins > 0 ? "Budget " + fmt(coins) : "Suggested flip");
        renderTopPick(list); // single best pick (sells prioritised), no list
        refreshFlipLog();
        revalidate(); repaint();
    }

    /** Live GE offers pushed from the plugin (client thread) so the card can show state. */
    public void setGeOffers(java.util.Map<Integer, long[]> offers)
    {
        SwingUtilities.invokeLater(() ->
        {
            geOffers = offers != null ? offers : new java.util.HashMap<>();
            if (V_FLIPS.equals(currentView) && lastSuggestions != null) rerenderCard();
        });
    }

    /**
     * The player just placed an offer for {@code itemId}. If it's the item we were
     * suggesting, drop it and surface the next flip so the card keeps moving. EDT-safe.
     */
    public void onOfferPlaced(int itemId)
    {
        SwingUtilities.invokeLater(() ->
        {
            if (itemId <= 0 || itemId != currentPickId || lastSuggestions == null) return;
            skipped.add(itemId);
            rerenderCard();
        });
    }

    /**
     * Holdings changed (a buy or sell completed), so newly-held stock may now be worth
     * selling. Recompute the suggestion, keeping the user's skips. Debounced; EDT-safe.
     */
    public void onHoldingsChanged()
    {
        SwingUtilities.invokeLater(() ->
        {
            if (lastSuggestions == null) return; // Flips not used yet
            long now = System.currentTimeMillis();
            if (now - lastAutoRefresh < 8000) return;
            lastAutoRefresh = now;
            refreshFlips(false);
        });
    }

    /** Store the fetched suggestions and (re)draw the single action card. */
    private void renderTopPick(java.util.List<java.util.Map<String, Object>> list)
    {
        lastSuggestions = list;
        rerenderCard();
    }

    /** Choose the best suggestion not skipped and not buy-limit-maxed, then render the card. */
    private void rerenderCard()
    {
        flipTopCard.removeAll();
        java.util.Map<String, Object> pick = choosePick();
        if (pick == null)
        {
            currentPickId = -1;
            sharedFlip.clear();
            graphPanel.clear();
            flipTopCard.setVisible(false);
            flipTopCard.revalidate(); flipTopCard.repaint();
            return;
        }
        currentPickId = (int) num(pick.get("id"));
        publishPick(pick);
        flipTopCard.add(buildActionCard(pick));
        flipTopCard.setVisible(true);
        flipTopCard.revalidate(); flipTopCard.repaint();
    }

    private java.util.Map<String, Object> choosePick()
    {
        if (lastSuggestions == null || lastSuggestions.isEmpty()) return null;
        int activeOffers = geOffers.size();
        int slotBudget = config != null ? Math.max(1, Math.min(8, config.geSlots())) : 8;
        boolean slotsFull = activeOffers >= slotBudget;

        java.util.Map<String, Object> fallback = null; // best non-actionable, if nothing else
        for (java.util.Map<String, Object> s : lastSuggestions)
        {
            int id = (int) num(s.get("id"));
            if (skipped.contains(id)) continue;

            String verb = actionVerb(s);
            boolean needsNewSlot = ("BUY".equals(verb) || "SELL".equals(verb)) && !geOffers.containsKey(id);

            // Non-actionable right now: an offer already filling fine, or already done.
            if ("WAIT".equals(verb) || "DONE".equals(verb)) { if (fallback == null) fallback = s; continue; }
            // No free slot to place a new offer -> can't act on this one now.
            if (needsNewSlot && slotsFull) { if (fallback == null) fallback = s; continue; }

            int lim = (int) num(s.get("ge_limit"));
            if ("BUY".equals(verb) && flipTracker.limitRemaining(id, lim) == 0)
            { if (fallback == null) fallback = s; continue; } // buy-limit maxed

            return s; // actionable
        }
        return fallback; // nothing actionable — show the top item so the user still sees status
    }

    /** The action the card would resolve for a candidate (BUY/SELL/WAIT/MODIFY/DONE). */
    private String actionVerb(java.util.Map<String, Object> pick)
    {
        int id = (int) num(pick.get("id"));
        long buyAt = num(pick.get("buy_at")), sellAt = num(pick.get("sell_at"));
        long[] off = geOffers.get(id);
        if (off != null)
        {
            boolean buy = off[0] == 1; long price = off[1]; boolean filling = off[4] == 1;
            if (buy && filling) return price < buyAt ? "MODIFY" : "WAIT";
            if (buy) return "SELL";                 // buy complete -> collect & sell
            if (filling) return price > sellAt ? "MODIFY" : "WAIT";
            return "DONE";
        }
        return openPosition(id) > 0 ? "SELL" : "BUY";
    }

    /** Flipping-Copilot-style card: an action badge (BUY/WAIT/MODIFY/SELL) + what to do now. */
    private JPanel buildActionCard(java.util.Map<String, Object> pick)
    {
        int id       = (int) num(pick.get("id"));
        long buyAt   = num(pick.get("buy_at"));
        long sellAt  = num(pick.get("sell_at"));
        long qty     = num(pick.get("suggested_qty"));
        long profit  = num(pick.get("projected_profit"));
        long marginEa= num(pick.get("margin_post_tax"));
        Object pct   = pick.get("margin_pct");
        int lim      = (int) num(pick.get("ge_limit"));
        long[] off   = geOffers.get(id);
        long openQty = openPosition(id);

        // Decide the action + instruction from live offer / holdings.
        String badge; Color badgeColor; String line1; Color line1Color = ColorScheme.LIGHT_GRAY_COLOR;
        boolean showProfit = false;
        if (off != null)
        {
            boolean buy = off[0] == 1; long price = off[1], sold = off[2], total = off[3];
            boolean filling = off[4] == 1;
            if (buy && filling)
            {
                if (price < buyAt) { badge = "MODIFY"; badgeColor = WARN;
                    line1 = "Raise buy to " + fmt(buyAt) + " (bid " + fmt(price) + " is low)"; line1Color = WARN; }
                else { badge = "WAIT"; badgeColor = META_COLOR;
                    line1 = "Buying " + fmt(sold) + "/" + fmt(total) + " @ " + fmt(price); }
            }
            else if (buy) // buy done / cancelled -> time to sell
            {
                badge = "SELL"; badgeColor = SELL_C;
                line1 = "Collect, then sell @ " + fmt(sellAt); line1Color = Color.WHITE;
            }
            else if (filling) // selling
            {
                if (price > sellAt) { badge = "MODIFY"; badgeColor = WARN;
                    line1 = "Lower sell to " + fmt(sellAt) + " (ask " + fmt(price) + " is high)"; line1Color = WARN; }
                else { badge = "WAIT"; badgeColor = META_COLOR;
                    line1 = "Selling " + fmt(sold) + "/" + fmt(total) + " @ " + fmt(price); }
            }
            else { badge = "DONE"; badgeColor = GOOD_C; line1 = "Sold — pick the next flip"; }
        }
        else if (openQty > 0) // hold stock, no active offer -> sell it
        {
            boolean loss = Boolean.TRUE.equals(pick.get("loss"));
            badge = "SELL"; badgeColor = loss ? WARN : SELL_C;
            line1 = "Sell " + fmt(openQty) + " @ " + fmt(sellAt) + (loss ? "  (cut loss)" : "");
            line1Color = Color.WHITE;
        }
        else // nothing placed, don't hold -> buy
        {
            badge = "BUY"; badgeColor = ACCENT;
            line1 = "Buy " + fmt(qty) + " @ " + fmt(buyAt); line1Color = Color.WHITE;
            showProfit = true;
        }

        // Publish the resolved action so the on-GE overlay mirrors the card (no contradiction).
        sharedFlip.action = badge;
        sharedFlip.actionLine = line1;

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(FIELD_BG);
        card.setAlignmentX(LEFT_ALIGNMENT);
        card.setBorder(new CompoundBorder(
            new MatteBorder(2, 2, 2, 2, badgeColor), new EmptyBorder(6, 8, 6, 8)));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 168));

        // Badge + item name on one row.
        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
        head.setBackground(FIELD_BG);
        head.setAlignmentX(LEFT_ALIGNMENT);
        head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        JLabel badgeLbl = new JLabel(" " + badge + " ");
        badgeLbl.setFont(new Font("SansSerif", Font.BOLD, 11));
        badgeLbl.setForeground(Color.BLACK);
        badgeLbl.setOpaque(true);
        badgeLbl.setBackground(badgeColor);
        head.add(badgeLbl);
        head.add(Box.createHorizontalStrut(6));
        JLabel nameLbl = new JLabel(String.valueOf(pick.get("name")));
        nameLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        nameLbl.setForeground(Color.WHITE);
        head.add(nameLbl);
        head.add(Box.createHorizontalGlue());
        card.add(head);
        card.add(Box.createVerticalStrut(4));

        JLabel l1 = new JLabel(line1);
        l1.setFont(new Font("SansSerif", Font.BOLD, 12));
        l1.setForeground(line1Color);
        l1.setAlignmentX(LEFT_ALIGNMENT);
        card.add(l1);

        // Always show the target buy→sell so the numbers are to hand.
        JLabel prices = new JLabel("Buy " + fmt(buyAt) + "  →  Sell " + fmt(sellAt)
            + "   (" + (marginEa >= 0 ? "+" : "") + fmt(marginEa) + " ea, " + pct + "%)");
        prices.setFont(META_FONT);
        prices.setForeground(META_COLOR);
        prices.setAlignmentX(LEFT_ALIGNMENT);
        card.add(prices);

        if (showProfit)
        {
            JLabel p = new JLabel("≈ " + signed(profit) + " profit");
            p.setFont(NAME_FONT);
            p.setForeground(GOOD_C);
            p.setAlignmentX(LEFT_ALIGNMENT);
            card.add(p);
        }

        int left = flipTracker.limitRemaining(id, lim);
        if (lim > 0)
        {
            JLabel ll = new JLabel("4h limit " + fmt(left) + "/" + fmt(lim) + " left");
            ll.setFont(META_FONT);
            ll.setForeground(left == 0 ? WARN : META_COLOR);
            ll.setAlignmentX(LEFT_ALIGNMENT);
            card.add(ll);
        }

        Object flags = pick.get("flags");
        boolean risky = flags instanceof java.util.List && !((java.util.List<?>) flags).isEmpty();
        if (risky)
        {
            @SuppressWarnings("unchecked")
            String fstr = String.join(", ", (java.util.List<String>) flags);
            JLabel fl = new JLabel("⚠ " + fstr);
            fl.setFont(META_FONT);
            fl.setForeground(WARN);
            fl.setAlignmentX(LEFT_ALIGNMENT);
            card.add(fl);
        }

        // Skip / Abort -> drop this item and surface the next candidate.
        JButton skip = new JButton(risky ? "Abort — next flip" : "Skip");
        styleButton(skip, false);
        skip.addActionListener(e -> { skipped.add(id); rerenderCard(); });
        card.add(Box.createVerticalStrut(4));
        card.add(skip);
        return card;
    }

    /** Publish the chosen pick to the on-GE overlay so it can draw on top of the GE window. */
    private void publishPick(java.util.Map<String, Object> pick)
    {
        int id  = (int) num(pick.get("id"));
        int lim = (int) num(pick.get("ge_limit"));
        double pct = pick.get("margin_pct") instanceof Number
            ? ((Number) pick.get("margin_pct")).doubleValue() : 0;
        sharedFlip.sell = "sell".equals(pick.get("side"));
        sharedFlip.set(id, String.valueOf(pick.get("name")),
            num(pick.get("buy_at")), num(pick.get("sell_at")), num(pick.get("suggested_qty")),
            num(pick.get("projected_profit")), pct, lim, flipTracker.limitRemaining(id, lim));
        try { graphPanel.setItem(config.graphServerUrl(), id, String.valueOf(pick.get("name"))); }
        catch (Exception ignored) {}
    }

    /** Open buy quantity for an item from the tracker (stock you hold, ready to sell). */
    private long openPosition(int itemId)
    {
        try
        {
            java.util.Map<String, Object> snap = flipTracker.snapshot();
            @SuppressWarnings("unchecked")
            java.util.List<java.util.Map<String, Object>> open =
                (java.util.List<java.util.Map<String, Object>>) snap.get("open_positions");
            if (open != null) for (java.util.Map<String, Object> p : open)
                if ((int) num(p.get("item_id")) == itemId) return num(p.get("qty"));
        }
        catch (Exception ignored) {}
        return 0;
    }

    private static String fmt(Object o)
    {
        long n = o instanceof Number ? ((Number) o).longValue() : 0;
        long a = Math.abs(n);
        if (a >= 1_000_000) return (Math.round(n / 100000.0) / 10.0) + "M";
        if (a >= 1_000) return (Math.round(n / 100.0) / 10.0) + "k";
        return String.valueOf(n);
    }

    private JPanel buildComposer()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(PANEL_BG);
        p.setBorder(new EmptyBorder(4, 10, 10, 10));

        statusLbl.setFont(META_FONT);
        statusLbl.setForeground(ACCENT);
        statusLbl.setAlignmentX(LEFT_ALIGNMENT);
        p.add(statusLbl);
        p.add(Box.createVerticalStrut(4));

        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setFont(BODY_FONT);
        input.setForeground(Color.WHITE);
        input.setBackground(FIELD_BG);
        input.setCaretColor(Color.WHITE);
        input.setBorder(new CompoundBorder(
            new MatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(5, 6, 5, 6)));
        input.addKeyListener(new KeyAdapter()
        {
            @Override public void keyPressed(KeyEvent e)
            {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown())
                {
                    e.consume();
                    onSend();
                }
            }
        });
        JScrollPane inputScroll = new JScrollPane(input,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        inputScroll.setBorder(null);
        inputScroll.setAlignmentX(LEFT_ALIGNMENT);
        inputScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        p.add(inputScroll);
        p.add(Box.createVerticalStrut(6));

        styleButton(sendBtn, true);
        sendBtn.addActionListener(e -> onSend());
        p.add(sendBtn);
        return p;
    }

    // ── external hooks ────────────────────────────────────────────────────────────

    /** A proactive nudge: a dim, avatar-less line, visually distinct from chat. EDT-safe. */
    public void addNudge(String text)
    {
        SwingUtilities.invokeLater(() ->
        {
            JLabel body = new JLabel("<html><body style='width:" + BODY_WIDTH + "px'>"
                + "<i>* " + toHtml(text) + "</i></body></html>");
            body.setFont(META_FONT);
            body.setForeground(META_COLOR);
            body.setAlignmentX(LEFT_ALIGNMENT);
            body.setBorder(new EmptyBorder(2, 2, 2, 0));
            messages.add(body);
            messages.add(Box.createVerticalStrut(8));
            messages.revalidate();
            scrollToBottom();
        });
    }

    /** Called by the plugin once Guice injection is done. */
    public void refresh()
    {
        SwingUtilities.invokeLater(() ->
        {
            syncSettingsFromConfig();
            updateProviderLabel();
            if (activeKeyMissing(config.llmProvider())) showSettings(true); // nudge first-run setup
        });
    }

    private void syncSettingsFromConfig()
    {
        try
        {
            providerCombo.setSelectedItem(config.llmProvider());
            loadKeyForSelectedProvider();
            modelField.setText(config.llmModel() == null ? "" : config.llmModel());
        }
        catch (Exception ignored) {}
    }

    private void updateProviderLabel()
    {
        try
        {
            LlmProviderType type = config.llmProvider();
            boolean hasKey = !activeKeyMissing(type);
            providerLbl.setText("Provider: " + type + (hasKey ? "" : "  (no key set)"));
            providerLbl.setForeground(hasKey ? META_COLOR : ERROR_COLOR);
        }
        catch (Exception ignored) { providerLbl.setText("Provider: -"); }
    }

    // ── settings actions ──────────────────────────────────────────────────────────

    /** Kept for existing callers: true opens the Settings tab, false returns to Chat. */
    private void showSettings(boolean open) { selectView(open ? V_SETTINGS : V_CHAT); }

    private void loadKeyForSelectedProvider()
    {
        LlmProviderType type = (LlmProviderType) providerCombo.getSelectedItem();
        keyField.setText(currentKeyFor(type));
    }

    private String currentKeyFor(LlmProviderType type)
    {
        if (type == null) return "";
        String k;
        switch (type)
        {
            case ANTHROPIC: k = config.anthropicKey(); break;
            case OPENAI:    k = config.openAiKey();    break;
            case DEEPSEEK:  k = config.deepSeekKey();  break;
            case HOSTED:    k = config.hostedToken();  break;
            default:        k = "";
        }
        return k == null ? "" : k;
    }

    private void onSaveSettings()
    {
        LlmProviderType type = (LlmProviderType) providerCombo.getSelectedItem();
        String key   = new String(keyField.getPassword()).trim();
        String model = modelField.getText().trim();

        configManager.setConfiguration("osrsmcp", "llmProvider", type);
        configManager.setConfiguration("osrsmcp", keyNameFor(type), key);
        configManager.setConfiguration("osrsmcp", "llmModel", model);

        savedLbl.setText("Saved.");
        updateProviderLabel();
        Timer t = new Timer(1500, e -> { savedLbl.setText(" "); showSettings(false); });
        t.setRepeats(false);
        t.start();
    }

    private static String keyNameFor(LlmProviderType type)
    {
        switch (type)
        {
            case ANTHROPIC: return "anthropicKey";
            case OPENAI:    return "openAiKey";
            case DEEPSEEK:  return "deepSeekKey";
            case HOSTED:    return "hostedToken";
            default:        return "deepSeekKey";
        }
    }

    // ── chat actions ────────────────────────────────────────────────────────────

    private void onNewChat()
    {
        agent.reset();
        messages.removeAll();
        turnTools = new ArrayList<>();
        setStatus(" ");
        addMessage("RuneAssist", ACCENT, "R", "New chat.", ACCENT);
        messages.revalidate();
        messages.repaint();
    }

    private void onSend()
    {
        if (busy) return;
        String text = input.getText().trim();
        if (text.isEmpty()) return;

        LlmProviderType type = config.llmProvider();
        if (activeKeyMissing(type))
        {
            addMessage("You", USER_ACCENT, "U", text, Color.WHITE);
            input.setText("");
            addMessage("Error", ERROR_COLOR, "!", "No API key set for " + type
                + ". Open Settings above, pick a provider and paste your key.", ERROR_COLOR);
            updateProviderLabel();
            showSettings(true);
            return;
        }

        addMessage("You", USER_ACCENT, "U", text, Color.WHITE);
        input.setText("");
        setBusy(true);
        turnTools = new ArrayList<>();
        currentQuestion = text;
        answerChars = 0;
        setStatus("Thinking...");
        agent.sendUserMessage(text, new UiListener());
    }

    private boolean activeKeyMissing(LlmProviderType type)
    {
        String key = currentKeyFor(type);
        return key == null || key.trim().isEmpty();
    }

    private void setBusy(boolean b)
    {
        busy = b;
        sendBtn.setEnabled(!b);
        sendBtn.setText(b ? "..." : "Send");
        input.setEnabled(!b);
    }

    private void setStatus(String s) { statusLbl.setText(s == null || s.isEmpty() ? " " : s); }

    // ── agent callbacks (worker thread -> EDT) ────────────────────────────────────

    private final class UiListener implements CompanionAgent.Listener
    {
        @Override public void onAssistantText(String text)
        {
            SwingUtilities.invokeLater(() ->
            {
                if (text != null) answerChars += text.length();
                addMessage("RuneAssist", ACCENT, "R", text, ACCENT);
            });
        }

        @Override public void onToolCall(String toolName)
        {
            SwingUtilities.invokeLater(() ->
            {
                turnTools.add(toolName);
                setStatus("Checking: " + toolName + "...");
            });
        }

        @Override public void onToolResult(String toolName) { /* advances on next call */ }

        @Override public void onComplete(int inputTokens, int outputTokens)
        {
            SwingUtilities.invokeLater(() ->
            {
                addMeta(inputTokens, outputTokens, turnTools);
                try
                {
                    telemetry.logAdvice(currentQuestion, turnTools,
                        config.llmProvider().name(), inputTokens, outputTokens, answerChars);
                }
                catch (Exception ignored) {}
                setStatus(" ");
                setBusy(false);
                input.requestFocusInWindow();
            });
        }

        @Override public void onError(String message)
        {
            SwingUtilities.invokeLater(() ->
            {
                addMessage("Error", ERROR_COLOR, "!",
                    message != null ? message : "Something went wrong.", ERROR_COLOR);
                setStatus(" ");
                setBusy(false);
            });
        }
    }

    // ── message rendering (avatar + name + full-width body) ───────────────────────

    private void addMessage(String name, Color avatarColor, String initial, String text, Color nameColor)
    {
        JPanel msg = new JPanel();
        msg.setLayout(new BoxLayout(msg, BoxLayout.Y_AXIS));
        msg.setBackground(PANEL_BG);
        msg.setAlignmentX(LEFT_ALIGNMENT);
        msg.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JPanel headRow = new JPanel();
        headRow.setLayout(new BoxLayout(headRow, BoxLayout.X_AXIS));
        headRow.setBackground(PANEL_BG);
        headRow.setAlignmentX(LEFT_ALIGNMENT);
        headRow.add(new Avatar(initial, avatarColor));
        headRow.add(Box.createHorizontalStrut(6));
        JLabel nameLbl = new JLabel(name);
        nameLbl.setFont(NAME_FONT);
        nameLbl.setForeground(nameColor);
        headRow.add(nameLbl);
        headRow.add(Box.createHorizontalGlue());
        msg.add(headRow);
        msg.add(Box.createVerticalStrut(3));

        msg.add(selectableBody(text, Color.WHITE));

        lastMsg = msg;
        messages.add(msg);
        messages.add(Box.createVerticalStrut(10));
        messages.revalidate();
        scrollToBottom();
    }

    private void addMeta(int inTok, int outTok, List<String> tools)
    {
        // Keep the visible line minimal: just a small tool count when tools ran.
        // Token counts and the tool list live in the tooltip so the chat stays clean.
        if (tools == null || tools.isEmpty()) return;

        JLabel meta = new JLabel(tools.size() + (tools.size() == 1 ? " tool" : " tools"));
        meta.setFont(META_FONT);
        meta.setForeground(META_COLOR);
        meta.setAlignmentX(LEFT_ALIGNMENT);
        meta.setBorder(new EmptyBorder(3, 26, 0, 0)); // small gap, indented under the name
        meta.setToolTipText("Checked: " + String.join(", ", tools)
            + "  ·  " + inTok + " in / " + outTok + " out tokens");

        // Tuck it into the reply block so it sits directly beneath the text.
        JPanel target = lastMsg;
        if (target != null) { target.add(meta); target.revalidate(); }
        else { messages.add(meta); messages.revalidate(); }
        scrollToBottom();
    }

    private void scrollToBottom()
    {
        SwingUtilities.invokeLater(() ->
        {
            JScrollBar bar = scroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    /** Escape HTML, then render a light subset of markdown (bold + dash bullets). */
    /** A read-only, SELECTABLE/COPYABLE HTML body (JEditorPane) styled to match the chat. */
    private JComponent selectableBody(String text, Color fg)
    {
        JEditorPane ep = new JEditorPane();
        ep.setContentType("text/html");
        ep.setEditable(false);
        ep.setOpaque(false);
        ep.setBorder(null);
        ep.setForeground(fg);
        ep.setFont(BODY_FONT);
        ep.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        ep.setText("<html><body style='width:" + BODY_WIDTH + "px'>" + toHtml(text) + "</body></html>");
        ep.setCaretPosition(0);
        ep.setAlignmentX(LEFT_ALIGNMENT);
        // Bound vertical stretch in the BoxLayout; width follows the fixed HTML width.
        ep.setMaximumSize(new Dimension(Integer.MAX_VALUE, ep.getPreferredSize().height));
        return ep;
    }

    private static String toHtml(String s)
    {
        if (s == null) return "";
        String[] lines = s.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++)
        {
            String line = lines[i]
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
            // **bold** -> <b>bold</b>
            line = line.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
            // leading "- " or "* " bullet -> bullet glyph, keeping any indentation
            line = line.replaceFirst("^(\\s*)[-*]\\s+", "$1&#8226; ");
            out.append(line);
            if (i < lines.length - 1) out.append("<br>");
        }
        return out.toString();
    }

    // ── small styled widgets ──────────────────────────────────────────────────────

    private JLabel fieldLabel(String text)
    {
        JLabel l = new JLabel(text.toUpperCase());
        l.setFont(META_FONT);
        l.setForeground(ACCENT);
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private void styleField(JTextField f)
    {
        f.setFont(BODY_FONT);
        f.setForeground(Color.WHITE);
        f.setBackground(ColorScheme.DARK_GRAY_COLOR);
        f.setCaretColor(Color.WHITE);
        f.setAlignmentX(LEFT_ALIGNMENT);
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        f.setBorder(new CompoundBorder(
            new MatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(3, 5, 3, 5)));
    }

    private void styleButton(JButton btn, boolean accent)
    {
        btn.setFont(new Font("SansSerif", Font.BOLD, 11));
        btn.setForeground(accent ? Color.BLACK : ColorScheme.LIGHT_GRAY_COLOR);
        btn.setBackground(accent ? ACCENT : ColorScheme.MEDIUM_GRAY_COLOR);
        btn.setBorder(new EmptyBorder(6, 12, 6, 12));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setAlignmentX(LEFT_ALIGNMENT);
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
    }

    /** A small round avatar with a centred initial. */
    private static final class Avatar extends JComponent
    {
        private static final int SIZE = 20;
        private final String initial;
        private final Color color;

        Avatar(String initial, Color color)
        {
            this.initial = initial;
            this.color = color;
            Dimension d = new Dimension(SIZE, SIZE);
            setPreferredSize(d); setMinimumSize(d); setMaximumSize(d);
        }

        @Override protected void paintComponent(Graphics g)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(0, 0, SIZE, SIZE);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("SansSerif", Font.BOLD, 11));
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(initial);
            int th = fm.getAscent();
            g2.drawString(initial, (SIZE - tw) / 2, (SIZE + th) / 2 - 2);
            g2.dispose();
        }
    }
}
