package com.runeassist.flip.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

    /** Body for Ares {@code POST /v1/suggestion}: live GE / held / preference snapshot so the */
@Getter
@Setter
public class ComposeSuggestionRequest
{
    /** Available coins (same meaning as {@code /v1/flips} {@code capital}). */
    private long capital;
    private int timeframeMinutes = 5;
    /** {@code low}/{@code medium}/{@code high} — see {@link RiskLevel#toApiValue()}. */
    private String risk = "medium";
    private boolean membersItemsAllowed = true;
    private boolean f2pOnly = false;
    /** Max GE slots the plugin may use (after reserved slots). */
    private int maxSlots = 8;
    /** Free slots after counting live offers against {@link #maxSlots}. */
    private int remainingSlots = 8;
    private long minPredictedProfit;

    /** itemId string -> remaining 4h buy-limit. Missing = unknown. */
    private Map<String, Integer> remainingBuyLimit = new LinkedHashMap<>();
    /** itemId string -> units already counting against the 4h limit. */
    private Map<String, Integer> usedBuyLimit = new LinkedHashMap<>();

    private List<Integer> blockedIds = new ArrayList<>();
    private List<Integer> skippedIds = new ArrayList<>();
    /** User-skipped live offers: do not ABORT/MODIFY these. */
    private List<Integer> skipOfferItemIds = new ArrayList<>();
    /** Recently suggested / listed items: protect from ABORT for ~10 min. */
    private List<Integer> protectAbortItemIds = new ArrayList<>();

    /**
     * Live GE board. Empty slot = omit, or include with {@code filling=false} and
     * {@code itemId=0}. Slot index is 0-based (RuneLite offer slot).
     */
    private List<OfferSnapshot> offers = new ArrayList<>();

    /** Held stock with FIFO avg buy (gp). */
    private List<HeldSnapshot> held = new ArrayList<>();

    /** In-progress GE modify (cancel-then-relist); null if none. */
    private OwnedModifySnapshot ownedModify;

    /**
     * When true (default), Ares may bundle a {@code /v1/graph}-shaped {@code graph}
     * object on the compose response for the picked item (FC suggestion+graph parity).
     * Set false for low-data mode.
     */
    private boolean includeGraph = true;

    /**
     * Client clock ms. Optional — server normally uses its own clock; tests may pin this.
     */
    private long nowMs;

    @Getter
    @Setter
    public static class OfferSnapshot
    {
        private int slot;
        private int itemId;
        /** True = buy offer. */
        private boolean buy;
        private long price;
        private int sold;
        private int total;
        /** True when the offer is actively filling (not empty / cancelled). */
        private boolean filling;
        /** Epoch ms of last fill progress; 0 if unknown. */
        private long lastProgressMs;
        /** Epoch ms when this listing was placed; 0 if unknown. */
        private long listedMs;
    }

    @Getter
    @Setter
    public static class HeldSnapshot
    {
        private int itemId;
        private long qty;
        private long avgBuy;
    }

    @Getter
    @Setter
    public static class OwnedModifySnapshot
    {
        private int slot = -1;
        private int itemId;
        private boolean buy;
        private long targetPrice;
        private int quantity;
        private String name = "";
        private long offerPrice;
    }
}
