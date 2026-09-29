package com.runeassist.flip.ui;

import com.runeassist.flip.HeldCostTracker;
import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.controller.GrandExchange;
import com.runeassist.flip.model.OfferManager;
import com.runeassist.flip.model.SavedOffer;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Player;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/**
 * How long each live offer has been listed, drawn in the corner of its Grand Exchange slot,
 * with the time its card expected when one is known: "12m / ~50m".
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OfferAgeOverlay extends Overlay {

    private static final int GE_SLOT_COUNT = 8;
    private static final int FIRST_SLOT_CHILD_ID = 7;
    private static final int MARGIN = 5;
    private static final long REFRESH_MS = 1000L;
    private static final Color TEXT_COLOR = new Color(0xC8C8C8);

    private final Client client;
    private final RuneAssistConfig config;
    private final GrandExchange grandExchange;
    private final HeldCostTracker heldCostTracker;
    private final OfferManager offerManager;

    private final String[] labels = new String[GE_SLOT_COUNT];
    private final String[] shortLabels = new String[GE_SLOT_COUNT];
    private long refreshedAtMs;

    {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.MANUAL);
        setPriority(PRIORITY_LOW);
        drawAfterInterface(InterfaceID.GE_OFFERS);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.slotOfferAge() || !grandExchange.isOpen() || grandExchange.isSlotOpen()) {
            return null;
        }
        refreshLabels(System.currentTimeMillis());
        graphics.setFont(FontManager.getRunescapeSmallFont());
        for (int slot = 0; slot < GE_SLOT_COUNT; slot++) {
            String label = labels[slot];
            Widget widget = client.getWidget(InterfaceID.GE_OFFERS, FIRST_SLOT_CHILD_ID + slot);
            if (label == null || widget == null || widget.isHidden()) {
                continue;
            }
            Rectangle bounds = widget.getBounds();
            if (bounds == null || bounds.width <= 0) {
                continue;
            }
            if (graphics.getFontMetrics().stringWidth(label) > bounds.width - 2 * MARGIN) {
                label = shortLabels[slot];
            }
            int x = bounds.x + bounds.width - MARGIN - graphics.getFontMetrics().stringWidth(label);
            int y = bounds.y + MARGIN + graphics.getFontMetrics().getAscent();
            OverlayUtil.renderTextLocation(graphics, new net.runelite.api.Point(x, y), label, TEXT_COLOR);
        }
        return null;
    }

    private void refreshLabels(long nowMs) {
        if (nowMs - refreshedAtMs < REFRESH_MS) {
            return;
        }
        refreshedAtMs = nowMs;
        Player player = client.getLocalPlayer();
        String displayName = player != null ? player.getName() : null;
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        for (int slot = 0; slot < GE_SLOT_COUNT; slot++) {
            labels[slot] = null;
            shortLabels[slot] = null;
            if (displayName == null || offers == null || slot >= offers.length || offers[slot] == null) {
                continue;
            }
            GrandExchangeOfferState state = offers[slot].getState();
            if (state != GrandExchangeOfferState.BUYING && state != GrandExchangeOfferState.SELLING) {
                continue;
            }
            long listedMs = heldCostTracker.listedMs(displayName, slot, offers[slot].getItemId());
            shortLabels[slot] = formatAge(nowMs - listedMs, listedMs);
            labels[slot] = formatProgress(shortLabels[slot], expectedSeconds(slot, offers[slot].getItemId()));
        }
    }

    private long expectedSeconds(int slot, int itemId) {
        try {
            SavedOffer saved = offerManager.loadOffer(client.getAccountHash(), slot);
            return saved != null && saved.getItemId() == itemId ? saved.getExpectedSeconds() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** "12m / ~50m" when the card gave an estimate, the age alone otherwise. */
    static String formatProgress(String age, long expectedSeconds) {
        if (age == null || expectedSeconds <= 0) {
            return age;
        }
        return age + " / ~" + formatAge(Math.max(60_000L, expectedSeconds * 1000L), 1L);
    }

    /** "under 1m", "12m", "1h 05m", "2d 3h"; null when the listing time is unknown. */
    static String formatAge(long ageMs, long listedMs) {
        if (listedMs <= 0 || ageMs < 0) {
            return null;
        }
        long minutes = ageMs / 60_000L;
        if (minutes < 1) {
            return "<1m";
        }
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return String.format("%dh %02dm", hours, minutes % 60);
        }
        return String.format("%dd %dh", hours / 24, hours % 24);
    }
}
