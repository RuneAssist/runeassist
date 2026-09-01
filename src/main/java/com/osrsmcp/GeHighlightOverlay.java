package com.osrsmcp;

import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/**
 * Highlights the exact Grand Exchange button the player should click next to carry out the
 * current suggested flip — the "guide me click-by-click" behaviour from Flipping Copilot:
 * on the home screen it points at an empty slot's Buy button; on the offer-setup screen it
 * points at Set Price, then Set Quantity, then Confirm as each value comes to match the
 * suggestion. Draws on {@link OverlayLayer#ABOVE_WIDGETS} so it sits over the interface.
 *
 * <p>Decision logic and widget targets are adapted from Flipping Copilot's
 * {@code HighlightController} / {@code WidgetHighlightOverlay} (BSD 2-Clause, Copyright (c)
 * 2024 Cillian Brewitt; widget-overlay technique Copyright (c) 2018 Jasper, 2020 melky —
 * https://github.com/cbrewitt/flipping-copilot), simplified to this plugin's single
 * suggestion. Purely a visual guide: it never clicks, sets a price, or places an offer.
 */
@Singleton
public class GeHighlightOverlay extends Overlay
{
    private static final Color BASE = new Color(124, 138, 255); // indigo accent

    private final Client client;
    private final OsrsMcpConfig config;
    private final SharedFlipState flip;
    private final GeWidgets ge;

    @Inject
    GeHighlightOverlay(Client client, OsrsMcpConfig config, SharedFlipState flip)
    {
        this.client = client;
        this.config = config;
        this.flip = flip;
        this.ge = new GeWidgets(client);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPosition(OverlayPosition.DYNAMIC);
        setPriority(PRIORITY_HIGH);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (config == null || !config.screenOverlay() || !flip.valid) return null;
        if (!ge.isOpen()) return null;

        Widget target = null;
        Rectangle rel = null;

        if (ge.isSetupOfferOpen())
        {
            // Only guide when the item being set up is the one we suggested.
            if (ge.setupItemId() == flip.itemId)
            {
                boolean buy = ge.setupIsBuy();
                long targetPrice = buy ? flip.buyAt : flip.sellAt;
                long targetQty   = flip.qty;
                if (ge.setupPrice() != targetPrice)
                {
                    target = ge.setPriceButton(); rel = new Rectangle(1, 6, 33, 23);
                }
                else if (targetQty > 0 && ge.setupQuantity() != targetQty)
                {
                    target = ge.setQuantityButton(); rel = new Rectangle(1, 6, 33, 23);
                }
                else
                {
                    target = ge.confirmButton(); rel = new Rectangle(1, 1, 150, 38);
                }
            }
        }
        else if (ge.isHomeScreenOpen())
        {
            int slot = firstEmptySlot();
            if (slot != -1) { target = ge.buyButton(slot); rel = new Rectangle(0, 0, 45, 44); }
        }

        if (target == null || target.isHidden()) return null;
        Rectangle b = target.getBounds();
        if (b == null) return null;
        if (rel == null) rel = new Rectangle(0, 0, target.getWidth(), target.getHeight());

        // Gentle pulse so the highlight is noticeable without being harsh.
        double phase = (System.currentTimeMillis() % 1200) / 1200.0;
        int alpha = 55 + (int) (35 * (0.5 + 0.5 * Math.sin(phase * 2 * Math.PI)));
        g.setColor(new Color(BASE.getRed(), BASE.getGreen(), BASE.getBlue(), alpha));
        g.fillRect(b.x + rel.x, b.y + rel.y, rel.width, rel.height);
        return null;
    }

    /** First GE slot with no active offer (where a new buy can go). */
    private int firstEmptySlot()
    {
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null) return 0;
        for (int i = 0; i < offers.length; i++)
        {
            GrandExchangeOffer o = offers[i];
            if (o == null || o.getState() == null || o.getState() == GrandExchangeOfferState.EMPTY)
                return i;
        }
        return -1;
    }
}
