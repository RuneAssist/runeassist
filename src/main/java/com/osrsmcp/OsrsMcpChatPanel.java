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

    private final JPanel      messages   = new JPanel();
    private final JScrollPane scroll;
    private final JTextArea   input       = new JTextArea(2, 1);
    private final JButton     sendBtn     = new JButton("Send");
    private final JButton     newChatBtn  = new JButton("New chat");
    private final JButton     settingsBtn = new JButton("Settings");
    private final JButton     goalsBtn    = new JButton("Goals");
    private final JPanel      goalsPanel  = new JPanel();
    private boolean           goalsOpen   = false;
    private final JLabel      providerLbl = new JLabel();

    // Flips section (the flip model as a collapsible tab, like Goals/Settings)
    private final JButton     flipsBtn     = new JButton("Flips");
    private final JPanel      flipsPanel   = new JPanel();
    private final JTextField  capitalField = new JTextField("10m");
    private final JButton     findFlipsBtn = new JButton("Find flips");
    private final JPanel      flipResults  = new JPanel();
    private final JLabel      flipStatus   = new JLabel(" ");
    private final JLabel      profitLbl    = new JLabel(" ");
    private final JPanel      flipLog      = new JPanel();
    private boolean           flipsOpen    = false;

    // Settings controls
    private final JPanel                     settingsPanel = new JPanel();
    private final JComboBox<LlmProviderType> providerCombo =
        new JComboBox<>(new LlmProviderType[]{ LlmProviderType.ANTHROPIC, LlmProviderType.OPENAI, LlmProviderType.DEEPSEEK });
    private final JPasswordField keyField   = new JPasswordField();
    private final JTextField     modelField = new JTextField();
    private final JLabel         savedLbl   = new JLabel(" ");

    private final JLabel statusLbl = new JLabel(" ");

    private volatile boolean busy = false;
    private boolean settingsOpen = false;
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
        add(scroll, BorderLayout.CENTER);

        add(buildComposer(), BorderLayout.SOUTH);

        addMessage("RuneAssist", ACCENT, "R", "Hi! Ask me anything OSRS.", ACCENT);
    }

    // ── header (title, provider, actions, settings) ──────────────────────────────

    private JPanel buildHeader()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(PANEL_BG);
        p.setBorder(new EmptyBorder(10, 10, 6, 10));

        JLabel title = new JLabel("RuneAssist");
        title.setForeground(Color.WHITE);
        title.setFont(new Font("SansSerif", Font.BOLD, 16));
        title.setAlignmentX(LEFT_ALIGNMENT);
        p.add(title);
        p.add(Box.createVerticalStrut(3));

        providerLbl.setFont(META_FONT);
        providerLbl.setForeground(META_COLOR);
        providerLbl.setAlignmentX(LEFT_ALIGNMENT);
        p.add(providerLbl);
        p.add(Box.createVerticalStrut(6));

        styleButton(newChatBtn, false);
        newChatBtn.addActionListener(e -> onNewChat());
        styleButton(settingsBtn, false);
        settingsBtn.addActionListener(e -> toggleSettings());
        styleButton(goalsBtn, false);
        goalsBtn.addActionListener(e -> toggleGoals());
        styleButton(flipsBtn, false);
        flipsBtn.addActionListener(e -> toggleFlips());
        // Four toggle tabs won't fit on one row at this panel width, so wrap them
        // into a 2-column grid: New chat / Goals · Flips / Settings.
        JPanel row = new JPanel(new GridLayout(0, 2, 6, 6));
        row.setBackground(PANEL_BG);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
        row.add(newChatBtn);
        row.add(goalsBtn);
        row.add(flipsBtn);
        row.add(settingsBtn);
        p.add(row);

        p.add(buildGoalsPanel());
        p.add(buildFlipsPanel());
        p.add(buildSettingsPanel());
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
        settingsPanel.setVisible(false);

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
        goalsPanel.setVisible(false);
        return goalsPanel;
    }

    private void toggleGoals() { showGoals(!goalsOpen); }

    private void showGoals(boolean open)
    {
        goalsOpen = open;
        goalsBtn.setText(open ? "Hide goals" : "Goals");
        goalsPanel.setVisible(open);
        if (open) refreshGoals();
        revalidate();
        repaint();
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
        flipsPanel.setVisible(false);

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

        JButton resetBtn = new JButton("Reset flips");
        styleButton(resetBtn, false);
        resetBtn.addActionListener(e -> { flipTracker.reset(); refreshFlipLog(); });
        flipsPanel.add(Box.createVerticalStrut(4));
        flipsPanel.add(resetBtn);

        JPanel sep = new JPanel();
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        sep.setAlignmentX(LEFT_ALIGNMENT);
        flipsPanel.add(Box.createVerticalStrut(8));
        flipsPanel.add(sep);
        flipsPanel.add(Box.createVerticalStrut(8));

        flipsPanel.add(fieldLabel("Capital"));
        capitalField.setAlignmentX(LEFT_ALIGNMENT);
        capitalField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        capitalField.setFont(BODY_FONT);
        capitalField.setForeground(Color.WHITE);
        capitalField.setBackground(ColorScheme.DARK_GRAY_COLOR);
        capitalField.setCaretColor(Color.WHITE);
        capitalField.setBorder(new CompoundBorder(
            new MatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(3, 5, 3, 5)));
        capitalField.addActionListener(e -> refreshFlips());
        flipsPanel.add(capitalField);
        flipsPanel.add(Box.createVerticalStrut(6));

        styleButton(findFlipsBtn, true);
        findFlipsBtn.addActionListener(e -> refreshFlips());
        flipsPanel.add(findFlipsBtn);

        flipStatus.setFont(META_FONT);
        flipStatus.setForeground(META_COLOR);
        flipStatus.setAlignmentX(LEFT_ALIGNMENT);
        flipsPanel.add(Box.createVerticalStrut(4));
        flipsPanel.add(flipStatus);

        flipResults.setLayout(new BoxLayout(flipResults, BoxLayout.Y_AXIS));
        flipResults.setBackground(FIELD_BG);
        flipResults.setAlignmentX(LEFT_ALIGNMENT);
        flipsPanel.add(Box.createVerticalStrut(4));
        flipsPanel.add(flipResults);

        JLabel hint = new JLabel("<html><body style='width:" + BODY_WIDTH + "px'>"
            + "Display-only market flips ranked for your capital. Advice, not automation.</body></html>");
        hint.setFont(META_FONT);
        hint.setForeground(META_COLOR);
        hint.setAlignmentX(LEFT_ALIGNMENT);
        flipsPanel.add(Box.createVerticalStrut(6));
        flipsPanel.add(hint);
        return flipsPanel;
    }

    private void toggleFlips() { showFlips(!flipsOpen); }

    private void showFlips(boolean open)
    {
        flipsOpen = open;
        flipsBtn.setText(open ? "Hide flips" : "Flips");
        flipsPanel.setVisible(open);
        if (open) refreshFlipLog();
        revalidate();
        repaint();
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

    private void refreshFlips()
    {
        final long capital = parseCapital(capitalField.getText());
        findFlipsBtn.setEnabled(false);
        flipStatus.setText("Finding flips...");
        new Thread(() ->
        {
            java.util.Map<String, Object> res;
            try { res = playerDataService.buildFlipSuggestions(capital, 0, 0, 12); }
            catch (Exception ex) { res = null; }
            final java.util.Map<String, Object> r = res;
            SwingUtilities.invokeLater(() -> renderFlips(r));
        }, "runeassist-flips").start();
    }

    @SuppressWarnings("unchecked")
    private void renderFlips(java.util.Map<String, Object> r)
    {
        flipResults.removeAll();
        findFlipsBtn.setEnabled(true);
        if (r == null || r.get("error") != null)
        {
            flipStatus.setText(r != null ? String.valueOf(r.get("error")) : "Failed to load prices.");
            flipResults.revalidate(); flipResults.repaint();
            return;
        }
        java.util.List<java.util.Map<String, Object>> list =
            (java.util.List<java.util.Map<String, Object>>) r.get("suggestions");
        int count = r.get("count") instanceof Number ? ((Number) r.get("count")).intValue()
            : (list == null ? 0 : list.size());
        flipStatus.setText(count + " candidates · top " + (list == null ? 0 : list.size()));
        if (list != null) for (java.util.Map<String, Object> s : list) flipResults.add(flipRow(s));
        flipResults.revalidate(); flipResults.repaint();
        refreshFlipLog();
        revalidate(); repaint();
    }

    @SuppressWarnings("unchecked")
    private JComponent flipRow(java.util.Map<String, Object> s)
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(ColorScheme.DARK_GRAY_COLOR);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(5, 7, 5, 7)));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 108));

        JLabel name = new JLabel(String.valueOf(s.get("name")));
        name.setFont(NAME_FONT);
        name.setForeground(Color.WHITE);
        name.setAlignmentX(LEFT_ALIGNMENT);
        p.add(name);

        JLabel d1 = new JLabel(fmt(s.get("buy_at")) + " → " + fmt(s.get("sell_at"))
            + "  +" + fmt(s.get("margin_post_tax")) + " (" + s.get("margin_pct") + "%)");
        d1.setFont(META_FONT);
        d1.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        d1.setAlignmentX(LEFT_ALIGNMENT);
        p.add(d1);

        JLabel d2 = new JLabel("x" + s.get("suggested_qty") + "  =  "
            + fmt(s.get("projected_profit")) + " profit");
        d2.setFont(META_FONT);
        d2.setForeground(new Color(0, 180, 90));
        d2.setAlignmentX(LEFT_ALIGNMENT);
        p.add(d2);

        // Buy-limit remaining in the 4h window (from what you've actually bought).
        int id = (int) num(s.get("id"));
        int geLimit = (int) num(s.get("ge_limit"));
        int left = flipTracker.limitRemaining(id, geLimit);
        if (geLimit > 0)
        {
            JLabel lim = new JLabel("limit " + fmt(left) + "/" + fmt(geLimit) + " left (4h)");
            lim.setFont(META_FONT);
            lim.setForeground(left == 0 ? ColorScheme.BRAND_ORANGE : META_COLOR);
            lim.setAlignmentX(LEFT_ALIGNMENT);
            p.add(lim);
        }

        Object flags = s.get("flags");
        if (flags instanceof java.util.List && !((java.util.List<?>) flags).isEmpty())
        {
            JLabel fl = new JLabel(String.join(", ", (java.util.List<String>) flags));
            fl.setFont(META_FONT);
            fl.setForeground(ColorScheme.BRAND_ORANGE);
            fl.setAlignmentX(LEFT_ALIGNMENT);
            p.add(fl);
        }

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(FIELD_BG);
        wrap.setBorder(new EmptyBorder(0, 0, 6, 0));
        wrap.add(p, BorderLayout.NORTH);
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 116));
        wrap.setAlignmentX(LEFT_ALIGNMENT);
        return wrap;
    }

    private static String fmt(Object o)
    {
        long n = o instanceof Number ? ((Number) o).longValue() : 0;
        long a = Math.abs(n);
        if (a >= 1_000_000) return (Math.round(n / 100000.0) / 10.0) + "M";
        if (a >= 1_000) return (Math.round(n / 100.0) / 10.0) + "k";
        return String.valueOf(n);
    }

    private static long parseCapital(String s)
    {
        if (s == null) return 0;
        s = s.trim().toLowerCase().replace(",", "");
        if (s.isEmpty()) return 0;
        double mul = 1;
        if (s.endsWith("m")) { mul = 1_000_000; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("k")) { mul = 1_000; s = s.substring(0, s.length() - 1); }
        try { return (long) (Double.parseDouble(s) * mul); }
        catch (NumberFormatException e) { return 0; }
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

    private void toggleSettings() { showSettings(!settingsOpen); }

    private void showSettings(boolean open)
    {
        settingsOpen = open;
        settingsPanel.setVisible(open);
        settingsBtn.setText(open ? "Hide settings" : "Settings");
        if (open) syncSettingsFromConfig();
        savedLbl.setText(" ");
        revalidate();
        repaint();
    }

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
