package com.osrsmcp;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Inventory cost-basis annotator: while the Grand Exchange is open, labels each inventory item the
 * player currently HOLDS AS A TRACKED POSITION with its average buy price, so the cost basis is
 * visible when deciding whether to sell. A tiny "avg 2.6k" tag is drawn on a dark translucent box
 * at the bottom of the item icon. The average buy cost comes from the local
 * {@link FlipTrackerService} open-position snapshot; no live price is fetched. Purely display-only.
 *
 * <p>Adapted from Flipping Copilot's {@code InventorySlotTooltipOverlay} (BSD 2-Clause, Copyright
 * (c) 2024 Cillian Brewitt, https://github.com/cbrewitt/flipping-copilot). Our version is an
 * {@link OverlayLayer#ABOVE_WIDGETS} overlay anchored to each GE-inventory item widget's bounds and
 * never mutates any game widget. See THIRD_PARTY_LICENSES.md.
 */
@Singleton
public class GeInventoryCostOverlay extends Overlay
{
    private static final Color TEXT   = new Color(235, 235, 235);
    private static final Color BOX_BG = new Color(0, 0, 0, 170);
    private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);

    private final Client client;
    private final OsrsMcpConfig config;
    private final FlipTrackerService flipTracker;
    private final GeWidgets ge;

    @Inject
    GeInventoryCostOverlay(Client client, OsrsMcpConfig config, FlipTrackerService flipTracker)
    {
        this.client = client;
        this.config = config;
        this.flipTracker = flipTracker;
        this.ge = new GeWidgets(client);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPosition(OverlayPosition.DYNAMIC);
        setPriority(PRIORITY_LOW);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (config == null || !config.screenOverlay()) return null;
        if (!ge.isOpen()) return null;

        Widget inv = client.getWidget(467, 0); // GE inventory container
        if (inv == null) return null;
        Widget[] children = inv.getDynamicChildren();
        if (children == null) return null;

        Map<Integer, Long> avgBuy = openPositionAvgBuy();
        if (avgBuy.isEmpty()) return null;

        g.setFont(LABEL_FONT);
        FontMetrics fm = g.getFontMetrics();

        for (Widget w : children)
        {
            if (w == null || w.isHidden()) continue;
            int itemId = w.getItemId();
            if (itemId <= 0 || w.getItemQuantity() <= 0) continue;
            Long buy = avgBuy.get(itemId);
            if (buy == null) continue;

            Rectangle b = w.getBounds();
            if (b == null || b.width <= 0 || b.height <= 0) continue;

            String text = "avg " + formatGp(buy);
            int tw = fm.stringWidth(text);
            int th = fm.getHeight();
            // Anchor to the bottom of the icon, clamped so the box stays within the icon's width.
            int x = b.x + 1;
            int y = b.y + b.height - 2;
            g.setColor(BOX_BG);
            g.fillRect(x - 1, y - fm.getAscent(), tw + 2, th - fm.getLeading());
            g.setColor(TEXT);
            g.drawString(text, x, y - fm.getDescent() + 1);
        }
        return null;
    }

    /** itemId -> average buy cost, from the client-free flip-tracker snapshot. */
    @SuppressWarnings("unchecked")
    private Map<Integer, Long> openPositionAvgBuy()
    {
        Map<Integer, Long> out = new HashMap<>();
        try
        {
            Map<String, Object> snap = flipTracker.snapshot();
            Object open = snap.get("open_positions");
            if (!(open instanceof List)) return out;
            for (Object row : (List<Object>) open)
            {
                if (!(row instanceof Map)) continue;
                Map<String, Object> p = (Map<String, Object>) row;
                Object id = p.get("item_id");
                Object avg = p.get("avg_buy");
                if (id instanceof Number && avg instanceof Number)
                    out.put(((Number) id).intValue(), ((Number) avg).longValue());
            }
        }
        catch (Exception ignored) {}
        return out;
    }

    /** Compact unsigned gp, e.g. 523 / 2.6k / 1.2M / 3B. */
    static String formatGp(long gp)
    {
        long a = Math.abs(gp);
        if (a < 1_000) return Long.toString(a);
        if (a < 1_000_000) return trim(a / 1_000.0) + "k";
        if (a < 1_000_000_000) return trim(a / 1_000_000.0) + "M";
        return trim(a / 1_000_000_000.0) + "B";
    }

    private static String trim(double v)
    {
        String s = String.format(Locale.US, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
