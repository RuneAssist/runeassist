package com.runeassist.flip.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.model.FlipManager;
import com.runeassist.flip.model.FlipStatus;
import com.runeassist.flip.model.FlipV2;
import com.runeassist.flip.model.LocalFlipLedger;
import com.runeassist.flip.model.OfferStatus;
import com.runeassist.flip.model.OsrsLoginManager;
import com.runeassist.flip.model.Transaction;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Server-owned flip history (FC-shaped). GE fills are persisted to an unacked
 * JSONL queue until {@code POST /v1/account/transactions} returns {@code ackedIds};
 * after device register / OSRS account link, uploads run and
 * {@code /v1/account/client-flips-delta} fills {@link FlipManager}. There is no
 * supported local-only or session-only history mode — Recent Flips come from
 * the server once this client is linked. {@link LocalFlipLedger} still matches
 * the live session for instant UI; stable flip ids keep both sides mergeable.
 */
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

    private final OkHttpClient http;
    private final Gson gson;
    private final ConfigManager configManager;
    private final RuneAssistConfig config;
    private final LocalFlipLedger localFlipLedger;
    private final FlipManager flipManager;
    private final OsrsLoginManager osrsLoginManager;
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
            RuneAssistConfig config,
            LocalFlipLedger localFlipLedger,
            FlipManager flipManager,
            OsrsLoginManager osrsLoginManager,
            @Named("runeAssistExecutor") ScheduledExecutorService executor) {
        this.http = http;
        this.gson = gson;
        this.configManager = configManager;
        this.config = config;
        this.localFlipLedger = localFlipLedger;
        this.flipManager = flipManager;
        this.osrsLoginManager = osrsLoginManager;
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

    /** Short line for Preferences → Flip history. */
    public String statusMessage() {
        if (isLinked()) {
            return "This client is linked. Recent Flips history is enabled for your RuneAssist account — pair another device or the website below.";
        }
        if (registering) {
            return "Registering this client… Linking (like signing in) enables Recent Flips history. Buttons below still work.";
        }
        if (lastError != null) {
            return "Not linked yet (" + lastError + "). Link this client below to enable Recent Flips history.";
        }
        return "Not linked yet. Link this client below to enable Recent Flips history across sessions.";
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
                unacked.add(LocalFlipLedger.copyTransaction(transaction));
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
                copy.add(LocalFlipLedger.copyTransaction(t));
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
        backfill(displayName, osrsAccountId);
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
        if (body == null || !body.has("flips")) {
            return;
        }
        JsonArray arr = body.getAsJsonArray("flips");
        List<FlipV2> flips = new ArrayList<>();
        int accountId = LocalFlipLedger.accountIdFor(displayName);
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
            flipManager.setPluginUserId(LocalFlipLedger.LOCAL_USER_ID);
            flipManager.mergeFlips(flips, LocalFlipLedger.LOCAL_USER_ID);
            log.info("flip history pulled {} flips for {}", flips.size(), displayName);
        }
        if (body.has("time") && !body.get("time").isJsonNull()) {
            configManager.setConfiguration(CONFIG_GROUP, flipsCursorKey(displayName), body.get("time").getAsString());
        }
    }

    private void backfill(String displayName, String osrsAccountId) {
        String doneKey = backfillKey(displayName);
        if ("1".equals(configManager.getConfiguration(CONFIG_GROUP, doneKey))) {
            return;
        }
        List<Transaction> all = localFlipLedger.listSourceTransactions(displayName);
        for (int i = 0; i < all.size(); i += BATCH) {
            int end = Math.min(all.size(), i + BATCH);
            List<UUID> acked = postTransactions(osrsAccountId, all.subList(i, end));
            if (acked == null) {
                return;
            }
            // Session source txs may also sit in the unacked JSONL — clear overlaps.
            removeAcked(displayName, acked);
        }
        configManager.setConfiguration(CONFIG_GROUP, doneKey, "1");
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
                    batch.add(LocalFlipLedger.copyTransaction(unacked.get(i)));
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

    private String backfillKey(String displayName) {
        return "cloudBackfill." + Persistance.hashDisplayName(displayName);
    }

    private String origin() {
        String endpoint = config.telemetryEndpoint();
        if (endpoint == null || endpoint.trim().isEmpty()) {
            return DEFAULT_ORIGIN;
        }
        try {
            java.net.URL u = new java.net.URL(endpoint.trim());
            String port = u.getPort() > 0 ? (":" + u.getPort()) : "";
            return u.getProtocol() + "://" + u.getHost() + port;
        } catch (Exception e) {
            return DEFAULT_ORIGIN;
        }
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
            f.setUserId(LocalFlipLedger.LOCAL_USER_ID);
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
