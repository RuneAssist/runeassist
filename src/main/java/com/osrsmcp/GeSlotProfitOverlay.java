package com.osrsmcp;

import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Grand Exchange "slot profit colorizer": while the GE is open, draws each active sell offer's
 * running profit/loss next to its slot, coloured green (in profit) or red (at a loss). Profit
 * so far = (askPrice - avgBuy) * quantitySold, less the 2% GE sell tax on those units; the
 * average buy cost comes from the local {@link FlipTrackerService} open-position snapshot.
 * Buy offers (no realized sale yet) show a neutral dash.
 *
 * <p>The idea and per-slot profit read are adapted from Flipping Copilot's
 * {@code SlotProfitColorizer} (BSD 2-Clause, Copyright (c) 2024 Cillian Brewitt,
 * https://github.com/cbrewitt/flipping-copilot). Our version is an {@link OverlayLayer#ABOVE_WIDGETS}
 * overlay rather than a widget mutation: it anchors to each slot widget's bounds and never
 * changes any game widget. Purely display-only. See THIRD_PARTY_LICENSES.md.
 */
@Singleton
public class GeSlotProfitOverlay extends Overlay
{
    private static final Color PROFIT = new Color(0, 200, 100);
    private static final Color LOSS   = ColorScheme.PROGRESS_ERROR_COLOR;
    private static final Color NEUTRAL = new Color(190, 190, 190);
    private static final Color BOX_BG  = new Color(0, 0, 0, 170);

    private final Client client;
    private final OsrsMcpConfig config;
    private final FlipTrackerService flipTracker;
    private final GeWidgets ge;

    @Inject
    GeSlotProfitOverlay(Client client, OsrsMcpConfig config, FlipTrackerService flipTracker)
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

        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null) return null;

        Map<Integer, Long> avgBuy = openPositionAvgBuy();
        FontMetrics fm = g.getFontMetrics();

        for (int slot = 0; slot < offers.length; slot++)
        {
            GrandExchangeOffer o = offers[slot];
            if (o == null) continue;
            GrandExchangeOfferState st = o.getState();
            if (st == null || st == GrandExchangeOfferState.EMPTY) continue;

            Widget w = ge.slotWidget(slot);
            if (w == null || w.isHidden()) continue;
            Rectangle b = w.getBounds();
            if (b == null || b.width <= 0 || b.height <= 0) continue;

            String text;
            Color color;
            Long profit = sellProfit(o, st, avgBuy);
            if (profit == null)
            {
                text = "—";
                color = NEUTRAL;
            }
            else
            {
                text = formatGp(profit);
                color = profit >= 0 ? PROFIT : LOSS;
            }

            int tw = fm.stringWidth(text);
            int th = fm.getHeight();
            // Anchor to the bottom-left corner of the slot, nudged inside its border.
            int x = b.x + 4;
            int y = b.y + b.height - 5;
            g.setColor(BOX_BG);
            g.fillRect(x - 2, y - fm.getAscent() - 1, tw + 4, th - fm.getLeading() + 2);
            g.setColor(color);
            g.drawString(text, x, y);
        }
        return null;
    }

    /**
     * Running profit for a sell offer, or {@code null} when it should not show a figure
     * (a buy offer, nothing sold yet, or no recorded buy cost for the item).
     */
    private Long sellProfit(GrandExchangeOffer o, GrandExchangeOfferState st, Map<Integer, Long> avgBuy)
    {
        boolean sell = st == GrandExchangeOfferState.SELLING || st == GrandExchangeOfferState.SOLD;
        if (!sell) return null;

        int qtySold = o.getQuantitySold();
        if (qtySold <= 0) return null;

        int itemId = o.getItemId();
        Long buy = avgBuy.get(itemId);
        if (buy == null) return null;

        long price = o.getPrice();
        long taxPerItem = GeTax.taxAmount(itemId, price);
        return (price - buy - taxPerItem) * qtySold;
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

    /** Compact signed gp, e.g. +12.3k / -1.2M / +523. */
    static String formatGp(long gp)
    {
        String sign = gp >= 0 ? "+" : "-";
        long a = Math.abs(gp);
        if (a < 1_000) return sign + a;
        if (a < 1_000_000) return sign + trim(a / 1_000.0) + "k";
        if (a < 1_000_000_000) return sign + trim(a / 1_000_000.0) + "M";
        return sign + trim(a / 1_000_000_000.0) + "B";
    }

    private static String trim(double v)
    {
        String s = String.format(Locale.US, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
