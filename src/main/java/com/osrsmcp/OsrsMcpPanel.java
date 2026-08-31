package com.osrsmcp;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Side panel for the OSRS MCP plugin. Focused on what actually helps: server
 * status, one-click connect (endpoint + client config), a live activity log so
 * you can watch the AI work, integration health, and a data-refresh action.
 */
@Slf4j
@Singleton
public class OsrsMcpPanel extends PluginPanel
{
    private static final Color CARD_BG = ColorScheme.DARKER_GRAY_COLOR;
    private static final Color GREEN   = new Color(0, 180, 90);
    private static final Color MONO_ORANGE = ColorScheme.BRAND_ORANGE;
    private static final int   ACTIVITY_ROWS = 6;

    // Header / status
    private final JLabel statusDot   = new JLabel("⬤");
    private final JLabel statusText  = new JLabel("Starting…");
    private final JLabel gameState   = new JLabel("⬤ Not logged in");

    // Connect
    private final JTextField endpointField = new JTextField();
    private final JButton copyUrlBtn    = new JButton("Copy URL");
    private final JButton copyConfigBtn = new JButton("Copy client config");
    private final JTextArea configHolder = new JTextArea();   // not shown; holds JSON for copy
    private JPanel  tailscaleLine;
    private final JLabel tailscaleLabel = new JLabel();
    private final JButton installTailscaleBtn = new JButton("Copy tailscale.com");

    // Activity
    private final JLabel requestCount = new JLabel("0 requests");
    private final JLabel[] actName = new JLabel[ACTIVITY_ROWS];
    private final JLabel[] actAge  = new JLabel[ACTIVITY_ROWS];
    private final JPanel[] actRow  = new JPanel[ACTIVITY_ROWS];
    private final JLabel activityEmpty = new JLabel("No requests yet — connect a client.");

    // Integrations / data
    private final JLabel questData = new JLabel("Quest data: —");
    private final JButton reloadBtn = new JButton("Refresh data");
    private final JLabel spDot  = new JLabel("⬤ Shortest Path");
    private final JLabel tcgDot = new JLabel("⬤ OSRS TCG");
    private final JLabel invDot = new JLabel("⬤ Inventory Setups");

    // Footer
    private final JButton restartBtn = new JButton("Restart server");
    private final JLabel toolsNote = new JLabel();

    // State
    private int currentPort = 8282;
    private String currentLanIp = null;
    private ConnectionMode currentMode = ConnectionMode.LOCAL;

    private Runnable restartCallback;
    private Runnable reloadCallback;
    private ConfigManager configManager;
    private TailscaleService tailscaleService;
    private Supplier<Map<String, Object>> statusSupplier;
    private Timer statusTimer;

    public OsrsMcpPanel()
    {
        super(false);
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(ColorScheme.DARK_GRAY_COLOR);
        root.setBorder(new EmptyBorder(10, 10, 10, 10));

        root.add(buildHeader());
        root.add(vGap(10));
        root.add(header("Connect"));
        root.add(vGap(4));
        root.add(buildConnectCard());
        root.add(vGap(10));
        root.add(header("Activity"));
        root.add(vGap(4));
        root.add(buildActivityCard());
        root.add(vGap(10));
        root.add(header("Integrations"));
        root.add(vGap(4));
        root.add(buildIntegrationsCard());
        root.add(vGap(10));
        root.add(buildFooter());

        add(root, BorderLayout.NORTH);
        refreshConfig();
    }

    // ── wiring from the plugin ──────────────────────────────────────────────

    public void setRestartCallback(Runnable cb)          { this.restartCallback = cb; }
    public void setReloadCallback(Runnable cb)           { this.reloadCallback = cb; }
    public void setConfigManager(ConfigManager cm)       { this.configManager = cm; }
    public void setTailscaleService(TailscaleService ts) { this.tailscaleService = ts; }

    public void setStatusSupplier(Supplier<Map<String, Object>> supplier)
    {
        this.statusSupplier = supplier;
        if (statusTimer == null)
        {
            statusTimer = new Timer(2000, e -> refreshStatus());
            statusTimer.start();
        }
        refreshStatus();
    }

    public void stopStatusUpdates()
    {
        if (statusTimer != null) { statusTimer.stop(); statusTimer = null; }
        statusSupplier = null;
    }

    // ── sections ────────────────────────────────────────────────────────────

    private JPanel buildHeader()
    {
        JPanel p = col(ColorScheme.DARK_GRAY_COLOR);
        JLabel title = new JLabel("OSRS MCP");
        title.setForeground(Color.WHITE);
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setAlignmentX(LEFT_ALIGNMENT);
        p.add(title);
        p.add(vGap(6));

        statusDot.setFont(statusDot.getFont().deriveFont(9f));
        statusDot.setForeground(Color.GRAY);
        statusText.setFont(FontManager.getRunescapeSmallFont());
        statusText.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        JPanel sr = row();
        sr.add(statusDot); sr.add(Box.createHorizontalStrut(5)); sr.add(statusText);
        p.add(sr);

        gameState.setFont(FontManager.getRunescapeSmallFont());
        gameState.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
        gameState.setAlignmentX(LEFT_ALIGNMENT);
        p.add(vGap(2));
        p.add(gameState);
        return p;
    }

    private JPanel buildConnectCard()
    {
        JPanel p = card();
        endpointField.setEditable(false);
        endpointField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        endpointField.setForeground(MONO_ORANGE);
        endpointField.setBackground(ColorScheme.DARK_GRAY_COLOR);
        endpointField.setBorder(new EmptyBorder(3, 4, 3, 4));
        endpointField.setAlignmentX(LEFT_ALIGNMENT);
        endpointField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        p.add(endpointField);
        p.add(vGap(6));

        styleButton(copyUrlBtn);
        copyUrlBtn.addActionListener(e -> copy(endpointField.getText(), copyUrlBtn, "Copy URL"));
        styleButton(copyConfigBtn);
        copyConfigBtn.addActionListener(e -> copy(configHolder.getText(), copyConfigBtn, "Copy client config"));
        JPanel btns = row();
        btns.add(copyUrlBtn); btns.add(Box.createHorizontalStrut(6)); btns.add(copyConfigBtn);
        p.add(btns);

        // Tailscale line (only shown in Tailscale mode)
        tailscaleLine = col(CARD_BG);
        tailscaleLine.add(vGap(6));
        tailscaleLabel.setFont(FontManager.getRunescapeSmallFont());
        tailscaleLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
        tailscaleLabel.setAlignmentX(LEFT_ALIGNMENT);
        tailscaleLine.add(tailscaleLabel);
        styleButton(installTailscaleBtn);
        installTailscaleBtn.addActionListener(e -> copy("https://tailscale.com/download", installTailscaleBtn, "Copy tailscale.com"));
        tailscaleLine.add(vGap(4));
        tailscaleLine.add(installTailscaleBtn);
        tailscaleLine.setVisible(false);
        p.add(tailscaleLine);

        p.add(vGap(6));
        JLabel hint = wrap("Add this to your AI client, then ask it about your account.");
        p.add(hint);
        return p;
    }

    private JPanel buildActivityCard()
    {
        JPanel p = card();
        requestCount.setFont(FontManager.getRunescapeBoldFont());
        requestCount.setForeground(Color.WHITE);
        requestCount.setAlignmentX(LEFT_ALIGNMENT);
        p.add(requestCount);
        p.add(vGap(4));

        activityEmpty.setFont(FontManager.getRunescapeSmallFont());
        activityEmpty.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
        activityEmpty.setAlignmentX(LEFT_ALIGNMENT);
        p.add(activityEmpty);

        for (int i = 0; i < ACTIVITY_ROWS; i++)
        {
            actName[i] = new JLabel();
            actName[i].setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            actName[i].setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            actAge[i] = new JLabel();
            actAge[i].setFont(FontManager.getRunescapeSmallFont());
            actAge[i].setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
            actAge[i].setHorizontalAlignment(SwingConstants.RIGHT);
            JPanel r = new JPanel(new BorderLayout(6, 0));
            r.setBackground(CARD_BG);
            r.setBorder(new EmptyBorder(1, 0, 1, 0));
            r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
            r.setAlignmentX(LEFT_ALIGNMENT);
            r.add(actName[i], BorderLayout.WEST);
            r.add(actAge[i], BorderLayout.EAST);
            r.setVisible(false);
            actRow[i] = r;
            p.add(r);
        }
        return p;
    }

    private JPanel buildIntegrationsCard()
    {
        JPanel p = card();
        questData.setFont(FontManager.getRunescapeSmallFont());
        questData.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        questData.setAlignmentX(LEFT_ALIGNMENT);
        p.add(questData);
        p.add(vGap(4));
        styleButton(reloadBtn);
        reloadBtn.addActionListener(e -> {
            if (reloadCallback == null) return;
            reloadBtn.setEnabled(false);
            reloadBtn.setText("Refreshing…");
            new Thread(() -> {
                try { reloadCallback.run(); } catch (Exception ignored) {}
                SwingUtilities.invokeLater(() -> { reloadBtn.setEnabled(true); reloadBtn.setText("Refresh data"); refreshStatus(); });
            }, "osrs-mcp-reload").start();
        });
        p.add(reloadBtn);
        p.add(vGap(6));
        for (JLabel d : new JLabel[]{ spDot, tcgDot, invDot })
        {
            d.setFont(FontManager.getRunescapeSmallFont());
            d.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
            d.setAlignmentX(LEFT_ALIGNMENT);
            p.add(d);
            p.add(vGap(1));
        }
        return p;
    }

    private JPanel buildFooter()
    {
        JPanel p = col(ColorScheme.DARK_GRAY_COLOR);
        styleButton(restartBtn);
        restartBtn.addActionListener(e -> {
            if (restartCallback == null) return;
            restartBtn.setEnabled(false);
            restartBtn.setText("Restarting…");
            new Thread(() -> {
                restartCallback.run();
                SwingUtilities.invokeLater(() -> { restartBtn.setEnabled(true); restartBtn.setText("Restart server"); });
            }, "osrs-mcp-restart").start();
        });
        p.add(restartBtn);
        p.add(vGap(4));
        toolsNote.setFont(FontManager.getRunescapeSmallFont());
        toolsNote.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
        toolsNote.setAlignmentX(LEFT_ALIGNMENT);
        toolsNote.setText("Live account + wiki + planner + progress + plugin tools.");
        p.add(toolsNote);
        return p;
    }

    // ── live refresh ────────────────────────────────────────────────────────

    private void refreshStatus()
    {
        if (statusSupplier == null) return;
        Map<String, Object> s;
        try { s = statusSupplier.get(); } catch (Exception ex) { return; }
        if (s == null) return;

        questData.setText("Quest data: " + s.getOrDefault("quest_data", "—"));
        long n = s.get("request_count") instanceof Number ? ((Number) s.get("request_count")).longValue() : 0;
        requestCount.setText(n + (n == 1 ? " request" : " requests"));

        paintDot(spDot,  "Shortest Path",    Boolean.TRUE.equals(s.get("shortestpath")));
        paintDot(tcgDot, "OSRS TCG",         Boolean.TRUE.equals(s.get("osrstcg")));
        paintDot(invDot, "Inventory Setups", Boolean.TRUE.equals(s.get("inventorysetups")));

        Object recent = s.get("recent");
        int shown = 0;
        if (recent instanceof List)
        {
            List<?> list = (List<?>) recent;
            for (int i = 0; i < ACTIVITY_ROWS && i < list.size(); i++)
            {
                if (!(list.get(i) instanceof Map)) continue;
                Map<?, ?> row = (Map<?, ?>) list.get(i);
                actName[i].setText(String.valueOf(row.get("name")));
                actAge[i].setText(fmtAge(row.get("age")));
                actRow[i].setVisible(true);
                shown++;
            }
        }
        for (int i = shown; i < ACTIVITY_ROWS; i++) actRow[i].setVisible(false);
        activityEmpty.setVisible(shown == 0);

        revalidate();
        repaint();
    }

    private static String fmtAge(Object ageObj)
    {
        long a = ageObj instanceof Number ? ((Number) ageObj).longValue() : 0;
        if (a < 60) return a + "s ago";
        if (a < 3600) return (a / 60) + "m ago";
        return (a / 3600) + "h ago";
    }

    private void paintDot(JLabel label, String name, boolean on)
    {
        label.setText("⬤ " + name);
        label.setForeground(on ? GREEN : ColorScheme.MEDIUM_GRAY_COLOR);
        label.setToolTipText(on ? name + " is enabled — its tools will work."
                                : name + " is not enabled; its tools return unavailable.");
    }

    // ── public state (called by the plugin) ─────────────────────────────────

    public void setServerRunning(boolean running, int port, ConnectionMode mode, String lanIp)
    {
        SwingUtilities.invokeLater(() ->
        {
            currentPort = port;
            currentMode = mode;
            currentLanIp = running ? lanIp : null;
            statusDot.setForeground(running ? GREEN : Color.GRAY);
            statusText.setText(running ? "Server running · port " + port + " · " + mode : "Server stopped");
            endpointField.setText(running ? endpointUrl() : "");
            copyUrlBtn.setEnabled(running);
            copyConfigBtn.setEnabled(running);

            boolean ts = mode == ConnectionMode.TAILSCALE;
            tailscaleLine.setVisible(ts);
            if (ts)
            {
                tailscaleLabel.setText(lanIp != null ? "Tailscale IP: " + lanIp : "Tailscale not detected");
                installTailscaleBtn.setVisible(lanIp == null);
            }
            refreshConfig();
            revalidate();
            repaint();
        });
    }

    public void setError(String message)
    {
        SwingUtilities.invokeLater(() ->
        {
            statusDot.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
            statusText.setText("Error: " + message);
        });
    }

    public void updateGameState(GameState state)
    {
        SwingUtilities.invokeLater(() ->
        {
            switch (state)
            {
                case LOGGED_IN:    gameState.setForeground(GREEN); gameState.setText("⬤ Logged in"); break;
                case LOGIN_SCREEN: gameState.setForeground(ColorScheme.MEDIUM_GRAY_COLOR); gameState.setText("⬤ Login screen"); break;
                case LOADING:      gameState.setForeground(MONO_ORANGE); gameState.setText("⬤ Loading…"); break;
                default:           gameState.setForeground(ColorScheme.MEDIUM_GRAY_COLOR); gameState.setText("⬤ " + state.name().toLowerCase());
            }
        });
    }

    public void refreshSectionsForMode(ConnectionMode mode)
    {
        SwingUtilities.invokeLater(() ->
        {
            currentMode = mode;
            boolean ts = mode == ConnectionMode.TAILSCALE;
            if (tailscaleLine != null) tailscaleLine.setVisible(ts);
            endpointField.setText(endpointUrl());
            refreshConfig();
            revalidate();
            repaint();
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String endpointUrl()
    {
        switch (currentMode)
        {
            case LAN:       return "http://" + (currentLanIp != null ? currentLanIp : "YOUR_LAN_IP") + ":" + currentPort + "/mcp";
            case TAILSCALE: return "http://" + (currentLanIp != null ? currentLanIp : "YOUR_TAILSCALE_IP") + ":" + currentPort + "/mcp";
            default:        return "http://127.0.0.1:" + currentPort + "/mcp";
        }
    }

    private void refreshConfig()
    {
        String url = endpointUrl();
        boolean local = currentMode == ConnectionMode.LOCAL;
        String argsLine = local ? "      \"" + url + "\"]" : "      \"" + url + "\",\n      \"--allow-http\"]";
        configHolder.setText(
            "\"osrs\": {\n" +
            "  \"command\": \"npx\",\n" +
            "  \"args\": [\"mcp-remote\",\n" +
            argsLine + "\n" +
            "}");
    }

    private void copy(String text, JButton btn, String original)
    {
        if (text == null || text.isEmpty()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        btn.setText("Copied!");
        Timer t = new Timer(1400, e -> btn.setText(original));
        t.setRepeats(false);
        t.start();
    }

    private JLabel header(String text)
    {
        JLabel l = new JLabel(text.toUpperCase());
        l.setFont(FontManager.getRunescapeSmallFont());
        l.setForeground(ColorScheme.BRAND_ORANGE);
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private JLabel wrap(String text)
    {
        JLabel l = new JLabel("<html><body style='width:150px'>" + text + "</body></html>");
        l.setFont(FontManager.getRunescapeSmallFont());
        l.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private JPanel card()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(CARD_BG);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(8, 8, 8, 8)));
        return p;
    }

    private JPanel col(Color bg)
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(bg);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return p;
    }

    private JPanel row()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setBackground(CARD_BG);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        return p;
    }

    private Component vGap(int h) { return Box.createVerticalStrut(h); }

    private void styleButton(JButton btn)
    {
        btn.setFont(FontManager.getRunescapeSmallFont());
        btn.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        btn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        btn.setBorder(new EmptyBorder(5, 10, 5, 10));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setAlignmentX(LEFT_ALIGNMENT);
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
    }
}
