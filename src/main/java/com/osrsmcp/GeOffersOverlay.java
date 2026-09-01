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

    private final Client client;
    private final ItemManager itemManager;
    private final OsrsMcpConfig config;
    private final PanelComponent panel = new PanelComponent();

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
        panel.setPreferredSize(new Dimension(180, 0));
        boolean any = false;
        for (GrandExchangeOffer o : offers)
        {
            if (o == null) continue;
            GrandExchangeOfferState st = o.getState();
            if (st == null || st == GrandExchangeOfferState.EMPTY) continue;
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
        }
        return any ? panel.render(g) : null;
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
