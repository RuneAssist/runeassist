package com.osrsmcp;

import lombok.extern.slf4j.Slf4j;
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
 * In-panel AI chat ("OSRS Companion"). Drives {@link CompanionAgent} and renders the
 * conversation. The agent is headless and fires its callbacks on a worker thread, so
 * every {@link CompanionAgent.Listener} method here marshals onto the Swing EDT.
 */
@Slf4j
@Singleton
public class OsrsMcpChatPanel extends PluginPanel
{
    private static final Color CARD_BG    = ColorScheme.DARKER_GRAY_COLOR;
    private static final Color USER_BG     = ColorScheme.MEDIUM_GRAY_COLOR;
    private static final Color ERROR_COLOR = ColorScheme.PROGRESS_ERROR_COLOR;
    private static final int   BUBBLE_TEXT_WIDTH = 176; // px; fits the ~225px side panel

    @Inject private CompanionAgent agent;
    @Inject private OsrsMcpConfig config;

    private final JPanel      messages   = new JPanel();
    private final JScrollPane scroll;
    private final JTextArea   input       = new JTextArea(2, 1);
    private final JButton     sendBtn     = new JButton("Send");
    private final JButton     newChatBtn  = new JButton("New chat");
    private final JLabel      providerLbl = new JLabel();
    private final JLabel      statusLbl   = new JLabel(" ");

    private volatile boolean busy = false;
    private List<String> turnTools = new ArrayList<>();

    public OsrsMcpChatPanel()
    {
        super(false);
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        add(buildHeader(), BorderLayout.NORTH);

        messages.setLayout(new BoxLayout(messages, BoxLayout.Y_AXIS));
        messages.setBackground(ColorScheme.DARK_GRAY_COLOR);
        messages.setBorder(new EmptyBorder(8, 8, 8, 8));

        // Wrapper keeps bubbles top-aligned when the transcript is short.
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
        wrapper.add(messages, BorderLayout.NORTH);

        scroll = new JScrollPane(wrapper,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
        add(scroll, BorderLayout.CENTER);

        add(buildComposer(), BorderLayout.SOUTH);

        addAssistantText("Hi! I can see your live account, the wiki, your planner and "
            + "your other plugins. Ask me what to do next, how to train something, "
            + "what your dailies are, or anything OSRS.");
    }

    // ── header ────────────────────────────────────────────────────────────────

    private JPanel buildHeader()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(ColorScheme.DARK_GRAY_COLOR);
        p.setBorder(new EmptyBorder(10, 10, 6, 10));

        JLabel title = new JLabel("OSRS Companion");
        title.setForeground(Color.WHITE);
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setAlignmentX(LEFT_ALIGNMENT);
        p.add(title);
        p.add(Box.createVerticalStrut(4));

        providerLbl.setFont(FontManager.getRunescapeSmallFont());
        providerLbl.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
        providerLbl.setAlignmentX(LEFT_ALIGNMENT);
        p.add(providerLbl);
        p.add(Box.createVerticalStrut(6));

        styleButton(newChatBtn);
        newChatBtn.addActionListener(e -> onNewChat());
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.add(newChatBtn);
        row.add(Box.createHorizontalGlue());
        p.add(row);
        return p;
    }

    // ── composer (status + input + send) ────────────────────────────────────────

    private JPanel buildComposer()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(ColorScheme.DARK_GRAY_COLOR);
        p.setBorder(new EmptyBorder(4, 10, 10, 10));

        statusLbl.setFont(FontManager.getRunescapeSmallFont());
        statusLbl.setForeground(ColorScheme.BRAND_ORANGE);
        statusLbl.setAlignmentX(LEFT_ALIGNMENT);
        p.add(statusLbl);
        p.add(Box.createVerticalStrut(4));

        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setFont(FontManager.getRunescapeSmallFont());
        input.setForeground(Color.WHITE);
        input.setBackground(CARD_BG);
        input.setCaretColor(Color.WHITE);
        input.setBorder(new CompoundBorder(
            new MatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(5, 6, 5, 6)));
        // Enter sends; Shift+Enter inserts a newline.
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

        styleButton(sendBtn);
        sendBtn.setBackground(ColorScheme.BRAND_ORANGE);
        sendBtn.setForeground(Color.BLACK);
        sendBtn.addActionListener(e -> onSend());
        p.add(sendBtn);
        return p;
    }

    // ── actions ─────────────────────────────────────────────────────────────────

    /** Called by the plugin once Guice injection is done, to show the active provider. */
    public void refresh()
    {
        SwingUtilities.invokeLater(this::updateProviderLabel);
    }

    private void updateProviderLabel()
    {
        try
        {
            LlmProviderType type = config.llmProvider();
            boolean hasKey = !activeKeyMissing(type);
            providerLbl.setText("Provider: " + type + (hasKey ? "" : "  (no key set)"));
            providerLbl.setForeground(hasKey ? ColorScheme.MEDIUM_GRAY_COLOR : ERROR_COLOR);
        }
        catch (Exception ignored) { providerLbl.setText("Provider: -"); }
    }

    private void onNewChat()
    {
        agent.reset();
        messages.removeAll();
        turnTools = new ArrayList<>();
        setStatus(" ");
        addAssistantText("New chat. What would you like to do?");
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
            addUserText(text);
            input.setText("");
            addErrorText("No API key set for " + type + ". Add one in the plugin config "
                + "under the \"AI Chat\" section (settings cog -> OSRS MCP), then try again.");
            updateProviderLabel();
            return;
        }

        addUserText(text);
        input.setText("");
        setBusy(true);
        turnTools = new ArrayList<>();
        setStatus("Thinking...");
        agent.sendUserMessage(text, new UiListener());
    }

    private boolean activeKeyMissing(LlmProviderType type)
    {
        String key;
        switch (type)
        {
            case ANTHROPIC: key = config.anthropicKey(); break;
            case OPENAI:    key = config.openAiKey();    break;
            case DEEPSEEK:  key = config.deepSeekKey();  break;
            case HOSTED:    key = config.hostedToken();  break;
            default:        key = "";
        }
        return key == null || key.trim().isEmpty();
    }

    private void setBusy(boolean b)
    {
        busy = b;
        sendBtn.setEnabled(!b);
        sendBtn.setText(b ? "..." : "Send");
        input.setEnabled(!b);
    }

    private void setStatus(String s)
    {
        statusLbl.setText(s == null || s.isEmpty() ? " " : s);
    }

    // ── agent callbacks (fire on the worker thread -> marshal to EDT) ─────────────

    private final class UiListener implements CompanionAgent.Listener
    {
        @Override public void onAssistantText(String text)
        {
            SwingUtilities.invokeLater(() -> addAssistantText(text));
        }

        @Override public void onToolCall(String toolName)
        {
            SwingUtilities.invokeLater(() ->
            {
                turnTools.add(toolName);
                setStatus("Checking: " + toolName + "...");
            });
        }

        @Override public void onToolResult(String toolName) { /* status advances on next call */ }

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
                addErrorText(message != null ? message : "Something went wrong.");
                setStatus(" ");
                setBusy(false);
            });
        }
    }

    // ── message rendering ─────────────────────────────────────────────────────────

    private void addUserText(String text)      { addBubble("You",       text, USER_BG, Color.WHITE); }
    private void addAssistantText(String text)  { addBubble("Companion", text, CARD_BG, ColorScheme.LIGHT_GRAY_COLOR); }
    private void addErrorText(String text)      { addBubble("Error",     text, CARD_BG, ERROR_COLOR); }

    private void addBubble(String role, String text, Color bg, Color fg)
    {
        JPanel bubble = new JPanel();
        bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
        bubble.setBackground(bg);
        bubble.setAlignmentX(LEFT_ALIGNMENT);
        bubble.setBorder(new CompoundBorder(
            new MatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(5, 7, 6, 7)));
        bubble.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JLabel roleLbl = new JLabel(role.toUpperCase());
        roleLbl.setFont(FontManager.getRunescapeSmallFont());
        roleLbl.setForeground(ColorScheme.BRAND_ORANGE);
        roleLbl.setAlignmentX(LEFT_ALIGNMENT);
        bubble.add(roleLbl);
        bubble.add(Box.createVerticalStrut(2));

        JLabel body = new JLabel("<html><body style='width:" + BUBBLE_TEXT_WIDTH + "px'>"
            + toHtml(text) + "</body></html>");
        body.setFont(FontManager.getRunescapeSmallFont());
        body.setForeground(fg);
        body.setAlignmentX(LEFT_ALIGNMENT);
        bubble.add(body);

        messages.add(bubble);
        messages.add(Box.createVerticalStrut(6));
        messages.revalidate();
        scrollToBottom();
    }

    /** A faint footer line under the last reply: tools used + token counts. */
    private void addMeta(int inTok, int outTok, List<String> tools)
    {
        StringBuilder sb = new StringBuilder();
        if (tools != null && !tools.isEmpty())
            sb.append(tools.size()).append(tools.size() == 1 ? " tool" : " tools").append(" - ");
        sb.append(inTok).append(" in / ").append(outTok).append(" out");

        JLabel meta = new JLabel(sb.toString());
        meta.setFont(FontManager.getRunescapeSmallFont());
        meta.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
        meta.setAlignmentX(LEFT_ALIGNMENT);
        meta.setBorder(new EmptyBorder(0, 4, 6, 0));
        if (tools != null && !tools.isEmpty())
            meta.setToolTipText("Checked: " + String.join(", ", tools));

        messages.add(meta);
        messages.add(Box.createVerticalStrut(4));
        messages.revalidate();
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

    /** Escape HTML and preserve line breaks so the JLabel renders user/model text safely. */
    private static String toHtml(String s)
    {
        if (s == null) return "";
        String esc = s.replace("&", "&amp;")
                      .replace("<", "&lt;")
                      .replace(">", "&gt;")
                      .replace("\n", "<br>");
        return esc;
    }

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
