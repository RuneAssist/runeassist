package com.osrsmcp;

import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A Flipping-Copilot-style "Profit over time" popup: a cumulative-profit line chart built
 * from the local {@link FlipTrackerService} flip log. Each completed flip contributes its
 * realized profit; the line plots the running total against flip time. Display-only. The
 * data read is cheap and client-free (a copy from the tracker), so it runs on the EDT.
 */
public class ProfitGraphWindow extends JFrame
{
    private static final Color BG   = ColorScheme.DARK_GRAY_COLOR;
    private static final Color BG2  = ColorScheme.DARKER_GRAY_COLOR;

    private final transient FlipTrackerService flipTracker;
    private final ProfitGraphPanel graph;

    ProfitGraphWindow(FlipTrackerService flipTracker)
    {
        this.flipTracker = flipTracker;
        setTitle("RuneAssist — Profit over time");
        setSize(720, 440);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(HIDE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(10, 12, 10, 12));

        graph = new ProfitGraphPanel();
        root.add(graph, BorderLayout.CENTER);

        setContentPane(root);
    }

    /** Show + refresh. Call on the EDT. */
    public void open()
    {
        setVisible(true);
        toFront();
        graph.reload();
        repaint();
    }

    /** The cumulative-profit line chart. */
    private class ProfitGraphPanel extends JComponent
    {
        private static final int PAD_LEFT = 8;

        private long[] cumProfit = new long[0];  // running total after each flip
        private long[] times     = new long[0];  // epoch ms per flip
        private long total = 0;
        private int flips = 0;

        ProfitGraphPanel()
        {
            setBackground(BG2);
            setOpaque(true);
        }

        /** Rebuild the series from the tracker's full flip log. EDT only. */
        void reload()
        {
            List<Map<String, Object>> all;
            try { all = flipTracker.allFlips(); }
            catch (Exception e) { all = null; }

            List<Map<String, Object>> sorted = new ArrayList<>();
            if (all != null) sorted.addAll(all);
            sorted.sort(Comparator.comparingLong(f -> num(f.get("time"))));

            int n = sorted.size();
            cumProfit = new long[n];
            times = new long[n];
            long run = 0;
            for (int i = 0; i < n; i++)
            {
                Map<String, Object> f = sorted.get(i);
                run += num(f.get("profit"));
                cumProfit[i] = run;
                times[i] = num(f.get("time"));
            }
            total = run;
            flips = n;
        }

        @Override
        protected void paintComponent(Graphics g0)
        {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setColor(getBackground());
            g.fillRect(0, 0, w, h);
            g.setFont(FONT);

            if (cumProfit.length < 2)
            {
                g.setColor(TEXT);
                String msg = "Not enough completed flips yet.";
                FontMetrics fm = g.getFontMetrics();
                g.drawString(msg, (w - fm.stringWidth(msg)) / 2, h / 2);
                g.dispose();
                return;
            }

            // y-range spans the running total (may dip below 0) and always includes 0.
            long yMin = 0, yMax = 0;
            for (long v : cumProfit) { yMin = Math.min(yMin, v); yMax = Math.max(yMax, v); }
            if (yMin >= yMax) { yMax = yMin + 1; }
            long pad = Math.max(1, (yMax - yMin) / 12);
            yMin -= pad; yMax += pad;

            long tMin = times[0], tMax = times[times.length - 1];
            if (tMax <= tMin) tMax = tMin + 1;

            int left = PAD_LEFT, right = w - 52, top = 28, bottom = h - 16;

            // grid + y labels (min / mid / max)
            g.setStroke(new BasicStroke(0.8f));
            for (int k = 0; k <= 2; k++)
            {
                long val = yMin + (yMax - yMin) * k / 2;
                int y = mapY(val, yMin, yMax, top, bottom);
                g.setColor(GRID);
                g.drawLine(left, y, right, y);
                g.setColor(TEXT);
                g.drawString(signed(val), right + 4, y + 4);
            }

            // zero line (emphasised) if it sits within the visible range
            if (yMin < 0 && yMax > 0)
            {
                int zy = mapY(0, yMin, yMax, top, bottom);
                g.setColor(ZERO);
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{4}, 0));
                g.drawLine(left, zy, right, zy);
                g.setColor(TEXT);
                g.drawString("0", right + 4, zy + 4);
            }

            // header: latest total + flip count
            g.setColor(total >= 0 ? GOOD : LOSS);
            g.setFont(BOLD);
            g.drawString(signed(total) + " gp", 6, 14);
            g.setFont(FONT);
            g.setColor(TEXT);
            g.drawString(flips + " flips", 6, 26);

            // cumulative-profit line
            Color line = total >= 0 ? GOOD : LOSS;
            g.setColor(line);
            g.setStroke(new BasicStroke(1.6f));
            int px = -1, py = -1;
            for (int i = 0; i < cumProfit.length; i++)
            {
                int x = mapX(times[i], tMin, tMax, left, right);
                int y = mapY(cumProfit[i], yMin, yMax, top, bottom);
                if (px != -1) g.drawLine(px, py, x, y);
                px = x; py = y;
            }

            g.dispose();
        }

        private int mapX(long t, long tMin, long tMax, int left, int right)
        {
            if (tMax <= tMin) return left;
            return left + (int) ((t - tMin) * (double) (right - left) / (tMax - tMin));
        }

        private int mapY(long v, long vMin, long vMax, int top, int bottom)
        {
            if (vMax <= vMin) return bottom;
            return bottom - (int) ((v - vMin) * (double) (bottom - top) / (vMax - vMin));
        }
    }

    // ── shared drawing constants ────────────────────────────────────────────────

    private static final Color TEXT = new Color(210, 210, 210);
    private static final Color GRID = new Color(85, 85, 85, 90);
    private static final Color ZERO = new Color(150, 150, 150, 140);
    private static final Color GOOD = new Color(0, 200, 100);
    private static final Color LOSS = ColorScheme.PROGRESS_ERROR_COLOR;
    private static final Font  FONT = new Font("SansSerif", Font.PLAIN, 10);
    private static final Font  BOLD = new Font("SansSerif", Font.BOLD, 12);

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0; }

    private static String signed(long n) { return (n >= 0 ? "+" : "-") + fmt(Math.abs(n)); }

    private static String fmt(long n)
    {
        long a = Math.abs(n);
        if (a >= 1_000_000) return (Math.round(n / 100000.0) / 10.0) + "M";
        if (a >= 1_000) return (Math.round(n / 100.0) / 10.0) + "k";
        return String.valueOf(n);
    }
}
