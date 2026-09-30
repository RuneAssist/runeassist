package com.runeassist.flip.ui;

import com.runeassist.flip.HeldCostTracker;
import com.runeassist.flip.model.OfferManager;
import com.runeassist.flip.model.SavedOffer;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Player;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * How long a live offer has been listed against the time its card expected, as a line for
 * the slot's hover tooltip: "Listed 12m ago, expected 50m", or just "Listed 12m ago" when
 * the offer did not come from a card.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OfferAge {

    private final Client client;
    private final HeldCostTracker heldCostTracker;
    private final OfferManager offerManager;

    /** The tooltip line for a slot, or null when it holds no live offer or its listing time is unknown. */
    public String tooltipLine(int slot, long nowMs) {
        Player player = client.getLocalPlayer();
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (player == null || player.getName() == null || offers == null || slot < 0 || slot >= offers.length || offers[slot] == null) {
            return null;
        }
        GrandExchangeOfferState state = offers[slot].getState();
        if (state != GrandExchangeOfferState.BUYING && state != GrandExchangeOfferState.SELLING) {
            return null;
        }
        long listedMs = heldCostTracker.listedMs(player.getName(), slot, offers[slot].getItemId());
        return line(formatAge(nowMs - listedMs, listedMs), expectedSeconds(slot, offers[slot].getItemId()));
    }

    private long expectedSeconds(int slot, int itemId) {
        try {
            SavedOffer saved = offerManager.loadOffer(client.getAccountHash(), slot);
            return saved != null && saved.getItemId() == itemId ? saved.getExpectedSeconds() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** "Listed 12m ago, expected 50m"; "Listed 12m ago" without an estimate; null when the age is unknown. */
    static String line(String age, long expectedSeconds) {
        if (age == null) {
            return null;
        }
        String expected = formatExpected(expectedSeconds);
        return "Listed " + age + " ago" + (expected == null ? "" : ", expected " + expected);
    }

    /** "50m" when the card gave an estimate, null otherwise. */
    static String formatExpected(long expectedSeconds) {
        if (expectedSeconds <= 0) {
            return null;
        }
        return formatAge(Math.max(60_000L, expectedSeconds * 1000L), 1L);
    }

    /** "under 1m", "12m", "1h05m", "2d3h"; null when the listing time is unknown. */
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
            return String.format("%dh%02dm", hours, minutes % 60);
        }
        return String.format("%dd%dh", hours / 24, hours % 24);
    }
}
