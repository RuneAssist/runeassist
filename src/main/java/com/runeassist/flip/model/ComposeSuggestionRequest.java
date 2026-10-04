package com.runeassist.flip.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class ComposeSuggestionRequest
{
    private long capital;
    private int timeframeMinutes = 5;
    private String risk = "medium";
    private boolean membersItemsAllowed = true;
    private boolean f2pOnly = false;
    private boolean sellOnlyMode;
    private int maxSlots = 8;
    private int remainingSlots = 8;
    private long minPredictedProfit;

    private Map<String, Integer> remainingBuyLimit = new LinkedHashMap<>();
    private Map<String, Integer> usedBuyLimit = new LinkedHashMap<>();

    private List<Integer> blockedIds = new ArrayList<>();
    private List<Integer> skippedIds = new ArrayList<>();
    private List<Integer> skipOfferItemIds = new ArrayList<>();
    private Map<String, Long> modifyDismissedMs = new LinkedHashMap<>();
    private List<Integer> protectAbortItemIds = new ArrayList<>();

    private List<OfferSnapshot> offers = new ArrayList<>();

    private List<HeldSnapshot> held = new ArrayList<>();

    private boolean inventorySnapshotKnown;
    private List<InventoryAvailabilitySnapshot.ItemQuantity> availableInventory = new ArrayList<>();

    private boolean bankSnapshotKnown;
    private List<InventoryAvailabilitySnapshot.ItemQuantity> availableBank = new ArrayList<>();

    private OwnedModifySnapshot ownedModify;

    private boolean includeGraph = true;

    private String clientDeviceId = "";

    private long nowMs;

    private boolean contributeTrainingData;

    private String osrsAccountId = "";

    @Getter
    @Setter
    public static class OfferSnapshot
    {
        private int slot;
        private int itemId;
        private boolean buy;
        private long price;
        private int sold;
        private int total;
        private boolean filling;
        private long lastProgressMs;
        private long listedMs;
        private long lastPriceChangeMs;
        private String suggestionId = "";
        private String origin = "external";
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
