package com.osrsmcp;

import net.runelite.api.Client;
import net.runelite.api.widgets.ComponentID;
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

/**
 * When the Grand Exchange price/quantity chatbox is open for the suggested item, this shows
 * the exact number to type, right by the input — the "what to type" companion to the
 * button highlight. Detection (chatbox title text) and the idea of surfacing the value at the
 * input are adapted from Flipping Copilot's {@code OfferEditor}/{@code OfferHandler} (BSD
 * 2-Clause, Copyright (c) 2024 Cillian Brewitt; see THIRD_PARTY_LICENSES.md).
 *
 * <p>Deliberately DISPLAY-ONLY: unlike Flipping Copilot, it does not offer a click/keybind to
 * fill the value in. It draws the number as an overlay over the chatbox (no game-widget
 * mutation) — you still type it yourself.
 */
@Singleton
public class GeOfferHintOverlay extends Overlay
{
    private static final Color ACCENT = new Color(124, 138, 255);
    private static final Color BG     = new Color(20, 20, 30, 220);
    private static final Font  FONT   = new Font("SansSerif", Font.BOLD, 13);

    private final Client client;
    private final OsrsMcpConfig config;
    private final SharedFlipState flip;
    private final GeWidgets ge;

    @Inject
    GeOfferHintOverlay(Client client, OsrsMcpConfig config, SharedFlipState flip)
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

        Widget title = client.getWidget(ComponentID.CHATBOX_TITLE);
        if (title == null || title.isHidden()) return null;
        String t = title.getText();
        if (t == null) return null;

        boolean quantity = t.equals("How many do you wish to buy?") || t.equals("How many do you wish to sell?");
        boolean price    = t.equals("Set a price for each item:");
        if (!quantity && !price) return null;

        // Only hint when the item being configured is the one we suggested.
        if (ge.setupItemId() != flip.itemId) return null;

        long value;
        String label;
        if (quantity)
        {
            if (flip.qty <= 0) return null;
            value = flip.qty; label = "RuneAssist qty";
        }
        else
        {
            value = ge.setupIsBuy() ? flip.buyAt : flip.sellAt;
            if (value <= 0) return null;
            label = "RuneAssist price";
        }

        Widget container = client.getWidget(ComponentID.CHATBOX_CONTAINER);
        Rectangle b = container != null ? container.getBounds() : title.getBounds();
        if (b == null) return null;

        String text = label + ": " + String.format("%,d", value) + (price ? " gp" : "");
        g.setFont(FONT);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text), th = fm.getHeight();
        int x = b.x + 8;
        int y = b.y - 6;              // sit just above the chatbox so it never covers the input
        if (y - th < 0) y = b.y + th; // fallback if against the top edge

        g.setColor(BG);
        g.fillRect(x - 4, y - th, tw + 8, th + 6);
        g.setColor(ACCENT);
        g.drawString(text, x, y);
        return null;
    }
}
