package com.runeassist.flip.ui;

import com.runeassist.flip.controller.GrandExchange;
import com.runeassist.flip.model.ComposeSuggestionResponse;
import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionManager;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.TextComponent;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.List;

/**
 * A small badge on each GE slot on the offers screen: your price against the side
 * that has to take it, and how much is trading there. Hover for the full note.
 * Answers "is this offer dormant or just slow?" without opening anything.
 */
@Singleton
public class OfferHealthOverlay extends Overlay {
    private static final int GE_GROUP = 465;
    private static final int SLOT_CHILD_OFFSET = 7;
    private static final Color ABOVE = new Color(0xE05252);
    private static final Color OK = new Color(0x4CC27A);
    private static final Color THIN = new Color(0xB0B0B0);

    private final Client client;
    private final SuggestionManager suggestionManager;
    private final GrandExchange grandExchange;
    private final TooltipManager tooltipManager;
    private final TextComponent text = new TextComponent();

    @Inject
    public OfferHealthOverlay(Client client, SuggestionManager suggestionManager, GrandExchange grandExchange,
                              TooltipManager tooltipManager) {
        this.client = client;
        this.suggestionManager = suggestionManager;
        this.grandExchange = grandExchange;
        this.tooltipManager = tooltipManager;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        text.setFont(FontManager.getRunescapeSmallFont());
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!grandExchange.isOpen() || grandExchange.isSlotOpen()) return null;
        Suggestion suggestion = suggestionManager.getSuggestion();
        if (suggestion == null) return null;
        List<ComposeSuggestionResponse.OfferHealthDto> rows = suggestion.getOfferHealth();
        if (rows == null || rows.isEmpty()) return null;
        Point mouse = client.getMouseCanvasPosition();
        for (ComposeSuggestionResponse.OfferHealthDto row : rows) {
            if (row == null || row.getSlot() < 0 || row.getSlot() > 7) continue;
            Widget slot = client.getWidget(GE_GROUP, row.getSlot() + SLOT_CHILD_OFFSET);
            if (slot == null || slot.isHidden()) continue;
            Rectangle bounds = slot.getBounds();
            if (bounds == null) continue;
            String label = label(row);
            if (label.isEmpty()) continue;
            text.setText(label);
            text.setColor(color(row));
            text.setPosition(new java.awt.Point(bounds.x + 6, bounds.y + bounds.height - 6));
            text.render(graphics);
            if (mouse != null && bounds.contains(mouse.getX(), mouse.getY()) && row.getNote() != null && !row.getNote().isEmpty()) {
                tooltipManager.add(new Tooltip(tooltip(row)));
            }
        }
        return null;
    }

    static String label(ComposeSuggestionResponse.OfferHealthDto row) {
        String v = row.getVerdict() == null ? "" : row.getVerdict();
        switch (v) {
            case "complete":
                return "Done";
            case "above-market":
                return signed(row.getGapPct()) + (row.isBuy() ? " under" : " over") + " \u00b7 " + row.getVolPerHour() + "/h";
            case "thin":
                return "at market \u00b7 0/h";
            case "on-track":
            case "filling":
                return (v.equals("filling") ? "filling" : "at market") + " \u00b7 " + row.getVolPerHour() + "/h"
                        + (row.getEtaHours() != null ? " \u00b7 ~" + trim(row.getEtaHours()) + "h" : "");
            default:
                return "";
        }
    }

    static Color color(ComposeSuggestionResponse.OfferHealthDto row) {
        String v = row.getVerdict() == null ? "" : row.getVerdict();
        if (v.equals("above-market")) return ABOVE;
        if (v.equals("thin") || v.equals("no-price") || v.equals("unknown")) return THIN;
        return OK;
    }

    private static String tooltip(ComposeSuggestionResponse.OfferHealthDto row) {
        StringBuilder sb = new StringBuilder(row.getNote());
        if (row.getRepriceTo() != null && row.getRepriceCostGp() != null) {
            sb.append("</br>Match the market: ").append(UIUtilities.quantityToRSDecimalStack(row.getRepriceTo(), false))
                    .append(" (").append(row.isBuy() ? "costs " : "gives up ")
                    .append(UIUtilities.quantityToRSDecimalStack(row.getRepriceCostGp(), false)).append(" gp)");
        }
        return sb.toString();
    }

    private static String signed(Double pct) {
        if (pct == null) return "?";
        double abs = Math.abs(pct);
        return (abs >= 10 ? String.format("%.0f", abs) : String.format("%.1f", abs)) + "%";
    }

    private static String trim(Double hours) {
        return hours >= 10 ? String.format("%.0f", hours) : String.format("%.1f", hours);
    }
}
