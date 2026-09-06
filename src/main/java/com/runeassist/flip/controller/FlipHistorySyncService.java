package com.runeassist.flip.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.runeassist.flip.HeldCostTracker;
import com.runeassist.flip.controller.history.AccountHttp;
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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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

/** Server-owned flip history: unacked GE outbox, pairing, delta, portfolio/flip mutations. */
@Slf4j
@Singleton
public class FlipHistorySyncService {

    public static final String CONFIG_GROUP = BugReportClient.CONFIG_GROUP;
    public static final String KEY_DEVICE_TOKEN = BugReportClient.KEY_DEVICE_TOKEN;
    public static final String KEY_USER_ID = BugReportClient.KEY_USER_ID;
    public static final String DEFAULT_ORIGIN = BugReportClient.DEFAULT_ORIGIN;
    public static final int PLUGIN_USER_ID = 0;

    private static final int FLUSH_SEC = 45;
    private static final int BATCH = 200;

    private final AccountHttp api;
    private final ConfigManager configManager;
    private final FlipManager flipManager;
    private final OsrsLoginManager osrsLoginManager;
    private final HeldCostTracker heldCostTracker;
    private final SuggestionManager suggestionManager;
    private final ScheduledExecutorService executor;

    private final ConcurrentMap<String, List<Transaction>> unackedByDisplay = new ConcurrentHashMap<>();
    private final List<Runnable> statusListeners = new CopyOnWriteArrayList<>();
    private volatile boolean started;
    private volatile boolean registering;
    private volatile String lastError;

    @Inject
    public FlipHistorySyncService(
            AccountHttp api,
            ConfigManager configManager,
            FlipManager flipManager,
            OsrsLoginManager osrsLoginManager,
            HeldCostTracker heldCostTracker,
            SuggestionManager suggestionManager,
            @Named("runeAssistExecutor") ScheduledExecutorService executor) {
        this.api = api;
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
            async("register", this::ensureRegistered);
        } else {
            fireStatus();
        }
    }

    public boolean isLinked() {
        return api.isLinked();
    }

    public boolean isOsrsLinked(String displayName) {
        if (displayName == null || displayName.isEmpty() || !isLinked()) {
            return false;
        }
        String cached = configManager.getConfiguration(CONFIG_GROUP, osrsKey(displayName));
        return cached != null && !cached.isEmpty();
    }

    public String shortUserId() {
        String id = api.userId();
        if (id == null || id.isEmpty()) {
            return null;
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    public String identityLabel() {
        if (isLinked()) {
            String rsn = osrsLoginManager.getPlayerDisplayName();
            if (rsn != null && isOsrsLinked(rsn)) {
                return rsn;
            }
            String shortId = shortUserId();
            return shortId != null ? "Linked · " + shortId : "Linked";
        }
        return registering ? "Linking…" : "Not linked";
    }

    public String identityDetail() {
        if (isLinked()) {
            String rsn = osrsLoginManager.getPlayerDisplayName();
            if (rsn != null && isOsrsLinked(rsn)) {
                String shortId = shortUserId();
                return "Flip history on for " + rsn
                        + (shortId != null ? " · account " + shortId : "")
                        + ". Click for account settings.";
            }
            if (rsn != null) {
                return "Device linked — finishing attach for " + rsn + ". Click for account settings.";
            }
            return "Device linked. Log into OSRS to attach this character. Click for account settings.";
        }
        if (registering) {
            return "Registering this client so Recent Flips can sync…";
        }
        if (lastError != null) {
            return "Not linked (" + lastError + "). Open settings to retry or enter a pairing code.";
        }
        return "Link this client to enable Recent Flips history. Click for account settings.";
    }

    public String statusMessage() {
        if (isLinked()) {
            String rsn = osrsLoginManager.getPlayerDisplayName();
            String shortId = shortUserId();
            String idBit = shortId != null ? " · account " + shortId : "";
            if (rsn != null && isOsrsLinked(rsn)) {
                return "Signed in for flip history as " + rsn + idBit
                        + ". Pair another PC or attach a website login below.";
            }
            if (rsn != null) {
                return "Device linked" + idBit + ". Attaching " + rsn
                        + "… Recent Flips enable once the character link finishes.";
            }
            return "Device linked" + idBit
                    + ". Log into OSRS to attach this character and enable Recent Flips.";
        }
        if (registering) {
            return "Registering this client… Linking enables Recent Flips history (same role as signing in).";
        }
        if (lastError != null) {
            return "Not linked yet (" + lastError + "). Use a pairing code below, or wait for auto-register.";
        }
        return "Not linked yet. This client will auto-register, or enter a pairing code from another device / the website.";
    }

    public void addStatusListener(Runnable listener) {
        if (listener != null) {
            statusListeners.add(listener);
        }
    }

    public String websiteUrl() {
        return "https://runeassist.com/app/";
    }

    public String websiteAccountsUrl() {
        return websiteUrl() + "#/accounts";
    }

    public String websitePairUrl() {
        return websiteUrl() + "#/pair";
    }

    public String websiteLoginWithCodeUrl(String code) {
        if (code == null || code.isEmpty()) {
            return websiteUrl() + "#/login";
        }
        return websiteUrl() + "#/login?code=" + URLEncoder.encode(code, StandardCharsets.UTF_8);
    }

    public void onLogin(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return;
        }
        async("on login", () -> syncOnLogin(displayName));
    }

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
        async("enqueue flush", () -> flushDisplay(displayName));
    }

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
        async("flushNow", this::flush);
    }

    public String startPairing() throws Exception {
        ensureRegistered();
        JsonObject body = api.post("/v1/account/pair/start", new JsonObject(), true);
        if (body == null || !body.has("code")) {
            throw new IllegalStateException("no pairing code from server");
        }
        return body.get("code").getAsString();
    }

    public void redeemPairing(String code) throws Exception {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("empty code");
        }
        JsonObject req = new JsonObject();
        req.addProperty("code", code.trim().toUpperCase());
        JsonObject body = api.post("/v1/account/pair/redeem", req, false);
        if (body == null || !body.has("deviceToken")) {
            throw new IllegalStateException("pairing redeem failed");
        }
        api.storeToken(body.get("userId").getAsString(), body.get("deviceToken").getAsString());
        lastError = null;
        fireStatus();
        String name = osrsLoginManager.getPlayerDisplayName();
        if (name != null) {
            configManager.unsetConfiguration(CONFIG_GROUP, osrsKey(name));
            ensureOsrsAccount(name);
            flushDisplay(name);
        }
    }

    public void refreshAccount(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return;
        }
        async("refresh", () -> {
            String osrsAccountId = ensureOsrsAccount(displayName);
            if (osrsAccountId == null) {
                return;
            }
            refreshDelta(displayName, osrsAccountId);
        });
    }

    public boolean clearPortfolio(String displayName) {
        if (displayName == null || !isLinked()) {
            return false;
        }
        heldCostTracker.clearLots(displayName);
        suggestionManager.setSuggestionNeeded(true);
        async("clear-portfolio", () -> {
            String osrsAccountId = ensureOsrsAccount(displayName);
            if (osrsAccountId == null) {
                return;
            }
            JsonObject req = new JsonObject();
            req.addProperty("osrsAccountId", osrsAccountId);
            applyHeldFromBody(displayName, api.post("/v1/account/clear-portfolio", req, true));
            refreshDelta(displayName, osrsAccountId);
        });
        return true;
    }

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
        async("toggle-item-portfolio", () -> {
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
            req.addProperty("portfolioId", remove ? -1 : PortfolioId.COFLIP_PORTFOLIO);
            applyHeldFromBody(displayName, api.post("/v1/account/toggle-item-portfolio", req, true));
            refreshDelta(displayName, osrsAccountId);
        });
        return true;
    }

    public boolean deleteFlip(String displayName, UUID flipId) {
        return enqueueFlipMut(displayName, flipId, "/v1/account/delete-flip", null, "delete-flip");
    }

    public boolean orphanFlip(String displayName, UUID flipId) {
        return enqueueFlipMut(displayName, flipId, "/v1/account/orphan-flip", null, "orphan-flip");
    }

    public boolean reviveFlip(String displayName, UUID flipId) {
        return enqueueFlipMut(displayName, flipId, "/v1/account/revive-ghost-flip", null, "revive-ghost-flip");
    }

    public boolean addMissedSale(String displayName, UUID flipId, int quantity, long price) {
        if (displayName == null || flipId == null || quantity <= 0 || price < 0 || !isLinked()) {
            return false;
        }
        JsonObject extra = new JsonObject();
        extra.addProperty("quantity", quantity);
        extra.addProperty("price", price);
        return enqueueFlipMut(displayName, flipId, "/v1/account/add-missed-sale", extra, "add-missed-sale");
    }

    public boolean orphanTransaction(String displayName, UUID transactionId) {
        return mutateTransaction(displayName, transactionId, "/v1/account/orphan-transaction", "orphan-transaction");
    }

    public boolean deleteTransaction(String displayName, UUID transactionId) {
        return mutateTransaction(displayName, transactionId, "/v1/account/delete-transaction", "delete-transaction");
    }

    public List<Transaction> listTransactions(String displayName) {
        if (displayName == null || !isLinked()) {
            return Collections.emptyList();
        }
        try {
            String osrsAccountId = ensureOsrsAccount(displayName);
            if (osrsAccountId == null) {
                return Collections.emptyList();
            }
            JsonObject body = api.get("/v1/account/transactions?osrsAccountId=" + AccountHttp.urlEnc(osrsAccountId), true);
            if (body == null || !body.has("transactions") || !body.get("transactions").isJsonArray()) {
                return Collections.emptyList();
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
            return Collections.emptyList();
        }
    }

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
                String path = "/v1/account/visualize-flip?osrsAccountId=" + AccountHttp.urlEnc(osrsAccountId)
                        + "&flipId=" + AccountHttp.urlEnc(flipId.toString());
                JsonObject body = api.get(path, true);
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
                onSuccess.accept(VisualizeFlipResponse.fromJson(body, api.gson()));
            } catch (Exception e) {
                log.warn("visualize-flip failed: {}", e.getMessage());
                if (onFailure != null) {
                    onFailure.accept(e.getMessage() != null ? e.getMessage() : "visualize-flip failed");
                }
            }
        });
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
            f.setStatus(parseStatus(o.has("status") ? o.get("status").getAsString() : "FINISHED"));
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
        String path = "/v1/account/client-flips-delta?osrsAccountId=" + AccountHttp.urlEnc(osrsAccountId);
        if (cursor != null && !cursor.isEmpty() && !"0".equals(cursor)) {
            path += "&sinceUpdatedTime=" + AccountHttp.urlEnc(cursor);
        }
        JsonObject body = api.get(path, true);
        if (body == null) {
            return;
        }
        if (body.has("flips")) {
            List<FlipV2> flips = parseFlips(body.getAsJsonArray("flips"), accountIdFor(displayName));
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

    private boolean mutateTransaction(String displayName, UUID transactionId, String path, String logLabel) {
        if (displayName == null || transactionId == null || !isLinked()) {
            return false;
        }
        async(logLabel, () -> {
            String osrsAccountId = ensureOsrsAccount(displayName);
            if (osrsAccountId == null) {
                return;
            }
            JsonObject req = new JsonObject();
            req.addProperty("osrsAccountId", osrsAccountId);
            req.addProperty("transactionId", transactionId.toString());
            mergeFlipsFromMutation(displayName, api.post(path, req, true));
            refreshDelta(displayName, osrsAccountId);
        });
        return true;
    }

    private boolean enqueueFlipMut(String displayName, UUID flipId, String path, JsonObject extra, String logLabel) {
        if (displayName == null || flipId == null || !isLinked()) {
            return false;
        }
        async(logLabel, () -> mutateFlip(displayName, path, flipId, extra));
        return true;
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
            for (Map.Entry<String, JsonElement> e : extra.entrySet()) {
                req.add(e.getKey(), e.getValue());
            }
        }
        mergeFlipsFromMutation(displayName, api.post(path, req, true));
        refreshDelta(displayName, osrsAccountId);
    }

    private void mergeFlipsFromMutation(String displayName, JsonObject body) {
        if (body == null || !body.has("flips") || !body.get("flips").isJsonArray()) {
            return;
        }
        List<FlipV2> flips = parseFlips(body.getAsJsonArray("flips"), accountIdFor(displayName));
        if (!flips.isEmpty()) {
            flipManager.setPluginUserId(PLUGIN_USER_ID);
            flipManager.mergeFlips(flips, PLUGIN_USER_ID);
        }
        applyHeldFromBody(displayName, body);
    }

    private List<FlipV2> parseFlips(JsonArray arr, int accountId) {
        List<FlipV2> flips = new ArrayList<>();
        if (arr == null) {
            return flips;
        }
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            FlipV2 flip = flipFromJson(el.getAsJsonObject(), accountId);
            if (flip != null) {
                flips.add(flip);
            }
        }
        return flips;
    }

    private void refreshDelta(String displayName, String osrsAccountId) {
        configManager.unsetConfiguration(CONFIG_GROUP, flipsCursorKey(displayName));
        pullFlipsDelta(displayName, osrsAccountId);
    }

    private void flush() throws Exception {
        if (api.deviceToken() == null) {
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
        if (displayName == null || displayName.isEmpty() || api.deviceToken() == null) {
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
            if (acked == null || acked.isEmpty()) {
                return;
            }
            removeAcked(displayName, acked);
        }
    }

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
        JsonObject body = api.post("/v1/account/transactions", req, true);
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
                    // skip
                }
            }
        } else {
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
        if (api.deviceToken() != null && api.userId() != null) {
            registering = false;
            return;
        }
        registering = true;
        fireStatus();
        try {
            JsonObject body = api.post("/v1/account/register", new JsonObject(), false);
            if (body == null || !body.has("deviceToken") || !body.has("userId")) {
                throw new IllegalStateException("register failed");
            }
            api.storeToken(body.get("userId").getAsString(), body.get("deviceToken").getAsString());
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
        JsonObject body = api.post("/v1/account/link-osrs", req, true);
        if (body == null || !body.has("osrsAccountId")) {
            return null;
        }
        String id = body.get("osrsAccountId").getAsString();
        configManager.setConfiguration(CONFIG_GROUP, osrsKey(displayName), id);
        return id;
    }

    private String osrsKey(String displayName) {
        return "cloudOsrs." + Persistance.hashDisplayName(displayName);
    }

    private String flipsCursorKey(String displayName) {
        return "cloudFlipsCursor." + Persistance.hashDisplayName(displayName);
    }

    private void async(String label, Work work) {
        executor.execute(() -> {
            try {
                work.run();
            } catch (Exception e) {
                log.warn("{} failed: {}", label, e.getMessage());
            }
        });
    }

    @FunctionalInterface
    private interface Work {
        void run() throws Exception;
    }
}
