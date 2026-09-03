package com.runeassist.flip;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v2 of the shop-flip feature: live-verifies {@link ShopFlipService} candidates against
 * whichever shop the player currently has open, instead of trusting the wiki's documented
 * base stock blindly.
 *
 * <p>Deliberately does NOT try to compute a shop's live per-unit price from its current
 * stock count -- the price-scaling formula and its per-item multipliers weren't pulled in
 * v1, and getting that formula subtly wrong would silently show a wrong price with nothing
 * to catch it. Instead this only confirms the open shop's <em>live</em> stock is at or near
 * the wiki's documented base stock -- the stock level {@link ShopFlipService}'s margin
 * calculation assumed -- for whichever candidate items happen to be in front of the player
 * right now.
 *
 * <p>Not a {@code @Subscribe}r itself, matching this package's convention (see
 * {@code GrandExchangeOfferEventHandler}, {@code GameUiChangesHandler}): {@link
 * #onItemContainerChanged} is called manually from {@code FlippingCopilotPlugin}'s own
 * {@code ItemContainerChanged} handler.
 *
 * <p><b>Not live-verified.</b> {@code InterfaceID.Shopmain.UNIVERSE} is read from the
 * RuneLite API sources (mirrors the existing {@code InterfaceID.Bankmain.UNIVERSE} bank-open
 * check in {@code FlippingCopilotPlugin}), not confirmed against a running shop screen --
 * check a shop actually flips {@link #isShopOpen()} true before trusting the rest.
 */
@Slf4j
@Singleton
public class ShopLiveTracker
{
    private static final double NEAR_FULL_STOCK_FRACTION = 0.9;

    private final Client client;
    private final ShopFlipService shopFlipService;

    private volatile Map<Integer, Integer> lastShopStock = Collections.emptyMap();

    @Inject
    public ShopLiveTracker(Client client, ShopFlipService shopFlipService)
    {
        this.client = client;
        this.shopFlipService = shopFlipService;
    }

    /** Client-thread only (widget read). Call from the plugin's own ItemContainerChanged handler. */
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (!isShopOpen())
        {
            if (!lastShopStock.isEmpty())
            {
                lastShopStock = Collections.emptyMap();
            }
            return;
        }
        if (event.getContainerId() == InventoryID.INV)
        {
            return; // the player's own inventory changing, not the shop's stock
        }
        ItemContainer shop = event.getItemContainer();
        if (shop == null)
        {
            return;
        }
        Map<Integer, Integer> stock = new HashMap<>();
        for (Item item : shop.getItems())
        {
            if (item == null || item.getId() <= 0)
            {
                continue;
            }
            stock.merge(item.getId(), item.getQuantity(), Integer::sum);
        }
        lastShopStock = stock;
    }

    /** Client-thread only (widget read). */
    public boolean isShopOpen()
    {
        Widget shopMain = client.getWidget(InterfaceID.Shopmain.UNIVERSE);
        return shopMain != null && !shopMain.isHidden();
    }

    /**
     * Client-thread only (calls {@link #isShopOpen()}). {@link ShopFlipService} candidates
     * whose item is in the currently open shop at or near its documented base stock -- i.e.
     * worth buying from this specific shop right now, not just "worth checking sometime".
     */
    public List<Map<String, Object>> liveConfirmedCandidates(int maxResults)
    {
        if (!isShopOpen())
        {
            return List.of();
        }
        Map<Integer, Integer> live = lastShopStock;
        if (live.isEmpty())
        {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> candidate : shopFlipService.topShopFlips(500))
        {
            if (!"Buy from shop, sell on GE".equals(candidate.get("direction")))
            {
                continue; // only the "shop must currently be well-stocked" direction applies
            }
            Object stockObj = candidate.get("stock");
            long baseStock;
            try
            {
                baseStock = Long.parseLong(String.valueOf(stockObj));
            }
            catch (NumberFormatException e)
            {
                continue; // "unknown" / "unlimited" -- nothing concrete to confirm against
            }
            int itemId = (int) candidate.get("itemId");
            Integer liveQty = live.get(itemId);
            if (liveQty == null || liveQty < baseStock * NEAR_FULL_STOCK_FRACTION)
            {
                continue;
            }
            Map<String, Object> confirmed = new LinkedHashMap<>(candidate);
            confirmed.put("liveQty", liveQty);
            out.add(confirmed);
            if (out.size() >= maxResults)
            {
                break;
            }
        }
        return out;
    }
}
