package com.osrsmcp.graph;

import com.google.gson.Gson;
import net.runelite.client.ui.ColorScheme;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * A compact price-history graph for one item: the hourly buy (low) and sell (high) lines
 * over the recent window, fed by {@link Data} from the RuneAssist server's /v1/graph
 * endpoint. Consumes Flipping Copilot's graph data model (see {@link Data}); the rendering
 * here is RuneAssist's own compact side-panel drawing. Fetches off the EDT.
 */
public class PriceGraphPanel extends JComponent
{
    private static final Color LOW  = new Color(0, 153, 255);   // buy
    private static final Color HIGH = new Color(255, 102, 0);   // sell
    private static final Color GRID = new Color(85, 85, 85, 90);
    private static final Color TEXT = new Color(210, 210, 210);
    private static final Font  FONT = new Font("SansSerif", Font.PLAIN, 10);
    private static final int   MAX_POINTS = 168; // ~7 days of hourly points

    private final Gson gson;
    private String baseUrl = "";
    private int itemId = -1;
    private String title = "";
    private volatile Data data;
    private volatile String status = "No item selected.";

    public PriceGraphPanel(Gson gson)
    {
        this.gson = gson != null ? gson : new Gson();
        setPreferredSize(new Dimension(0, 150));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
        setBackground(ColorScheme.DARKER_GRAY_COLOR);
        setOpaque(true);
    }

    /** Load the graph for an item. baseUrl e.g. https://runeassist.ares-server.co.uk */
    public void setItem(String baseUrl, int itemId, String name)
    {
        if (baseUrl == null || baseUrl.isEmpty() || itemId <= 0) { clear(); return; }
        if (itemId == this.itemId && baseUrl.equals(this.baseUrl) && data != null) return;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.itemId = itemId;
        this.title = name != null ? name : ("item " + itemId);
        this.data = null;
        this.status = "Loading " + title + " prices…";
        repaint();

        final String url = this.baseUrl + "/v1/graph?id=" + itemId;
        final int wanted = itemId;
        new Thread(() ->
        {
            Data d = null; String err = null;
            try
            {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                if (code == 200)
                    d = gson.fromJson(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8), Data.class);
                else err = "Graph unavailable (HTTP " + code + ")";
                c.disconnect();
            }
            catch (Exception e) { err = "Graph fetch failed"; }
            final Data fd = d; final String fe = err;
            SwingUtilities.invokeLater(() ->
            {
                if (wanted != this.itemId) return; // superseded by a newer selection
                if (fd != null && fd.high1hPrices != null) { data = fd; status = null; }
                else status = fe != null ? fe : "No price history.";
                repaint();
            });
        }, "runeassist-graph").start();
    }

    public void clear()
    {
        itemId = -1; data = null; status = "No item selected.";
        repaint();
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

        Data d = data;
        if (d == null || d.high1hTimes == null || d.high1hTimes.length < 2)
        {
            g.setColor(TEXT);
            g.drawString(status != null ? status : "No price history.", 8, h / 2);
            g.dispose();
            return;
        }

        // Window: the last MAX_POINTS hourly points.
        int n = d.high1hTimes.length;
        int from = Math.max(0, n - MAX_POINTS);
        int count = n - from;

        // y-range across both low and high in the window.
        long yMin = Long.MAX_VALUE, yMax = Long.MIN_VALUE;
        for (int i = from; i < n; i++)
        {
            yMax = Math.max(yMax, d.high1hPrices[i]);
            yMin = Math.min(yMin, d.high1hPrices[i]);
        }
        if (d.low1hPrices != null)
            for (int i = Math.max(0, d.low1hPrices.length - count); i < d.low1hPrices.length; i++)
            {
                yMax = Math.max(yMax, d.low1hPrices[i]);
                yMin = Math.min(yMin, d.low1hPrices[i]);
            }
        if (yMin >= yMax) { yMax = yMin + 1; }
        long pad = Math.max(1, (yMax - yMin) / 12);
        yMin -= pad; yMax += pad;

        int left = 4, right = w - 52, top = 16, bottom = h - 14;
        int xMinT = d.high1hTimes[from], xMaxT = d.high1hTimes[n - 1];

        // grid + y labels (min / mid / max)
        g.setStroke(new BasicStroke(0.8f));
        for (int k = 0; k <= 2; k++)
        {
            long val = yMin + (yMax - yMin) * k / 2;
            int y = mapY(val, yMin, yMax, top, bottom);
            g.setColor(GRID);
            g.drawLine(left, y, right, y);
            g.setColor(TEXT);
            g.drawString(kmb(val), right + 4, y + 4);
        }

        // title
        g.setColor(TEXT);
        g.drawString(title, 6, 11);

        drawSeries(g, d.high1hTimes, d.high1hPrices, from, xMinT, xMaxT, yMin, yMax, left, right, top, bottom, HIGH);
        if (d.low1hTimes != null && d.low1hPrices != null)
        {
            int lf = Math.max(0, d.low1hTimes.length - count);
            drawSeries(g, d.low1hTimes, d.low1hPrices, lf, xMinT, xMaxT, yMin, yMax, left, right, top, bottom, LOW);
        }

        // legend / current values
        g.setColor(HIGH); g.drawString("sell " + kmb(d.sellPrice), 6, bottom + 12);
        g.setColor(LOW);  g.drawString("buy " + kmb(d.buyPrice), 90, bottom + 12);

        g.dispose();
    }

    private void drawSeries(Graphics2D g, int[] times, long[] prices, int from,
                            int xMinT, int xMaxT, long yMin, long yMax,
                            int left, int right, int top, int bottom, Color c)
    {
        g.setColor(c);
        g.setStroke(new BasicStroke(1.4f));
        int px = -1, py = -1;
        for (int i = from; i < times.length && i < prices.length; i++)
        {
            int x = mapX(times[i], xMinT, xMaxT, left, right);
            int y = mapY(prices[i], yMin, yMax, top, bottom);
            if (px != -1) g.drawLine(px, py, x, y);
            px = x; py = y;
        }
    }

    private static int mapX(long t, long tMin, long tMax, int left, int right)
    {
        if (tMax <= tMin) return left;
        return left + (int) ((t - tMin) * (double) (right - left) / (tMax - tMin));
    }

    private static int mapY(long v, long vMin, long vMax, int top, int bottom)
    {
        if (vMax <= vMin) return bottom;
        return bottom - (int) ((v - vMin) * (double) (bottom - top) / (vMax - vMin));
    }

    private static String kmb(long n)
    {
        long a = Math.abs(n);
        if (a >= 1_000_000) return (Math.round(n / 100000.0) / 10.0) + "M";
        if (a >= 1_000) return (Math.round(n / 100.0) / 10.0) + "k";
        return String.valueOf(n);
    }
}
