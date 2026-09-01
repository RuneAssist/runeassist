package com.osrsmcp;

import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.game.ItemManager;
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
import java.util.HashMap;
import java.util.Map;

/**
 * On-screen GE helper: a live HUD of the player's active Grand Exchange offers and their
 * fill progress. Uses only the stable {@code getGrandExchangeOffers()} API + cached item
 * names — no widget scraping and no network in the render path — so it's safe and
 * display-only. Gated by {@code screenOverlay}. (The price-annotated offer-setup overlay
 * is a separate, later build.)
 */
@Singleton
public class GeOffersOverlay extends Overlay
{
    private static final Color ACCENT = new Color(124, 138, 255);
    private static final Color WARN   = new Color(220, 138, 0);
    // An unfilling offer is flagged "stale" after this long with no progress.
    private static final long STALE_AFTER_MS = 5L * 60 * 1000;

    private final Client client;
    private final ItemManager itemManager;
    private final OsrsMcpConfig config;
    private final PanelComponent panel = new PanelComponent();

    /** Per-slot fill-progress tracking, so we can tell how long an offer has been stuck. */
    private static final class Progress { int itemId; int sold; long sinceChange;
        Progress(int i, int s, long t){ itemId=i; sold=s; sinceChange=t; } }
    private final Map<Integer, Progress> progress = new HashMap<>();

    @Inject
    GeOffersOverlay(Client client, ItemManager itemManager, OsrsMcpConfig config)
    {
        this.client = client;
        this.itemManager = itemManager;
        this.config = config;
        setPosition(OverlayPosition.TOP_RIGHT);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (config == null || !config.screenOverlay()) return null;
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null) return null;

        panel.getChildren().clear();
        panel.setPreferredSize(new Dimension(190, 0));
        long now = System.currentTimeMillis();
        boolean any = false;
        for (int slot = 0; slot < offers.length; slot++)
        {
            GrandExchangeOffer o = offers[slot];
            if (o == null) continue;
            GrandExchangeOfferState st = o.getState();
            if (st == null || st == GrandExchangeOfferState.EMPTY)
            {
                progress.remove(slot);
                continue;
            }
            if (!any)
            {
                panel.getChildren().add(TitleComponent.builder().text("GE Offers").color(ACCENT).build());
                any = true;
            }
            String name = safeName(o.getItemId());
            String verb = label(st);
            String prog = o.getTotalQuantity() > 0 ? o.getQuantitySold() + "/" + o.getTotalQuantity() : "";
            panel.getChildren().add(LineComponent.builder()
                .left(clip(verb + " " + name, 20))
                .right(prog)
                .rightColor(done(st) ? Color.GREEN : Color.WHITE)
                .build());

            // Track fill progress; flag an in-progress offer that hasn't moved in a while.
            long stuckMs = trackStale(slot, o.getItemId(), o.getQuantitySold(), now);
            boolean active = st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.SELLING;
            if (active && o.getQuantitySold() < o.getTotalQuantity() && stuckMs >= STALE_AFTER_MS)
            {
                boolean buy = st == GrandExchangeOfferState.BUYING;
                panel.getChildren().add(LineComponent.builder()
                    .left("  stale " + (stuckMs / 60000) + "m")
                    .right(buy ? "raise bid?" : "lower ask?")
                    .leftColor(WARN).rightColor(WARN)
                    .build());
            }
        }
        return any ? panel.render(g) : null;
    }

    /**
     * Update per-slot progress and return how long (ms) this offer has gone without a fill.
     * A new offer, a different item in the slot, or any increase in quantity sold resets the
     * clock. Runs on the client thread (render), so the map needs no synchronisation.
     */
    private long trackStale(int slot, int itemId, int sold, long now)
    {
        Progress p = progress.get(slot);
        if (p == null || p.itemId != itemId || sold < p.sold)
        {
            progress.put(slot, new Progress(itemId, sold, now));
            return 0;
        }
        if (sold > p.sold) { p.sold = sold; p.sinceChange = now; return 0; }
        return now - p.sinceChange;
    }

    private String safeName(int itemId)
    {
        try { String n = itemManager.getItemComposition(itemId).getName(); return n != null ? n : "item " + itemId; }
        catch (Exception e) { return "item " + itemId; }
    }

    private static boolean done(GrandExchangeOfferState st)
    {
        return st == GrandExchangeOfferState.BOUGHT || st == GrandExchangeOfferState.SOLD;
    }

    private static String label(GrandExchangeOfferState st)
    {
        switch (st)
        {
            case BUYING: case BOUGHT: case CANCELLED_BUY:  return "Buy";
            case SELLING: case SOLD: case CANCELLED_SELL:  return "Sell";
            default: return "";
        }
    }

    private static String clip(String s, int n)
    {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
