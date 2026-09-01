package com.osrsmcp;

import javax.inject.Singleton;

/**
 * The current suggested flip, shared from the Flips panel (EDT) to the on-GE overlay
 * (client render thread). Plain volatile fields — a single most-recent snapshot, no history.
 * Display-only: it only carries what to show; nothing here touches the game.
 */
@Singleton
public class SharedFlipState
{
    public volatile boolean valid = false;
    public volatile boolean sell = false; // true = suggestion is to SELL a held item
    public volatile int    itemId;
    public volatile String name = "";
    public volatile long   buyAt;
    public volatile long   sellAt;
    public volatile long   qty;
    public volatile long   profit;
    public volatile double marginPct;
    public volatile int    geLimit;
    public volatile int    limitLeft;

    public void set(int itemId, String name, long buyAt, long sellAt, long qty,
                    long profit, double marginPct, int geLimit, int limitLeft)
    {
        this.itemId = itemId;
        this.name = name;
        this.buyAt = buyAt;
        this.sellAt = sellAt;
        this.qty = qty;
        this.profit = profit;
        this.marginPct = marginPct;
        this.geLimit = geLimit;
        this.limitLeft = limitLeft;
        this.valid = true;
    }

    public void clear() { valid = false; }
}
