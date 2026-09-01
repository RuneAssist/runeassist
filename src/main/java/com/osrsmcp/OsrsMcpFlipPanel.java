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
import java.util.List;
import java.util.Map;

/**
 * RuneAssist "Flips" tab — the flip model (get_flip_suggestions) as a dedicated panel:
 * enter your capital, hit Refresh, get a ranked list of market-wide flips with margin,
 * quantity and projected profit. Read-only advice; the flip scoring runs off the EDT.
 */
@Slf4j
@Singleton
public class OsrsMcpFlipPanel extends PluginPanel
{
    private static final Color CARD_BG = ColorScheme.DARKER_GRAY_COLOR;
    private static final Color ACCENT  = new Color(124, 138, 255);

    @Inject private PlayerDataService playerDataService;

    private final JTextField capitalField = new JTextField("10m");
    private final JButton    refreshBtn   = new JButton("Find flips");
    private final JPanel      results      = new JPanel();
    private final JLabel      status       = new JLabel(" ");

    public OsrsMcpFlipPanel()
    {
        super(false);
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        add(buildHeader(), BorderLayout.NORTH);

        results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));
        results.setBackground(ColorScheme.DARK_GRAY_COLOR);
        results.setBorder(new EmptyBorder(6, 10, 10, 10));
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
        wrap.add(results, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(wrap,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
    }

    private JPanel buildHeader()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(ColorScheme.DARK_GRAY_COLOR);
        p.setBorder(new EmptyBorder(10, 10, 6, 10));

        JLabel title = new JLabel("RuneAssist Flips");
        title.setFont(new Font("SansSerif", Font.BOLD, 16));
        title.setForeground(Color.WHITE);
        title.setAlignmentX(LEFT_ALIGNMENT);
        p.add(title);
        p.add(Box.createVerticalStrut(6));

        JLabel cap = new JLabel("CAPITAL");
        cap.setFont(FontManager.getRunescapeSmallFont());
        cap.setForeground(ACCENT);
        cap.setAlignmentX(LEFT_ALIGNMENT);
        p.add(cap);

        capitalField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        capitalField.setAlignmentX(LEFT_ALIGNMENT);
        capitalField.setBackground(CARD_BG);
        capitalField.setForeground(Color.WHITE);
        capitalField.setCaretColor(Color.WHITE);
        capitalField.setBorder(new CompoundBorder(
            new MatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR), new EmptyBorder(3, 5, 3, 5)));
        capitalField.addActionListener(e -> refresh());
        p.add(capitalField);
        p.add(Box.createVerticalStrut(6));

        refreshBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
        refreshBtn.setForeground(Color.BLACK);
        refreshBtn.setBackground(ACCENT);
        refreshBtn.setFocusPainted(false);
        refreshBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        refreshBtn.setBorder(new EmptyBorder(6, 12, 6, 12));
        refreshBtn.setAlignmentX(LEFT_ALIGNMENT);
        refreshBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        refreshBtn.addActionListener(e -> refresh());
        p.add(refreshBtn);

        status.setFont(FontManager.getRunescapeSmallFont());
        status.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
        status.setAlignmentX(LEFT_ALIGNMENT);
        p.add(Box.createVerticalStrut(4));
        p.add(status);
        return p;
    }

    private void refresh()
    {
        long capital = parseCapital(capitalField.getText());
        refreshBtn.setEnabled(false);
        status.setText("Finding flips...");
        new Thread(() ->
        {
            Map<String, Object> res;
            try { res = playerDataService.buildFlipSuggestions(capital, 0, 0, 15); }
            catch (Exception ex) { res = null; }
            final Map<String, Object> r = res;
            SwingUtilities.invokeLater(() -> render(r));
        }, "runeassist-flips").start();
    }

    @SuppressWarnings("unchecked")
    private void render(Map<String, Object> r)
    {
        results.removeAll();
        refreshBtn.setEnabled(true);
        if (r == null || r.get("error") != null)
        {
            status.setText(r != null ? String.valueOf(r.get("error")) : "Failed to load prices.");
            results.revalidate(); results.repaint();
            return;
        }
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.get("suggestions");
        int count = r.get("count") instanceof Number ? ((Number) r.get("count")).intValue() : (list == null ? 0 : list.size());
        status.setText(count + " candidates - top " + (list == null ? 0 : list.size()));
        if (list != null) for (Map<String, Object> s : list) results.add(flipRow(s));
        results.revalidate(); results.repaint();
    }

    @SuppressWarnings("unchecked")
    private JPanel flipRow(Map<String, Object> s)
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(CARD_BG);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR), new EmptyBorder(5, 7, 5, 7)));

        JLabel name = new JLabel(String.valueOf(s.get("name")));
        name.setFont(new Font("SansSerif", Font.BOLD, 12));
        name.setForeground(Color.WHITE);
        name.setAlignmentX(LEFT_ALIGNMENT);
        p.add(name);

        String detail = fmt(s.get("buy_at")) + " → " + fmt(s.get("sell_at"))
            + "   +" + fmt(s.get("margin_post_tax")) + " (" + s.get("margin_pct") + "%)";
        JLabel d1 = new JLabel(detail);
        d1.setFont(FontManager.getRunescapeSmallFont());
        d1.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        d1.setAlignmentX(LEFT_ALIGNMENT);
        p.add(d1);

        String d2s = "x" + s.get("suggested_qty") + "  =  " + fmt(s.get("projected_profit")) + " profit";
        JLabel d2 = new JLabel(d2s);
        d2.setFont(FontManager.getRunescapeSmallFont());
        d2.setForeground(new Color(0, 180, 90));
        d2.setAlignmentX(LEFT_ALIGNMENT);
        p.add(d2);

        Object flags = s.get("flags");
        if (flags instanceof List && !((List<?>) flags).isEmpty())
        {
            JLabel fl = new JLabel(String.join(", ", (List<String>) flags));
            fl.setFont(FontManager.getRunescapeSmallFont());
            fl.setForeground(ColorScheme.BRAND_ORANGE);
            fl.setAlignmentX(LEFT_ALIGNMENT);
            p.add(fl);
        }
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
        wrap.setBorder(new EmptyBorder(0, 0, 6, 0));
        wrap.add(p, BorderLayout.NORTH);
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
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
}
