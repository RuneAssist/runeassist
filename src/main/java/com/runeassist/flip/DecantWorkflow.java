package com.runeassist.flip;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.runeassist.flip.controller.FlipHistorySyncService;
import com.runeassist.flip.controller.history.AccountHttp;
import com.runeassist.flip.model.DecantPlan;
import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionManager;
import net.runelite.api.*;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.config.ConfigManager;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;

/** Per-account durable plan and conversion outbox. No clicks or game actions are automated. */
@Singleton
public class DecantWorkflow {
    private static final String GROUP = "runeassistDecants";
    @Inject private Client client;
    @Inject private Gson gson;
    @Inject private ConfigManager config;
    @Inject private AccountHttp http;
    @Inject private SuggestionManager suggestions;
    private String account;
    private State state;
    private Map<String, Integer> before;
    private long armedUntil;
    private long nextRetry;

    public static class State {
        public DecantPlan plan;
        public JsonObject pending;
        public boolean started, converted, outputListed;
        public String conversionId;
    }

    /** Client-thread only: switch without ever carrying observation across accounts/logins. */
    public synchronized void bind(String linkedAccount) {
        if (Objects.equals(account, linkedAccount)) return;
        account = linkedAccount; state = null; disarm();
        if (account == null) return;
        String raw = config.getConfiguration(GROUP, "account_" + account);
        try { if (raw != null) state = gson.fromJson(raw, State.class); }
        catch (RuntimeException ignored) { state = null; }
        if (state != null && (state.plan == null || !state.plan.isValid())) state = null;
    }

    private void save() {
        if (account != null) config.setConfiguration(GROUP, "account_" + account, gson.toJson(state));
    }

    public synchronized DecantPlan plan() { return state == null ? null : state.plan; }
    public synchronized boolean pending() { return state != null && state.pending != null; }
    public synchronized String conversionId() { return state == null ? null : state.conversionId; }

    private void bindCurrentPlayer() {
        Player player = client.getLocalPlayer();
        String linked = player == null || player.getName() == null ? null : config.getConfiguration(
            FlipHistorySyncService.CONFIG_GROUP, FlipHistorySyncService.osrsConfigKey(player.getName()));
        bind(linked);
    }

    public synchronized void reconcile(Map<Integer, long[]> held) {
        // Converted plans are closed only by the authoritative server replay.
        if (state == null || state.converted || !state.outputListed || state.pending != null) return;
        int id = state.converted ? state.plan.getSellItemId() : state.plan.getBuyItemId();
        if (held.containsKey(id) && held.get(id)[0] > 0) return;
        if (inventory().values().stream().anyMatch(n -> n > 0)) return;
        for (GrandExchangeOffer o : client.getGrandExchangeOffers()) if (o != null
            && o.getState() != GrandExchangeOfferState.EMPTY && o.getItemId() == id) return;
        state = null; save(); disarm();
    }

    public synchronized void accept(String expectedAccount, Suggestion s) {
        if (!Objects.equals(account, expectedAccount) || account == null || s == null) return;
        if (s.isDecantComplete() && state != null && state.pending == null && state.converted) {
            state = null; disarm(); save();
        }
        DecantPlan p = s.getDecantPlan();
        if (state == null && p != null && p.isValid() && s.isBuySuggestion()) {
            state = new State(); state.plan = p; save();
        }
    }

    /** Send only counts for this plan's potion family, never general inventory/bank contents. */
    public synchronized Map<String, Integer> inventory() {
        Map<String, Integer> counts = new HashMap<>();
        if (state == null) return counts;
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv == null) return counts;
        for (Item item : inv.getItems()) {
            if (item == null || item.getId() <= 0) continue;
            ItemComposition def = client.getItemDefinition(item.getId());
            int id = def.getNote() == -1 ? item.getId() : def.getLinkedNoteId();
            if (state.plan.getFamilyIds().contains(id)) counts.merge(String.valueOf(id), item.getQuantity(), Integer::sum);
        }
        return counts;
    }

    public synchronized boolean isolatedInventory() {
        if (state == null) return true;
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv == null) return false;
        for (Item item : inv.getItems()) {
            if (item == null || item.getId() <= 0) continue;
            ItemComposition def = client.getItemDefinition(item.getId());
            int id = def.getNote() == -1 ? item.getId() : def.getLinkedNoteId();
            if (state.plan.getFamilyIds().contains(id)) continue;
            ItemComposition base = client.getItemDefinition(id);
            if (base.getName().matches(".*\\([1-4]\\)$") && base.getInventoryActions() != null
                && Arrays.stream(base.getInventoryActions()).anyMatch(a -> "Drink".equalsIgnoreCase(a))) return false;
        }
        return true;
    }

    public synchronized void disarm() { before = null; armedUntil = 0; }

    public synchronized void onMenu(MenuOptionClicked event) {
        bindCurrentPlayer();
        if (state == null || state.pending != null || state.converted) return;
        if (!isolatedInventory()) return;
        String target = event.getMenuTarget() == null ? "" : event.getMenuTarget().replaceAll("<[^>]*>", "");
        if (!"Decant".equalsIgnoreCase(event.getMenuOption())
            || !("Bob Barter".equalsIgnoreCase(target) || "Bob Barter (herbs)".equalsIgnoreCase(target))) return;
        Map<String, Integer> inv = inventory();
        DecantPlan p = state.plan;
        int input = inv.getOrDefault(String.valueOf(p.getBuyItemId()), 0);
        boolean otherDoses = inv.entrySet().stream().anyMatch(e -> !e.getKey().equals(String.valueOf(p.getBuyItemId())) && e.getValue() > 0);
        if (input <= 0 || input > p.getBuyQty() || (long) input * p.getBuyDose() % 4 != 0 || otherDoses) return;
        before = inv; armedUntil = System.currentTimeMillis() + 120000;
    }

    public static boolean matches(DecantPlan p, Map<String, Integer> before, Map<String, Integer> after) {
        int input = before.getOrDefault(String.valueOf(p.getBuyItemId()), 0);
        int oldOutput = before.getOrDefault(String.valueOf(p.getSellItemId()), 0);
        int output = after.getOrDefault(String.valueOf(p.getSellItemId()), 0) - oldOutput;
        if (input <= 0 || input > p.getBuyQty() || after.getOrDefault(String.valueOf(p.getBuyItemId()), 0) != 0
            || output <= 0 || (long) input * p.getBuyDose() != (long) output * 4) return false;
        for (int id : p.getFamilyIds()) if (id != p.getBuyItemId() && id != p.getSellItemId()
            && !Objects.equals(before.getOrDefault(String.valueOf(id), 0), after.getOrDefault(String.valueOf(id), 0))) return false;
        return true;
    }

    public synchronized void onTick(boolean bankOpen) {
        if (client.getGameState() != GameState.LOGGED_IN || bankOpen) { disarm(); return; }
        bindCurrentPlayer();
        if (state == null) return;
        Map<String, Integer> inv = inventory();
        DecantPlan p = state.plan;
        boolean offer = false;
        for (GrandExchangeOffer o : client.getGrandExchangeOffers()) if (o != null && o.getState() != GrandExchangeOfferState.EMPTY) {
            if (o.getItemId() == p.getBuyItemId() || o.getItemId() == p.getSellItemId()) offer = true;
            if (!state.outputListed && o.getItemId() == (state.converted ? p.getSellItemId() : p.getBuyItemId())
                && (o.getState() == GrandExchangeOfferState.SELLING || o.getState() == GrandExchangeOfferState.SOLD)) {
                state.outputListed = true; save();
            }
        }
        if (!state.started && (offer || inv.values().stream().anyMatch(n -> n > 0))) { state.started = true; save(); }
        if (!state.started && System.currentTimeMillis() - p.getCreatedAt() > 600000) { state = null; save(); disarm(); return; }
        if (before == null) return;
        if (System.currentTimeMillis() > armedUntil) { disarm(); return; }
        if (matches(p, before, inv)) {
            int input = before.getOrDefault(String.valueOf(p.getBuyItemId()), 0);
            JsonObject e = new JsonObject();
            e.addProperty("id", UUID.randomUUID().toString());
            e.addProperty("timestamp", System.currentTimeMillis());
            e.addProperty("buyItemId", p.getBuyItemId()); e.addProperty("sellItemId", p.getSellItemId());
            e.addProperty("buyDose", p.getBuyDose()); e.addProperty("sellDose", 4);
            e.addProperty("inputQuantity", input); e.addProperty("outputQuantity", input * p.getBuyDose() / 4);
            e.addProperty("beforeInput", input); e.addProperty("afterInput", 0);
            e.addProperty("beforeOutput", before.getOrDefault(String.valueOf(p.getSellItemId()), 0));
            e.addProperty("afterOutput", inv.getOrDefault(String.valueOf(p.getSellItemId()), 0));
            e.addProperty("evidence", "bob-barter-inventory-delta-v1");
            state.pending = e; save(); disarm(); suggestions.setSuggestionNeeded(true);
        } else if (!before.equals(inv)) { disarm(); }
    }

    /** Background thread only. Acknowledgement is idempotent; retries survive restart. */
    public void flush(String expectedAccount) {
        JsonObject event;
        synchronized (this) {
            if (!Objects.equals(account, expectedAccount) || state == null || state.pending == null
                || System.currentTimeMillis() < nextRetry) return;
            event = state.pending.deepCopy(); nextRetry = System.currentTimeMillis() + 10000;
        }
        JsonObject body = new JsonObject(); body.addProperty("osrsAccountId", expectedAccount); body.add("event", event);
        JsonObject reply = http.post("/v1/account/decant-conversion", body, true);
        synchronized (this) {
            if (Objects.equals(account, expectedAccount) && state != null && state.pending != null && reply != null
                && reply.has("ackedId") && reply.get("ackedId").getAsString().equals(state.pending.get("id").getAsString())) {
                state.conversionId = reply.get("ackedId").getAsString();
                state.pending = null; state.converted = true; save(); suggestions.setSuggestionNeeded(true);
            }
        }
    }
}
