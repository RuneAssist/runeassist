package com.runeassist.flip;

import com.runeassist.flip.controller.ItemController;
import com.runeassist.flip.model.GeHistoryState;
import com.runeassist.flip.model.OsrsLoginManager;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

/**
 * When the in-game GE history panel is open, backfill {@link HeldCostTracker} lots from
 * completed buys that live offer tracking never saw. Never places or cancels offers.
 */
@Slf4j
@Singleton
public class GeHistoryHeldBackfill {

    private final HeldCostTracker heldCostTracker;
    private final ItemController itemController;
    private final OsrsLoginManager osrsLoginManager;
    private final Client client;

    @Inject
    public GeHistoryHeldBackfill(HeldCostTracker heldCostTracker,
                                 ItemController itemController,
                                 OsrsLoginManager osrsLoginManager,
                                 Client client) {
        this.heldCostTracker = heldCostTracker;
        this.itemController = itemController;
        this.osrsLoginManager = osrsLoginManager;
        this.client = client;
    }

    public void maybeApply(GeHistoryState state) {
        if (state == null || !state.isLoaded()) {
            return;
        }
        if (state.getRows() == null || state.getRows().isEmpty()) {
            return;
        }
        String displayName = osrsLoginManager.getPlayerDisplayName();
        if (displayName == null || displayName.isEmpty()) {
            return;
        }
        Map<Integer, Long> filling = liveFillingBuyQty();
        int added = heldCostTracker.applyHistoryBackfill(
                displayName, state.getRows(), filling, itemController::toUnnotedItemId);
        if (added > 0) {
            log.info("backfilled {} held lot(s) from GE history for {}", added, displayName);
        }
    }

    private Map<Integer, Long> liveFillingBuyQty() {
        Map<Integer, Long> filling = new HashMap<>();
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null) {
            return filling;
        }
        for (GrandExchangeOffer offer : offers) {
            if (offer == null || offer.getState() != GrandExchangeOfferState.BUYING) {
                continue;
            }
            if (offer.getItemId() <= 0 || offer.getQuantitySold() <= 0) {
                continue;
            }
            int itemId = itemController.toUnnotedItemId(offer.getItemId());
            filling.merge(itemId, (long) offer.getQuantitySold(), Long::sum);
        }
        return filling;
    }
}
