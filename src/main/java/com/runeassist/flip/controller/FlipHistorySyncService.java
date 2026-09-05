package com.runeassist.flip.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.runeassist.flip.HeldCostTracker;
import com.runeassist.flip.model.FlipManager;
import com.runeassist.flip.model.FlipStatus;
import com.runeassist.flip.model.FlipV2;
import com.runeassist.flip.model.OfferStatus;
import com.runeassist.flip.model.OsrsLoginManager;
import com.runeassist.flip.model.PortfolioId;
import com.runeassist.flip.model.SuggestionManager;
import com.runeassist.flip.model.Transaction;
import com.runeassist.flip.model.VisualizeFlipResponse;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

    /** Server-owned flip history. GE fills persist to an unacked JSONL queue until */
@Slf4j
@Singleton
public class FlipHistorySyncService {

    public static final String CONFIG_GROUP = BugReportClient.CONFIG_GROUP;
    public static final String KEY_DEVICE_TOKEN = BugReportClient.KEY_DEVICE_TOKEN;
    public static final String KEY_USER_ID = BugReportClient.KEY_USER_ID;
    public static final String DEFAULT_ORIGIN = BugReportClient.DEFAULT_ORIGIN;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int FLUSH_SEC = 45;
    private static final int BATCH = 200;

    /** Matches FlipManager default pluginUserId. */
    public static final int PLUGIN_USER_ID = 0;

    private final OkHttpClient http;
    private final Gson gson;
    private final ConfigManager configManager;
    private final FlipManager flipManager;
    private final OsrsLoginManager osrsLoginManager;
    private final HeldCostTracker heldCostTracker;
    private final SuggestionManager suggestionManager;
    private final ScheduledExecutorService executor;

    /** Display-name → unacked GE fills (backed by {@link Persistance} JSONL). */
    private final ConcurrentMap<String, List<Transaction>> unackedByDisplay = new ConcurrentHashMap<>();
    private final List<Runnable> statusListeners = new CopyOnWriteArrayList<>();
    private volatile boolean started;
    private volatile boolean registering;
    private volatile String lastError;

    @Inject
    public FlipHistorySyncService(
            OkHttpClient http,
            Gson gson,
            ConfigManager configManager,
            FlipManager flipManager,
            OsrsLoginManager osrsLoginManager,
            HeldCostTracker heldCostTracker,
            SuggestionManager suggestionManager,
            @Named("runeAssistExecutor") ScheduledExecutorService executor) {
        this.http = http;
        this.gson = gson;
        this.configManager = configManager;
        this.flipManager = flipManager;
        this.osrsLoginManager = osrsLoginManager;
        this.heldCostTracker = heldCostTracker;
        this.suggestionManager = suggestionManager;
        this.executor = executor;
    }

    public void start() {
        if (started) {
            return;
        }
        started = true;
        executor.scheduleAtFixedRate(() -> {
            try {
                flush();
            } catch (Exception e) {
                log.debug("flip history sync flush: {}", e.getMessage());
            }
        }, FLUSH_SEC, FLUSH_SEC, TimeUnit.SECONDS);
        if (!isLinked()) {
            executor.execute(() -> {
                try {
                    ensureRegistered();
                } catch (Exception e) {
                    log.warn("flip history sync register: {}", e.getMessage());
                }
            });
        } else {
            fireStatus();
        }
    }

    public boolean isLinked() {
        return deviceToken() != null && userId() != null;
    }

    /** True when this install has cached an OSRS account id for the given display name. */
    public boolean isOsrsLinked(String displayName) {
        if (displayName == null || displayName.isEmpty() || !isLinked()) {
            return false;
        }
        String cached = configManager.getConfiguration(CONFIG_GROUP, osrsKey(displayName));
        return cached != null && !cached.isEmpty();
    }

    /** Short line for Preferences → Flip history. */
    public String statusMessage() {
        if (isLinked()) {
            return "Linked — Recent Flips enabled.";
        }
        if (registering) {
            return "Registering this client…";
        }
        if (lastError != null) {
            return "Not linked (" + lastError + ").";
        }
        return "Not linked. Pair below to enable Recent Flips.";
    }

    public void addStatusListener(Runnable listener) {
        if (listener != null) {
            statusListeners.add(listener);
        }
    }

    public String websiteUrl() {
        return "https://runeassist.com/app/";
    }

    public void onLogin(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            try {
                syncOnLogin(displayName);
            } catch (Exception e) {
                log.warn("flip history sync on login failed: {}", e.getMessage());
            }
        });
    }

    /**
     * Persist a GE fill to the unacked JSONL queue (crash-safe), then schedule upload.
     * Safe to call before the device is linked — rows stay on disk until {@code ackedIds}.
     */
    public void enqueue(Transaction transaction, String displayName) {
        if (transaction == null || transaction.getId() == null || displayName == null || displayName.isEmpty()) {
            return;
        }
        synchronized (this) {
            List<Transaction> unacked = unackedList(displayName);
            UUID id = transaction.getId();
            boolean exists = false;
            for (Transaction existing : unacked) {
                if (id.equals(existing.getId())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                unacked.add(copyTransaction(transaction));
                Persistance.storeUnackedTransactions(unacked, displayName);
            }
        }
        executor.execute(() -> {
            try {
                flushDisplay(displayName);
            } catch (Exception e) {
                log.debug("flip history enqueue flush: {}", e.getMessage());
            }
        });
    }

    /** Snapshot of unacked GE fills for session ledger replay / account status. */
    public List<Transaction> listUnacked(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return new ArrayList<>();
        }
        synchronized (this) {
            List<Transaction> copy = new ArrayList<>();
            for (Transaction t : unackedList(displayName)) {
                copy.add(copyTransaction(t));
            }
            return copy;
        }
    }

    public void flushNow() {
        executor.execute(() -> {
            try {
                flush();
            } catch (Exception e) {
                log.debug("flip history flushNow: {}", e.getMessage());
            }
        });
    }

    /** Blocking. Returns pairing code for another device or website email link. */
    public String startPairing() throws Exception {
        ensureRegistered();
        JsonObject body = post("/v1/account/pair/start", new JsonObject(), true);
        if (body == null || !body.has("code")) {
            throw new IllegalStateException("no pairing code from server");
        }
        return body.get("code").getAsString();
    }

    /** Blocking. Redeem a website-issued (or other device) pairing code onto this install. */
    public void redeemPairing(String code) throws Exception {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("empty code");
        }
        JsonObject req = new JsonObject();
        req.addProperty("code", code.trim().toUpperCase());
        JsonObject body = post("/v1/account/pair/redeem", req, false);
        if (body == null || !body.has("deviceToken")) {
            throw new IllegalStateException("pairing redeem failed");
        }
        storeToken(body.get("userId").getAsString(), body.get("deviceToken").getAsString());
        lastError = null;
        fireStatus();
        String name = osrsLoginManager.getPlayerDisplayName();
        if (name != null) {
            configManager.unsetConfiguration(CONFIG_GROUP, osrsKey(name));
            ensureOsrsAccount(name);
            flushDisplay(name);
        }
    }

    private void syncOnLogin(String displayName) throws Exception {
        ensureRegistered();
        String osrsAccountId = ensureOsrsAccount(displayName);
        if (osrsAccountId == null) {
            return;
        }
        flushDisplay(displayName);
        pullFlipsDelta(displayName, osrsAccountId);
    }

    private void pullFlipsDelta(String displayName, String osrsAccountId) {
        String cursor = configManager.getConfiguration(CONFIG_GROUP, flipsCursorKey(displayName));
        String path = "/v1/account/client-flips-delta?osrsAccountId=" + urlEnc(osrsAccountId);
        if (cursor != null && !cursor.isEmpty() && !"0".equals(cursor)) {
            path += "&sinceUpdatedTime=" + urlEnc(cursor);
        }
        JsonObject body = get(path, true);
        if (body == null) {
            return;
        }
        if (body.has("flips")) {
            JsonArray arr = body.getAsJsonArray("flips");
            List<FlipV2> flips = new ArrayList<>();
            int accountId = accountIdFor(displayName);
            for (JsonElement el : arr) {
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                FlipV2 flip = flipFromJson(el.getAsJsonObject(), accountId);
                if (flip != null) {
                    flips.add(flip);
                }
            }
            if (!flips.isEmpty()) {
                flipManager.setPluginUserId(PLUGIN_USER_ID);
                flipManager.mergeFlips(flips, PLUGIN_USER_ID);
                log.info("flip history pulled {} flips for {}", flips.size(), displayName);
            }
        }
        applyHeldFromBody(displayName, body);
        if (body.has("time") && !body.get("time").isJsonNull()) {
            configManager.setConfiguration(CONFIG_GROUP, flipsCursorKey(displayName), body.get("time").getAsString());
        }
    }

    private void applyHeldFromBody(String displayName, JsonObject body) {
        if (body == null || displayName == null || !body.has("held") || body.get("held").isJsonNull()) {
            return;
        }
        try {
            JsonObject heldObj = body.getAsJsonObject("held");
            Map<Integer, long[]> held = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : heldObj.entrySet()) {
                int itemId;
                try {
                    itemId = Integer.parseInt(e.getKey());
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (!e.getValue().isJsonArray()) {
                    continue;
                }
                JsonArray pair = e.getValue().getAsJsonArray();
                if (pair.size() < 2) {
                    continue;
                }
                long qty = pair.get(0).getAsLong();
                long avg = pair.get(1).getAsLong();
                if (qty > 0) {
                    held.put(itemId, new long[]{qty, avg});
                }
            }
            heldCostTracker.replaceServerHeld(displayName, held);
            suggestionManager.setSuggestionNeeded(true);
            log.info("server held applied for {} ({} items)", displayName, held.size());
        } catch (Exception e) {
            log.debug("apply held failed: {}", e.getMessage());
        }
    }

    /** Force a full flips+held refresh for the logged-in account (after mutations). */
    public void refreshAccount(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            try {
                String osrsAccountId = ensureOsrsAccount(displayName);
                if (osrsAccountId == null) {
                    return;
                }
                // Drop cursor so delete/ghost mutations are not filtered out.
                configManager.unsetConfiguration(CONFIG_GROUP, flipsCursorKey(displayName));
                pullFlipsDelta(displayName, osrsAccountId);
            } catch (Exception e) {
                log.warn("flip history refresh failed: {}", e.getMessage());
            }
        });
    }

    /**
     * Server clear-portfolio (FC clear-account-portfolio). Optimistic local clear, then sync.
     * @return true if the request was accepted / queued
     */
    public boolean clearPortfolio(String displayName) {
        if (displayName == null || !isLinked()) {
            return false;
        }
        heldCostTracker.clearLots(displayName);
        suggestionManager.setSuggestionNeeded(true);
        executor.execute(() -> {
            try {
                String osrsAccountId = ensureOsrsAccount(displayName);
                if (osrsAccountId == null) {
                    return;
                }
                JsonObject req = new JsonObject();
                req.addProperty("osrsAccountId", osrsAccountId);
                JsonObject body = post("/v1/account/clear-portfolio", req, true);
                if (body != null) {
                    applyHeldFromBody(displayName, body);
                }
                configManager.unsetConfiguration(CONFIG_GROUP, flipsCursorKey(displayName));
                pullFlipsDelta(displayName, osrsAccountId);
            } catch (Exception e) {
                log.warn("clear-portfolio failed: {}", e.getMessage());
            }
        });
        return true;
    }

    /**
     * Server toggle-item-portfolio. {@code remove=true} forgets cost basis; otherwise adds a manual lot.
     */
    public boolean toggleItemPortfolio(String displayName, int itemId, int quantity, long unitCost, boolean remove) {
        if (displayName == null || itemId <= 0 || !isLinked()) {
            return false;
        }
        if (remove) {
            heldCostTracker.removeLots(displayName, itemId, quantity);
        } else if (quantity > 0) {
            heldCostTracker.addManualLot(displayName, itemId, quantity, unitCost);
        }
        suggestionManager.setSuggestionNeeded(true);
        executor.execute(() -> {
            try {
                String osrsAccountId = ensureOsrsAccount(displayName);
                if (osrsAccountId == null) {
                    return;
                }
                JsonObject req = new JsonObject();
                req.addProperty("osrsAccountId", osrsAccountId);
                req.addProperty("itemId", itemId);
                req.addProperty("quantity", quantity);
                req.addProperty("unitCost", unitCost);
                req.addProperty("remove", remove);
                // FC shape: add → COFLIP (0); remove → -1
                req.addProperty("portfolioId", remove ? -1 : PortfolioId.COFLIP_PORTFOLIO);
                JsonObject body = post("/v1/account/toggle-item-portfolio", req, true);
                if (body != null) {
                    applyHeldFromBody(displayName, body);
                }
                configManager.unsetConfiguration(CONFIG_GROUP, flipsCursorKey(displayName));
                pullFlipsDelta(displayName, osrsAccountId);
            } catch (Exception e) {
                log.warn("toggle-item-portfolio failed: {}", e.getMessage());
            }
        });
        return true;
    }

    /** Soft-delete a flip on the server (FC delete-flip), then refresh FlipManager. */
    public boolean deleteFlip(String displayName, UUID flipId) {
        if (displayName == null || flipId == null || !isLinked()) {
            return false;
        }
        executor.execute(() -> {
            try {
                mutateFlip(displayName, "/v1/account/delete-flip", flipId, null);
            } catch (Exception e) {
                log.warn("delete-flip failed: {}", e.getMessage());
            }
        });
        return true;
    }

    /** Move a flip into the disappeared/ghost portfolio bucket (FC orphan). */
    public boolean orphanFlip(String displayName, UUID flipId) {
        if (displayName == null || flipId == null || !isLinked()) {
            return false;
        }
        executor.execute(() -> {
            try {
                mutateFlip(displayName, "/v1/account/orphan-flip", flipId, null);
            } catch (Exception e) {
                log.warn("orphan-flip failed: {}", e.getMessage());
            }
        });
        return true;
    }

    /** Revive a ghost/disappeared flip into the personal portfolio. */
    public boolean reviveFlip(String displayName, UUID flipId) {
        if (displayName == null || flipId == null || !isLinked()) {
            return false;
        }
        executor.execute(() -> {
            try {
                mutateFlip(displayName, "/v1/account/revive-ghost-flip", flipId, null);
            } catch (Exception e) {
                log.warn("revive-ghost-flip failed: {}", e.getMessage());
            }
        });
        return true;
    }

    /** Record a missed sale (synthetic sell) against an open flip. */
    public boolean addMissedSale(String displayName, UUID flipId, int quantity, long price) {
        if (displayName == null || flipId == null || quantity <= 0 || price < 0 || !isLinked()) {
            return false;
        }
        executor.execute(() -> {
            try {
                JsonObject extra = new JsonObject();
                extra.addProperty("quantity", quantity);
                extra.addProperty("price", price);
                mutateFlip(displayName, "/v1/account/add-missed-sale", flipId, extra);
            } catch (Exception e) {
                log.warn("add-missed-sale failed: {}", e.getMessage());
            }
        });
        return true;
    }

    /** Orphan a GE transaction so it is excluded from ledger replay. */
    public boolean orphanTransaction(String displayName, UUID transactionId) {
        return mutateTransaction(displayName, transactionId, "/v1/account/orphan-transaction", "orphan-transaction");
    }

    /** Soft-delete a GE transaction (FC delete-transaction). */
    public boolean deleteTransaction(String displayName, UUID transactionId) {
        return mutateTransaction(displayName, transactionId, "/v1/account/delete-transaction", "delete-transaction");
    }

    /** List GE transactions for the linked OSRS account (server chronological order). */
    public List<Transaction> listTransactions(String displayName) {
        if (displayName == null || !isLinked()) {
            return java.util.Collections.emptyList();
        }
        try {
            String osrsAccountId = ensureOsrsAccount(displayName);
            if (osrsAccountId == null) {
                return java.util.Collections.emptyList();
            }
            JsonObject body = get("/v1/account/transactions?osrsAccountId=" + urlEnc(osrsAccountId), true);
            if (body == null || !body.has("transactions") || !body.get("transactions").isJsonArray()) {
                return java.util.Collections.emptyList();
            }
            List<Transaction> out = new ArrayList<>();
            for (JsonElement el : body.getAsJsonArray("transactions")) {
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                Transaction t = txFromJson(el.getAsJsonObject());
                if (t != null) {
                    out.add(t);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("list transactions failed: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    private boolean mutateTransaction(String displayName, UUID transactionId, String path, String logLabel) {
        if (displayName == null || transactionId == null || !isLinked()) {
            return false;
        }
        executor.execute(() -> {
            try {
                String osrsAccountId = ensureOsrsAccount(displayName);
                if (osrsAccountId == null) {
                    return;
                }
                JsonObject req = new JsonObject();
                req.addProperty("osrsAccountId", osrsAccountId);
                req.addProperty("transactionId", transactionId.toString());
                JsonObject body = post(path, req, true);
                mergeFlipsFromMutation(displayName, body);
                configManager.unsetConfiguration(CONFIG_GROUP, flipsCursorKey(displayName));
                pullFlipsDelta(displayName, osrsAccountId);
            } catch (Exception e) {
                log.warn("{} failed: {}", logLabel, e.getMessage());
            }
        });
        return true;
    }

    /**
     * Server lots + graph for one flip ({@code GET /v1/account/visualize-flip}).
     * Invokes {@code onSuccess} on the worker thread (caller should hop to EDT for UI).
     */
    public void asyncVisualizeFlip(
            String displayName,
            UUID flipId,
            Consumer<VisualizeFlipResponse> onSuccess,
            Consumer<String> onFailure) {
        if (onSuccess == null) {
            return;
        }
        if (displayName == null || displayName.isEmpty() || flipId == null || !isLinked()) {
            if (onFailure != null) {
                onFailure.accept("not linked");
            }
            return;
        }
        executor.execute(() -> {
            try {
                String osrsAccountId = ensureOsrsAccount(displayName);
                if (osrsAccountId == null) {
                    if (onFailure != null) {
                        onFailure.accept("OSRS account not linked");
                    }
                    return;
                }
                String path = "/v1/account/visualize-flip?osrsAccountId=" + urlEnc(osrsAccountId)
                        + "&flipId=" + urlEnc(flipId.toString());
                JsonObject body = get(path, true);
                if (body == null) {
                    if (onFailure != null) {
                        onFailure.accept("visualize-flip request failed");
                    }
                    return;
                }
                if (body.has("error") && !body.get("error").isJsonNull()) {
                    if (onFailure != null) {
                        onFailure.accept(body.get("error").getAsString());
                    }
                    return;
                }
                VisualizeFlipResponse rsp = VisualizeFlipResponse.fromJson(body, gson);
                onSuccess.accept(rsp);
            } catch (Exception e) {
                log.warn("visualize-flip failed: {}", e.getMessage());
                if (onFailure != null) {
                    onFailure.accept(e.getMessage() != null ? e.getMessage() : "visualize-flip failed");
                }
            }
        });
    }

    private void mutateFlip(String displayName, String path, UUID flipId, JsonObject extra) throws Exception {
        String osrsAccountId = ensureOsrsAccount(displayName);
        if (osrsAccountId == null) {
            return;
        }
        JsonObject req = new JsonObject();
        req.addProperty("osrsAccountId", osrsAccountId);
        req.addProperty("flipId", flipId.toString());
        if (extra != null) {
            for (java.util.Map.Entry<String, JsonElement> e : extra.entrySet()) {
                req.add(e.getKey(), e.getValue());
            }
        }
        JsonObject body = post(path, req, true);
        mergeFlipsFromMutation(displayName, body);
        configManager.unsetConfiguration(CONFIG_GROUP, flipsCursorKey(displayName));
        pullFlipsDelta(displayName, osrsAccountId);
    }

    private void mergeFlipsFromMutation(String displayName, JsonObject body) {
        if (body == null || !body.has("flips") || !body.get("flips").isJsonArray()) {
            return;
        }
        int accountId = accountIdFor(displayName);
        List<FlipV2> flips = new ArrayList<>();
        for (JsonElement el : body.getAsJsonArray("flips")) {
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            FlipV2 flip = flipFromJson(el.getAsJsonObject(), accountId);
            if (flip != null) {
                flips.add(flip);
            }
        }
        if (!flips.isEmpty()) {
            flipManager.setPluginUserId(PLUGIN_USER_ID);
            flipManager.mergeFlips(flips, PLUGIN_USER_ID);
        }
        applyHeldFromBody(displayName, body);
    }

    private void flush() {
        if (deviceToken() == null) {
            return;
        }
        Set<String> names = new HashSet<>(unackedByDisplay.keySet());
        String current = osrsLoginManager.getPlayerDisplayName();
        if (current != null && !current.isEmpty()) {
            names.add(current);
        }
        for (String displayName : names) {
            try {
                flushDisplay(displayName);
            } catch (Exception e) {
                log.debug("flip history flush {}: {}", displayName, e.getMessage());
            }
        }
    }

    private void flushDisplay(String displayName) throws Exception {
        if (displayName == null || displayName.isEmpty() || deviceToken() == null) {
            return;
        }
        String osrsAccountId = ensureOsrsAccount(displayName);
        if (osrsAccountId == null) {
            return;
        }
        while (true) {
            List<Transaction> batch;
            synchronized (this) {
                List<Transaction> unacked = unackedList(displayName);
                if (unacked.isEmpty()) {
                    return;
                }
                int end = Math.min(BATCH, unacked.size());
                batch = new ArrayList<>(end);
                for (int i = 0; i < end; i++) {
                    batch.add(copyTransaction(unacked.get(i)));
                }
            }
            List<UUID> acked = postTransactions(osrsAccountId, batch);
            if (acked == null) {
                return;
            }
            if (acked.isEmpty()) {
                // Server accepted nothing usable — avoid tight retry loop on bad rows.
                return;
            }
            removeAcked(displayName, acked);
        }
    }

    /**
     * Upload a batch. Returns acked transaction ids on success, or {@code null} on transport/HTTP failure.
     */
    private List<UUID> postTransactions(String osrsAccountId, List<Transaction> txs) {
        if (txs == null || txs.isEmpty()) {
            return new ArrayList<>();
        }
        JsonObject req = new JsonObject();
        req.addProperty("osrsAccountId", osrsAccountId);
        JsonArray arr = new JsonArray();
        for (Transaction t : txs) {
            JsonObject o = txToJson(t);
            if (o != null) {
                arr.add(o);
            }
        }
        req.add("transactions", arr);
        JsonObject body = post("/v1/account/transactions", req, true);
        if (body == null) {
            return null;
        }
        List<UUID> acked = new ArrayList<>();
        if (body.has("ackedIds") && body.get("ackedIds").isJsonArray()) {
            for (JsonElement el : body.getAsJsonArray("ackedIds")) {
                if (el == null || el.isJsonNull()) {
                    continue;
                }
                try {
                    acked.add(UUID.fromString(el.getAsString()));
                } catch (Exception ignored) {
                    // skip malformed ids
                }
            }
        } else {
            // Older servers without ackedIds: treat uploaded ids as acked on 200.
            for (Transaction t : txs) {
                if (t.getId() != null) {
                    acked.add(t.getId());
                }
            }
        }
        return acked;
    }

    private synchronized void removeAcked(String displayName, List<UUID> ackedIds) {
        if (ackedIds == null || ackedIds.isEmpty()) {
            return;
        }
        Set<UUID> acked = new HashSet<>(ackedIds);
        List<Transaction> unacked = unackedList(displayName);
        unacked.removeIf(t -> t.getId() != null && acked.contains(t.getId()));
        Persistance.storeUnackedTransactions(unacked, displayName);
    }

    private List<Transaction> unackedList(String displayName) {
        return unackedByDisplay.computeIfAbsent(displayName, Persistance::loadUnackedTransactions);
    }

    private synchronized void ensureRegistered() throws Exception {
        if (deviceToken() != null && userId() != null) {
            registering = false;
            return;
        }
        registering = true;
        fireStatus();
        try {
            JsonObject body = post("/v1/account/register", new JsonObject(), false);
            if (body == null || !body.has("deviceToken") || !body.has("userId")) {
                throw new IllegalStateException("register failed");
            }
            storeToken(body.get("userId").getAsString(), body.get("deviceToken").getAsString());
            lastError = null;
            log.info("flip history sync registered user {}", body.get("userId").getAsString());
        } catch (Exception e) {
            lastError = e.getMessage() == null ? "register failed" : e.getMessage();
            throw e;
        } finally {
            registering = false;
            fireStatus();
        }
    }

    private void fireStatus() {
        for (Runnable listener : statusListeners) {
            try {
                SwingUtilities.invokeLater(listener);
            } catch (Exception ignored) {
                // UI gone
            }
        }
    }

    private String ensureOsrsAccount(String displayName) throws Exception {
        ensureRegistered();
        String cached = configManager.getConfiguration(CONFIG_GROUP, osrsKey(displayName));
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        JsonObject req = new JsonObject();
        req.addProperty("displayName", displayName);
        JsonObject body = post("/v1/account/link-osrs", req, true);
        if (body == null || !body.has("osrsAccountId")) {
            return null;
        }
        String id = body.get("osrsAccountId").getAsString();
        configManager.setConfiguration(CONFIG_GROUP, osrsKey(displayName), id);
        return id;
    }

    private void storeToken(String userId, String token) {
        configManager.setConfiguration(CONFIG_GROUP, KEY_USER_ID, userId);
        configManager.setConfiguration(CONFIG_GROUP, KEY_DEVICE_TOKEN, token);
    }

    private String deviceToken() {
        String t = configManager.getConfiguration(CONFIG_GROUP, KEY_DEVICE_TOKEN);
        return t == null || t.isEmpty() ? null : t;
    }

    private String userId() {
        return configManager.getConfiguration(CONFIG_GROUP, KEY_USER_ID);
    }

    private String osrsKey(String displayName) {
        // Config key prefix kept for install continuity (not a product "cloud sync" toggle).
        return "cloudOsrs." + Persistance.hashDisplayName(displayName);
    }

    private String flipsCursorKey(String displayName) {
        return "cloudFlipsCursor." + Persistance.hashDisplayName(displayName);
    }

    private String origin() {
        return DEFAULT_ORIGIN;
    }

    public static int accountIdFor(String displayName) {
        int h = Persistance.hashDisplayName(displayName == null ? "" : displayName).hashCode();
        if (h == Integer.MIN_VALUE || h == 0) {
            return 1;
        }
        return Math.abs(h);
    }

    public static Transaction copyTransaction(Transaction src) {
        if (src == null) {
            return null;
        }
        Transaction t = new Transaction();
        t.setId(src.getId());
        t.setType(src.getType());
        t.setItemId(src.getItemId());
        t.setPrice(src.getPrice());
        t.setQuantity(src.getQuantity());
        t.setBoxId(src.getBoxId());
        t.setAmountSpent(src.getAmountSpent());
        t.setTimestamp(src.getTimestamp());
        t.setLogin(src.isLogin());
        t.setConsistent(src.isConsistent());
        return t;
    }

    private JsonObject get(String path, boolean authed) {
        Request.Builder b = new Request.Builder()
                .url(origin() + path)
                .header("User-Agent", "RuneAssist-flip/1.0")
                .get();
        if (authed) {
            String token = deviceToken();
            if (token == null) {
                return null;
            }
            b.header("Authorization", "Bearer " + token);
        }
        return execute(b.build());
    }

    private JsonObject post(String path, JsonObject json, boolean authed) {
        Request.Builder b = new Request.Builder()
                .url(origin() + path)
                .header("User-Agent", "RuneAssist-flip/1.0")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(JSON, gson.toJson(json)));
        if (authed) {
            String token = deviceToken();
            if (token == null) {
                return null;
            }
            b.header("Authorization", "Bearer " + token);
        }
        return execute(b.build());
    }

    private JsonObject execute(Request request) {
        try (Response response = http.newCall(request).execute()) {
            String raw = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.warn("flip history sync {} {} → {}", request.method(), request.url().encodedPath(), response.code());
                return null;
            }
            if (raw.isEmpty()) {
                return new JsonObject();
            }
            return gson.fromJson(raw, JsonObject.class);
        } catch (Exception e) {
            log.debug("flip history sync {} failed: {}", request.url().encodedPath(), e.getMessage());
            return null;
        }
    }

    private static String urlEnc(String s) {
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    static JsonObject txToJson(Transaction t) {
        if (t == null || t.getId() == null || t.getType() == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", t.getId().toString());
        o.addProperty("type", OfferStatus.BUY.equals(t.getType()) ? "BUY" : "SELL");
        o.addProperty("itemId", t.getItemId());
        o.addProperty("price", t.getPrice());
        o.addProperty("quantity", t.getQuantity());
        o.addProperty("boxId", t.getBoxId());
        o.addProperty("amountSpent", t.getAmountSpent());
        Instant ts = t.getTimestamp() != null ? t.getTimestamp() : Instant.now();
        o.addProperty("timestamp", ts.toString());
        return o;
    }

    static Transaction txFromJson(JsonObject o) {
        if (o == null || !o.has("id")) {
            return null;
        }
        try {
            Transaction t = new Transaction();
            t.setId(UUID.fromString(o.get("id").getAsString()));
            String type = o.has("type") ? o.get("type").getAsString() : "BUY";
            t.setType("SELL".equalsIgnoreCase(type) ? OfferStatus.SELL : OfferStatus.BUY);
            t.setItemId(o.has("itemId") ? o.get("itemId").getAsInt() : 0);
            t.setPrice(o.has("price") ? o.get("price").getAsLong() : 0L);
            t.setQuantity(o.has("quantity") ? o.get("quantity").getAsInt() : 0);
            t.setBoxId(o.has("boxId") ? o.get("boxId").getAsInt() : 0);
            t.setAmountSpent(o.has("amountSpent") ? o.get("amountSpent").getAsLong() : 0L);
            if (o.has("timestamp") && !o.get("timestamp").isJsonNull()) {
                t.setTimestamp(Instant.parse(o.get("timestamp").getAsString()));
            }
            t.setConsistent(true);
            return t;
        } catch (Exception e) {
            return null;
        }
    }

        static FlipV2 flipFromJson(JsonObject o, int accountId) {
        if (o == null || !o.has("id")) {
            return null;
        }
        try {
            FlipV2 f = new FlipV2();
            f.setId(UUID.fromString(o.get("id").getAsString()));
            f.setAccountId(accountId);
            f.setItemId(o.has("itemId") ? o.get("itemId").getAsInt() : 0);
            f.setOpenedTime(o.has("openedTime") ? o.get("openedTime").getAsInt() : 0);
            f.setOpenedQuantity(o.has("openedQuantity") ? o.get("openedQuantity").getAsInt() : 0);
            f.setSpent(o.has("spent") ? o.get("spent").getAsLong() : 0L);
            f.setClosedTime(o.has("closedTime") ? o.get("closedTime").getAsInt() : 0);
            f.setClosedQuantity(o.has("closedQuantity") ? o.get("closedQuantity").getAsInt() : 0);
            f.setReceivedPostTax(o.has("receivedPostTax") ? o.get("receivedPostTax").getAsLong() : 0L);
            f.setProfit(o.has("profit") ? o.get("profit").getAsLong() : 0L);
            f.setTaxPaid(o.has("taxPaid") ? o.get("taxPaid").getAsLong() : 0L);
            String status = o.has("status") ? o.get("status").getAsString() : "FINISHED";
            f.setStatus(parseStatus(status));
            f.setUpdatedTime(o.has("updatedTime") ? o.get("updatedTime").getAsInt() : 0);
            f.setDeleted(o.has("deleted") && o.get("deleted").getAsBoolean());
            f.setPortfolioId(o.has("portfolioId") ? o.get("portfolioId").getAsInt() : 1);
            f.setSeqNo(o.has("seqNo") ? o.get("seqNo").getAsLong() : 1L);
            f.setUserId(PLUGIN_USER_ID);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private static FlipStatus parseStatus(String status) {
        if (status == null) {
            return FlipStatus.FINISHED;
        }
        switch (status.toUpperCase()) {
            case "BUYING":
                return FlipStatus.BUYING;
            case "SELLING":
                return FlipStatus.SELLING;
            default:
                return FlipStatus.FINISHED;
        }
    }
}
