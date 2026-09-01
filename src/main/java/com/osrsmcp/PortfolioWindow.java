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
 * A Flipping-Copilot-style Portfolio popup: your currently-held stock (from the flip
 * tracker) valued at the live market, with unrealized profit/ROI and time held, plus a
 * summary header. Display-only. Prices are fetched off the EDT.
 */
public class PortfolioWindow extends JFrame
{
    private static final Color BG   = ColorScheme.DARK_GRAY_COLOR;
    private static final Color BG2  = ColorScheme.DARKER_GRAY_COLOR;
    private static final Color TXT  = new Color(220, 220, 220);
    private static final Color GOOD = new Color(0, 200, 100);
    private static final Color LOSS = ColorScheme.PROGRESS_ERROR_COLOR;
    private static final Color ACCENT = new Color(124, 138, 255);

    private final transient FlipTrackerService flipTracker;
    private final transient PlayerDataService playerDataService;

    private final JLabel marketVal = value();
    private final JLabel unrealized = value();
    private final JLabel cashVal = value();
    private final JLabel assetsVal = value();
    private final JLabel uniqueItems = value();
    private final JLabel status = new JLabel(" ");

    private final DefaultTableModel model = new DefaultTableModel(
        new Object[]{ "Item", "Market value", "Qty", "Unrealized", "ROI", "Avg buy", "Held" }, 0)
    {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };

    PortfolioWindow(FlipTrackerService flipTracker, PlayerDataService playerDataService)
    {
        this.flipTracker = flipTracker;
        this.playerDataService = playerDataService;
        setTitle("RuneAssist — Portfolio");
        setSize(760, 420);
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
        // Colour the Unrealized + ROI columns by sign.
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
        table.getColumnModel().getColumn(3).setCellRenderer(signed);
        table.getColumnModel().getColumn(4).setCellRenderer(signed);
        JScrollPane sp = new JScrollPane(table);
        sp.getViewport().setBackground(BG2);
        sp.setBorder(null);
        root.add(sp, BorderLayout.CENTER);

        status.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
        status.setBorder(new EmptyBorder(6, 2, 0, 0));
        root.add(status, BorderLayout.SOUTH);

        setContentPane(root);
    }

    private JPanel buildSummary()
    {
        JPanel p = new JPanel(new GridLayout(0, 4, 12, 4));
        p.setBackground(BG);
        p.setBorder(new EmptyBorder(0, 0, 10, 0));
        p.add(label("Market value")); p.add(marketVal);
        p.add(label("Unrealized"));   p.add(unrealized);
        p.add(label("Cash"));         p.add(cashVal);
        p.add(label("Assets"));       p.add(assetsVal);
        p.add(label("Unique items")); p.add(uniqueItems);
        return p;
    }

    /** Show + refresh. Call on the EDT. */
    public void open()
    {
        setVisible(true);
        toFront();
        refresh();
    }

    @SuppressWarnings("unchecked")
    public void refresh()
    {
        status.setText("Loading prices…");
        List<Map<String, Object>> open;
        long coins;
        try
        {
            open = (List<Map<String, Object>>) flipTracker.snapshot().get("open_positions");
            coins = playerDataService.cachedCoins();
        }
        catch (Exception e) { status.setText("No data."); return; }
        final List<Map<String, Object>> positions = open;
        final long cash = Math.max(0, coins);

        new Thread(() ->
        {
            List<Map<String, Object>> quotes;
            try { quotes = playerDataService.buildHeldSellSuggestions(positions); }
            catch (Exception e) { quotes = null; }
            final List<Map<String, Object>> q = quotes;
            SwingUtilities.invokeLater(() -> populate(positions, q, cash));
        }, "runeassist-portfolio").start();
    }

    private void populate(List<Map<String, Object>> positions, List<Map<String, Object>> quotes, long cash)
    {
        model.setRowCount(0);
        long totalMarket = 0, totalUnreal = 0;
        int unique = 0;

        java.util.Map<Integer, Map<String, Object>> byId = new java.util.HashMap<>();
        if (quotes != null) for (Map<String, Object> qm : quotes)
            byId.put((int) num(qm.get("id")), qm);

        if (positions != null) for (Map<String, Object> p : positions)
        {
            int id = (int) num(p.get("item_id"));
            long qty = num(p.get("qty"));
            long avgBuy = num(p.get("avg_buy"));
            long since = num(p.get("since"));
            Map<String, Object> qm = byId.get(id);
            long sellAt = qm != null ? num(qm.get("sell_at")) : 0;
            long unrealEa = qm != null ? num(qm.get("margin_post_tax")) : 0;
            long unreal = qm != null ? num(qm.get("projected_profit")) : 0;
            long market = sellAt * qty;
            double roi = (avgBuy > 0 && qty > 0) ? (unreal * 100.0 / (avgBuy * qty)) : 0;

            totalMarket += market; totalUnreal += unreal; unique++;
            model.addRow(new Object[]{
                String.valueOf(p.get("name")),
                fmt(market),
                fmt(qty),
                signed(unreal),
                (roi >= 0 ? "+" : "") + (Math.round(roi * 10) / 10.0) + "%",
                fmt(avgBuy),
                heldFor(since)
            });
        }

        marketVal.setText(fmt(totalMarket + cash));
        unrealized.setText(signed(totalUnreal));
        unrealized.setForeground(totalUnreal >= 0 ? GOOD : LOSS);
        cashVal.setText(fmt(cash));
        assetsVal.setText(fmt(totalMarket));
        uniqueItems.setText(String.valueOf(unique));
        status.setText(unique == 0 ? "No open positions — buy something and it'll show here."
            : unique + " item" + (unique == 1 ? "" : "s") + " held");
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
