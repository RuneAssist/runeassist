package com.osrsmcp;

import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * Draws the current suggested flip ON TOP of the Grand Exchange window (like Flipping
 * Copilot), anchored to the GE widget so it's always visible while the GE is open. Uses
 * {@link OverlayLayer#ABOVE_WIDGETS} — the fix over the previous version, which rendered
 * under the GE interface and was unreadable.
 *
 * <p>Reads only: the suggested flip comes from {@link SharedFlipState} (published by the
 * Flips panel) and, when the offer-setup screen is open, it compares the price you've typed
 * (verified GE varbits) to the target. It never sets a price or places an offer.
 */
@Singleton
public class GeSuggestionOverlay extends Overlay
{
    private static final Color ACCENT = new Color(124, 138, 255);
    private static final Color GOOD   = new Color(0, 200, 100);
    private static final Color WARN   = new Color(220, 138, 0);

    private final Client client;
    private final OsrsMcpConfig config;
    private final SharedFlipState flip;
    private final PanelComponent panel = new PanelComponent();

    @Inject
    GeSuggestionOverlay(Client client, OsrsMcpConfig config, SharedFlipState flip)
    {
        this.client = client;
        this.config = config;
        this.flip = flip;
        setLayer(OverlayLayer.ABOVE_WIDGETS);   // draw on top of the GE interface
        setPosition(OverlayPosition.DYNAMIC);   // we anchor it to the GE window each frame
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (config == null || !config.screenOverlay() || !flip.valid) return null;

        Widget ge = client.getWidget(WidgetInfo.GRAND_EXCHANGE_WINDOW_CONTAINER);
        if (ge == null || ge.isHidden()) return null; // GE not open
        Rectangle b = ge.getBounds();
        if (b == null || b.width <= 0) return null;

        panel.getChildren().clear();
        panel.setPreferredSize(new Dimension(210, 0));
        boolean sell = flip.sell;
        panel.getChildren().add(TitleComponent.builder()
            .text("RuneAssist — next flip").color(ACCENT).build());
        panel.getChildren().add(LineComponent.builder()
            .left(sell ? "SELL" : "BUY").right(flip.name).leftColor(ACCENT).rightColor(Color.WHITE).build());
        panel.getChildren().add(LineComponent.builder()
            .left("Price").right(fmt(sell ? flip.sellAt : flip.buyAt)).rightColor(Color.WHITE).build());
        panel.getChildren().add(LineComponent.builder()
            .left("Quantity").right(fmt(flip.qty)).rightColor(Color.WHITE).build());
        if (!sell)
            panel.getChildren().add(LineComponent.builder()
                .left("Then sell").right(fmt(flip.sellAt)).rightColor(Color.WHITE).build());
        panel.getChildren().add(LineComponent.builder()
            .left("Profit").right("+" + fmt(flip.profit) + " (" + fmt(flip.marginPct) + "%)")
            .rightColor(GOOD).build());
        if (flip.geLimit > 0)
            panel.getChildren().add(LineComponent.builder()
                .left("4h limit left").right(fmt(flip.limitLeft) + "/" + fmt(flip.geLimit))
                .rightColor(flip.limitLeft == 0 ? WARN : Color.LIGHT_GRAY).build());

        // If the player is setting up an offer for THIS item, grade the price they've typed.
        try
        {
            int setupItem = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
            int entered   = client.getVarbitValue(VarbitID.GE_NEWOFFER_PRICE);
            boolean buying = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 0;
            if (setupItem == flip.itemId && entered > 0)
            {
                long target = buying ? flip.buyAt : flip.sellAt;
                boolean ok = buying ? entered <= target + Math.max(1, target / 100)
                                    : entered >= target - Math.max(1, target / 100);
                panel.getChildren().add(LineComponent.builder()
                    .left("Your price").right(fmt(entered) + (ok ? "  ok" : buying ? "  high" : "  low"))
                    .rightColor(ok ? GOOD : WARN).build());
            }
        }
        catch (Exception ignored) {}

        // Anchor to the right of the GE window (flip to the left if it would run off-canvas).
        int gap = 6;
        Dimension size = panel.render(g);
        int px = b.x + b.width + gap;
        if (px + size.width > client.getCanvasWidth()) px = Math.max(0, b.x - size.width - gap);
        setPreferredLocation(new Point(px, b.y));
        return size;
    }

    private static String fmt(double d) { return fmt((long) Math.round(d)); }

    private static String fmt(long n)
    {
        long a = Math.abs(n);
        if (a >= 1_000_000) return (Math.round(n / 100000.0) / 10.0) + "M";
        if (a >= 1_000) return (Math.round(n / 100.0) / 10.0) + "k";
        return String.valueOf(n);
    }
}
