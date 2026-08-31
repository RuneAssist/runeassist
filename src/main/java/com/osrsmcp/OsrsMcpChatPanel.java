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

    private final JPanel      messages   = new JPanel();
    private final JScrollPane scroll;
    private final JTextArea   input       = new JTextArea(2, 1);
    private final JButton     sendBtn     = new JButton("Send");
    private final JButton     newChatBtn  = new JButton("New chat");
    private final JButton     settingsBtn = new JButton("Settings");
    private final JLabel      providerLbl = new JLabel();

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

        addMessage("RuneAssist", ACCENT, "R",
            "Hi! I can see your live account, the wiki, your planner and your other "
            + "plugins. Ask me what to do next, how to train something, what your dailies "
            + "are, or anything OSRS.", ACCENT);
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
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBackground(PANEL_BG);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.add(newChatBtn);
        row.add(Box.createHorizontalStrut(6));
        row.add(settingsBtn);
        row.add(Box.createHorizontalGlue());
        p.add(row);

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
        addMessage("RuneAssist", ACCENT, "R", "New chat. What would you like to do?", ACCENT);
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
            SwingUtilities.invokeLater(() -> addMessage("RuneAssist", ACCENT, "R", text, ACCENT));
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

        JLabel body = new JLabel("<html><body style='width:" + BODY_WIDTH + "px'>"
            + toHtml(text) + "</body></html>");
        body.setFont(BODY_FONT);
        body.setForeground(Color.WHITE);
        body.setAlignmentX(LEFT_ALIGNMENT);
        msg.add(body);

        lastMsg = msg;
        messages.add(msg);
        messages.add(Box.createVerticalStrut(10));
        messages.revalidate();
        scrollToBottom();
    }

    private void addMeta(int inTok, int outTok, List<String> tools)
    {
        StringBuilder sb = new StringBuilder();
        if (tools != null && !tools.isEmpty())
            sb.append(tools.size()).append(tools.size() == 1 ? " tool" : " tools").append(" · ");
        sb.append(inTok).append(" in / ").append(outTok).append(" out");

        JLabel meta = new JLabel(sb.toString());
        meta.setFont(META_FONT);
        meta.setForeground(META_COLOR);
        meta.setAlignmentX(LEFT_ALIGNMENT);
        meta.setBorder(new EmptyBorder(3, 26, 0, 0)); // small gap, indented under the name
        if (tools != null && !tools.isEmpty())
            meta.setToolTipText("Checked: " + String.join(", ", tools));

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
