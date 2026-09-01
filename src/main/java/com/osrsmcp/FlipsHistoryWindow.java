package com.osrsmcp;

import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.List;
import java.util.Map;

/**
 * A Flipping-Copilot-style "Flips history" popup: every completed flip recorded by the local
 * {@link FlipTrackerService}, in a sortable table with a totals header. Display-only. The data
 * read is cheap and client-free (a copy from the tracker), so no background thread is needed;
 * the model is still updated on the EDT.
 */
public class FlipsHistoryWindow extends JFrame
{
    private static final Color BG   = ColorScheme.DARK_GRAY_COLOR;
    private static final Color BG2  = ColorScheme.DARKER_GRAY_COLOR;
    private static final Color TXT  = new Color(220, 220, 220);
    private static final Color GOOD = new Color(0, 200, 100);
    private static final Color LOSS = ColorScheme.PROGRESS_ERROR_COLOR;
    private static final Color ACCENT = new Color(124, 138, 255);

    private final transient FlipTrackerService flipTracker;

    private final JLabel totalProfit = value();
    private final JLabel flipCount = value();

    private final DefaultTableModel model = new DefaultTableModel(
        new Object[]{ "Item", "Qty", "Buy", "Sell", "Tax", "Profit", "When" }, 0)
    {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };

    FlipsHistoryWindow(FlipTrackerService flipTracker)
    {
        this.flipTracker = flipTracker;
        setTitle("RuneAssist — Flips history");
        setSize(720, 440);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(HIDE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(10, 12, 10, 12));
        root.add(buildSummary(), BorderLayout.NORTH);

        JTable table = new JTable(model);
        table.setBackground(BG2);
        table.setForeground(TXT);
        table.setGridColor(ColorScheme.MEDIUM_GRAY_COLOR);
        table.setRowHeight(22);
        table.getTableHeader().setBackground(BG2);
        table.getTableHeader().setForeground(ACCENT);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        // Colour the Profit column by sign.
        DefaultTableCellRenderer signed = new DefaultTableCellRenderer()
        {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col)
            {
                Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                String s = String.valueOf(v);
                c.setForeground(s.startsWith("-") ? LOSS : GOOD);
                return c;
            }
        };
        table.getColumnModel().getColumn(5).setCellRenderer(signed);
        JScrollPane sp = new JScrollPane(table);
        sp.getViewport().setBackground(BG2);
        sp.setBorder(null);
        root.add(sp, BorderLayout.CENTER);

        setContentPane(root);
    }

    private JPanel buildSummary()
    {
        JPanel p = new JPanel(new GridLayout(0, 4, 12, 4));
        p.setBackground(BG);
        p.setBorder(new EmptyBorder(0, 0, 10, 0));
        p.add(label("Total profit")); p.add(totalProfit);
        p.add(label("Flips"));        p.add(flipCount);
        return p;
    }

    /** Show + refresh. Call on the EDT. */
    public void open()
    {
        setVisible(true);
        toFront();
        refresh();
    }

    /** Rebuild the table from the tracker's full flip log (newest first). EDT only. */
    public void refresh()
    {
        List<Map<String, Object>> flips;
        try { flips = flipTracker.allFlips(); }
        catch (Exception e) { flips = null; }

        model.setRowCount(0);
        long total = 0;
        int count = 0;
        if (flips != null) for (int i = flips.size() - 1; i >= 0; i--) // newest first
        {
            Map<String, Object> f = flips.get(i);
            long profit = num(f.get("profit"));
            total += profit;
            count++;
            model.addRow(new Object[]{
                String.valueOf(f.get("name")),
                fmt(num(f.get("qty"))),
                fmt(num(f.get("buy_at"))),
                fmt(num(f.get("sell_at"))),
                fmt(num(f.get("tax"))),
                signed(profit),
                heldFor(num(f.get("time")))
            });
        }

        totalProfit.setText(signed(total));
        totalProfit.setForeground(total >= 0 ? GOOD : LOSS);
        flipCount.setText(String.valueOf(count));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static JLabel label(String t)
    {
        JLabel l = new JLabel(t);
        l.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
        return l;
    }

    private static JLabel value()
    {
        JLabel l = new JLabel("0");
        l.setForeground(TXT);
        l.setFont(new Font("SansSerif", Font.BOLD, 13));
        return l;
    }

    private static long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0; }

    private static String signed(long n) { return (n >= 0 ? "+" : "-") + fmt(Math.abs(n)); }

    private static String fmt(long n)
    {
        long a = Math.abs(n);
        if (a >= 1_000_000) return (Math.round(n / 100000.0) / 10.0) + "M";
        if (a >= 1_000) return (Math.round(n / 100.0) / 10.0) + "k";
        return String.valueOf(n);
    }

    private static String heldFor(long sinceMs)
    {
        if (sinceMs <= 0) return "—";
        long s = (System.currentTimeMillis() - sinceMs) / 1000;
        if (s < 90) return s + "s";
        long m = s / 60;
        if (m < 90) return m + "m";
        long h = m / 60;
        if (h < 48) return h + "h";
        return (h / 24) + "d";
    }
}
