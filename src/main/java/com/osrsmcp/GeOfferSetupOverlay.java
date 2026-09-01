package com.osrsmcp;

import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Map;

/**
 * Display-only helper for the Grand Exchange OFFER-SETUP screen (the buy/sell screen where
 * you pick item, price and quantity, BEFORE confirming). When that screen is open, it reads
 * the item being configured and shows the flip model's suggested buy/sell, post-tax margin,
 * 1h volume and a verdict — plus whether the price you've typed looks high or low.
 *
 * <p>Purely advisory: it reads varbits/varp and draws a panel. It NEVER sets a price, picks
 * an item, or confirms anything. Constants verified against runelite-api 1.12.37. The price
 * lookup ({@link PlayerDataService#flipQuoteForItem}) can block, so it runs on a background
 * thread and the render path only paints a cached quote for the current item. Gated by
 * {@code screenOverlay}.
 */
@Singleton
public class GeOfferSetupOverlay extends Overlay
{
    private static final Color ACCENT = new Color(124, 138, 255);
    private static final Color GOOD   = new Color(0, 200, 100);
    private static final Color WARN   = new Color(220, 138, 0); // brand orange

    private final Client client;
    private final OsrsMcpConfig config;
    private final PlayerDataService playerDataService;
    private final PanelComponent panel = new PanelComponent();

    // Cached quote for the current item, computed off the render thread.
    private volatile int cachedItemId = -1;
    private volatile Map<String, Object> cachedQuote = null;
    private volatile boolean loading = false;

    @Inject
    GeOfferSetupOverlay(Client client, OsrsMcpConfig config, PlayerDataService playerDataService)
    {
        this.client = client;
        this.config = config;
        this.playerDataService = playerDataService;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (config == null || !config.screenOverlay()) return null;

        Widget container = client.getWidget(WidgetInfo.GRAND_EXCHANGE_OFFER_CONTAINER);
        if (container == null || container.isHidden()) return null; // setup screen not open

        int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
        if (itemId <= 0) return null; // no item chosen yet

        boolean buying   = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 0;
        int enteredPrice = client.getVarbitValue(VarbitID.GE_NEWOFFER_PRICE);
        int enteredQty   = client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY);

        // Kick a background price fetch when the item changes; never fetch in render.
        if (itemId != cachedItemId && !loading)
        {
            loading = true;
            final int wanted = itemId;
            new Thread(() ->
            {
                Map<String, Object> q;
                try { q = playerDataService.flipQuoteForItem(wanted); }
                catch (Exception e) { q = null; }
                cachedQuote = q;
                cachedItemId = wanted;
                loading = false;
            }, "runeassist-ge-quote").start();
        }

        panel.getChildren().clear();
        panel.setPreferredSize(new Dimension(200, 0));
        panel.getChildren().add(TitleComponent.builder()
            .text("RuneAssist · " + (buying ? "Buy" : "Sell"))
            .color(ACCENT).build());

        Map<String, Object> q = cachedQuote;
        if (q == null || cachedItemId != itemId)
        {
            panel.getChildren().add(LineComponent.builder()
                .left("Checking price…").leftColor(Color.LIGHT_GRAY).build());
            return panel.render(g);
        }

        panel.getChildren().add(LineComponent.builder()
            .left(String.valueOf(q.get("name")))
            .leftColor(Color.WHITE).build());
        panel.getChildren().add(LineComponent.builder()
            .left("Suggested buy").right(fmt(q.get("buy_at"))).rightColor(Color.WHITE).build());
        panel.getChildren().add(LineComponent.builder()
            .left("Suggested sell").right(fmt(q.get("sell_at"))).rightColor(Color.WHITE).build());
        panel.getChildren().add(LineComponent.builder()
            .left("Margin (post-tax)")
            .right(fmt(q.get("margin_post_tax")) + " (" + q.get("margin_pct") + "%)")
            .rightColor(num(q.get("margin_post_tax")) > 0 ? GOOD : WARN).build());
        panel.getChildren().add(LineComponent.builder()
            .left("1h volume").right(fmt(q.get("volume_1h"))).rightColor(Color.LIGHT_GRAY).build());
        Object limit = q.get("ge_limit");
        if (num(limit) > 0)
            panel.getChildren().add(LineComponent.builder()
                .left("Buy limit").right(fmt(limit)).rightColor(Color.LIGHT_GRAY).build());

        // Compare what the player has typed to the suggested side.
        if (enteredPrice > 0)
        {
            long ref = num(buying ? q.get("buy_at") : q.get("sell_at"));
            String note;
            Color c;
            if (ref <= 0) { note = fmt(enteredPrice); c = Color.LIGHT_GRAY; }
            else if (buying)
            {
                boolean overpay = enteredPrice > ref;
                note = fmt(enteredPrice) + (overpay ? "  (above suggested)" : "  (ok)");
                c = overpay ? WARN : GOOD;
            }
            else
            {
                boolean undersell = enteredPrice < ref;
                note = fmt(enteredPrice) + (undersell ? "  (below suggested)" : "  (ok)");
                c = undersell ? WARN : GOOD;
            }
            panel.getChildren().add(LineComponent.builder()
                .left("Your price").right(note).rightColor(c).build());
        }
        if (enteredQty > 0)
            panel.getChildren().add(LineComponent.builder()
                .left("Your qty").right(fmt(enteredQty)).rightColor(Color.LIGHT_GRAY).build());

        Object verdict = q.get("verdict");
        if (verdict != null)
            panel.getChildren().add(LineComponent.builder()
                .left("Verdict").right(String.valueOf(verdict))
                .rightColor("skip".equals(verdict) ? WARN : ACCENT).build());

        return panel.render(g);
    }

    private static long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0; }

    private static String fmt(Object o)
    {
        long n = num(o);
        long a = Math.abs(n);
        if (a >= 1_000_000) return (Math.round(n / 100000.0) / 10.0) + "M";
        if (a >= 1_000) return (Math.round(n / 100.0) / 10.0) + "k";
        return String.valueOf(n);
    }
}
