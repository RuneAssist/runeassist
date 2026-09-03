package com.runeassist.flip;

import com.osrsmcp.WikiBucketService;
import com.osrsmcp.WikiPriceService;
import com.runeassist.flip.util.ProfitCalculator;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * NPC-shop-flip candidates, v1: a static reference list computed from the wiki's {@code
 * storeline} bucket (base-stock shop prices) cross-referenced against live GE prices. This
 * is deliberately NOT a live per-tick suggestion the way GE flips or decants are -- a shop's
 * real current price depends on its live stock, which nothing but physically opening that
 * shop's interface can tell us (see {@link ShopLiveTracker} for that, v2). Candidates here
 * rank what's worth checking, not what's currently true.
 *
 * <p>Blocking (paginates a wiki HTTP API + a GE-price load); callers must invoke {@link
 * #topShopFlips(int)} off the RuneLite client thread, same rule as {@link WikiBucketService}
 * and {@link FlipScorer#quote}.</p>
 */
@Slf4j
@Singleton
public class ShopFlipService
{
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L; // recompute candidate list every 30 min
    private static final int PAGE_SIZE = 500;
    private static final int MAX_PAGES = 20; // safety cap: 10,000 storeline rows
    private static final long MIN_MARGIN_EACH = 20; // filter near-zero-margin noise, in coins
    private static final int UNLIMITED_STOCK_FALLBACK_QTY = 100; // when a shop has infinite stock, cap the
                                                                   // "gp/run" estimate by this if the item has
                                                                   // no known GE 4h buy limit either

    @Inject private WikiBucketService wikiBucketService;
    @Inject private WikiPriceService wikiPriceService;

    private volatile List<Map<String, Object>> cached = new ArrayList<>();
    private volatile long cachedAt = 0L;
    private volatile String lastError;

    /** Top candidates by estimated gp for a single run (margin-per-unit x realistic cap qty), best first. */
    public List<Map<String, Object>> topShopFlips(int maxResults)
    {
        refreshIfStale();
        List<Map<String, Object>> snapshot = cached;
        return snapshot.subList(0, Math.min(maxResults, snapshot.size()));
    }

    public long cacheAgeMs()
    {
        return cachedAt == 0L ? -1L : System.currentTimeMillis() - cachedAt;
    }

    public String lastError()
    {
        return lastError;
    }

    private synchronized void refreshIfStale()
    {
        if (!cached.isEmpty() && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS)
        {
            return;
        }
        try
        {
            List<Map<String, Object>> fresh = computeCandidates();
            cached = fresh;
            lastError = null;
            log.info("shop flip candidates refreshed: {} rows", fresh.size());
        }
        catch (Exception e)
        {
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("shop flip refresh failed", e);
        }
        cachedAt = System.currentTimeMillis();
    }

    private List<Map<String, Object>> computeCandidates()
    {
        Map<String, WikiPriceService.ItemMeta> byName = new HashMap<>();
        for (WikiPriceService.ItemMeta m : wikiPriceService.getAllMeta().values())
        {
            if (m.name != null)
            {
                byName.putIfAbsent(m.name.toLowerCase(Locale.ROOT), m);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++)
        {
            List<Map<String, Object>> rows = fetchStorelinePage(offset);
            if (rows.isEmpty())
            {
                break;
            }
            for (Map<String, Object> row : rows)
            {
                addCandidatesForRow(row, byName, out);
            }
            if (rows.size() < PAGE_SIZE)
            {
                break;
            }
            offset += PAGE_SIZE;
        }

        out.sort(Comparator.comparingLong((Map<String, Object> r) -> (Long) r.get("estProfit")).reversed());
        return out;
    }

    private void addCandidatesForRow(Map<String, Object> row, Map<String, WikiPriceService.ItemMeta> byName,
                                      List<Map<String, Object>> out)
    {
        String currency = str(row.get("store_currency"));
        if (currency == null || !currency.equalsIgnoreCase("Coins"))
        {
            return; // v1: coin-priced shops only
        }
        String itemName = str(row.get("sold_item"));
        String shopName = str(row.get("sold_by"));
        if (itemName == null || shopName == null)
        {
            return;
        }
        WikiPriceService.ItemMeta meta = byName.get(itemName.toLowerCase(Locale.ROOT));
        if (meta == null)
        {
            return; // not a GE-tradeable item (most storeline rows are quest/cosmetic junk) -- self-filters
        }
        WikiPriceService.PriceData price = wikiPriceService.getPrice(meta.id);
        if (price == null || price.high <= 0 || price.low <= 0)
        {
            return; // no live GE price data
        }

        Long stock = parseStock(row.get("store_stock"));

        // Direction 1: buy from the shop, sell on the GE.
        Long shopSellPrice = parseNumeric(row.get("store_sell_price")); // what the shop charges the player
        if (shopSellPrice != null && shopSellPrice > 0)
        {
            long geSellPostTax = ProfitCalculator.getPostTaxPrice(meta.id, price.low);
            long marginEach = geSellPostTax - shopSellPrice;
            if (marginEach >= MIN_MARGIN_EACH)
            {
                long capQty = runCapQty(stock, meta);
                out.add(candidate("Buy from shop, sell on GE", shopName, meta.id, itemName,
                        shopSellPrice, geSellPostTax, marginEach, capQty, stock));
            }
        }

        // Direction 2: buy on the GE, sell to the shop (rarer, but some specialty shops overpay).
        Long shopBuyPrice = parseNumeric(row.get("store_buy_price")); // what the shop pays the player
        if (shopBuyPrice != null && shopBuyPrice > 0)
        {
            long geBuy = price.high;
            long marginEach = shopBuyPrice - geBuy;
            if (marginEach >= MIN_MARGIN_EACH)
            {
                long capQty = runCapQty(stock, meta);
                out.add(candidate("Buy on GE, sell to shop", shopName, meta.id, itemName,
                        geBuy, shopBuyPrice, marginEach, capQty, stock));
            }
        }
    }

    static long runCapQty(Long stock, WikiPriceService.ItemMeta meta)
    {
        if (stock != null && stock > 0 && stock != Long.MAX_VALUE)
        {
            return stock;
        }
        return meta.limit > 0 ? meta.limit : UNLIMITED_STOCK_FALLBACK_QTY;
    }

    private static Map<String, Object> candidate(String direction, String shop, int itemId, String itemName,
                                                   long payPrice, long receivePrice, long marginEach, long capQty,
                                                   Long stock)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("direction", direction);
        m.put("shop", shop);
        m.put("itemId", itemId);
        m.put("itemName", itemName);
        m.put("payPrice", payPrice);
        m.put("receivePrice", receivePrice);
        m.put("marginEach", marginEach);
        m.put("capQty", capQty);
        m.put("estProfit", marginEach * capQty);
        m.put("stock", stock == null ? "unknown" : (stock == Long.MAX_VALUE ? "unlimited" : String.valueOf(stock)));
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchStorelinePage(int offset)
    {
        String lua = "bucket('storeline').select('sold_item','sold_by','store_buy_price','store_sell_price',"
                + "'store_currency','store_stock').limit(" + PAGE_SIZE + ").offset(" + offset + ").run()";
        Map<String, Object> resp = wikiBucketService.bucketQuery(null, null, null, null, null, lua);
        if (resp.containsKey("error"))
        {
            throw new RuntimeException("storeline query failed: " + resp.get("error"));
        }
        Object rowsObj = resp.get("rows");
        if (!(rowsObj instanceof List))
        {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object r : (List<Object>) rowsObj)
        {
            if (r instanceof Map)
            {
                rows.add((Map<String, Object>) r);
            }
        }
        return rows;
    }

    private static String str(Object o)
    {
        return o == null ? null : String.valueOf(o).trim();
    }

    static Long parseNumeric(Object raw)
    {
        String s = str(raw);
        if (s == null || s.isEmpty() || s.equalsIgnoreCase("N/A"))
        {
            return null;
        }
        s = s.replace(",", "");
        if (s.equals("∞"))
        {
            return Long.MAX_VALUE;
        }
        try
        {
            return (long) Double.parseDouble(s);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    static Long parseStock(Object raw)
    {
        return parseNumeric(raw);
    }
}
