package com.runeassist.flip.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.runeassist.flip.config.FlippingCopilotConfig;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cross-device transaction log sync. Additive to {@link LocalFlipLedger} — never blocks
 * suggestions. Auth is a device token on a <em>user</em>; each OSRS display name is a
 * separate {@code osrs_account} under that user (see docs/cloud-sync-spec.md).
 */
@Slf4j
@Singleton
public class CloudSyncService {

    public static final String CONFIG_GROUP = "runeassistflip";
    public static final String KEY_DEVICE_TOKEN = "cloudDeviceToken";
    public static final String KEY_USER_ID = "cloudUserId";
    public static final String DEFAULT_ORIGIN = "https://runeassist.ares-server.co.uk";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int FLUSH_SEC = 45;
    private static final int BATCH = 200;

    private final OkHttpClient http;
    private final Gson gson;
    private final ConfigManager configManager;
    private final FlippingCopilotConfig config;
    private final LocalFlipLedger localFlipLedger;
    private final OsrsLoginManager osrsLoginManager;
    private final ScheduledExecutorService executor;

    private final ConcurrentLinkedQueue<QueuedTx> outbox = new ConcurrentLinkedQueue<>();
    private final List<Runnable> statusListeners = new CopyOnWriteArrayList<>();
    private volatile boolean started;
    private volatile boolean registering;
    private volatile String lastError;

    @Inject
    public CloudSyncService(
            OkHttpClient http,
            Gson gson,
            ConfigManager configManager,
            FlippingCopilotConfig config,
            LocalFlipLedger localFlipLedger,
            OsrsLoginManager osrsLoginManager,
            @Named("copilotExecutor") ScheduledExecutorService executor) {
        this.http = http;
        this.gson = gson;
        this.configManager = configManager;
        this.config = config;
        this.localFlipLedger = localFlipLedger;
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
                log.debug("cloud sync flush: {}", e.getMessage());
            }
        }, FLUSH_SEC, FLUSH_SEC, TimeUnit.SECONDS);
        if (isEnabled() && !isLinked()) {
            executor.execute(() -> {
                try {
                    ensureRegistered();
                } catch (Exception e) {
                    log.warn("cloud sync register: {}", e.getMessage());
                }
            });
        } else {
            fireStatus();
        }
    }

    public boolean isEnabled() {
        return config.cloudSync();
    }

    public boolean isLinked() {
        return deviceToken() != null && userId() != null;
    }

    /** Short line for Preferences → Cloud sync. Pairing is always offered; this is status only. */
    public String statusMessage() {
        if (!isEnabled()) {
            return "Cloud sync is off. Enable “Cloud sync flip history” in Configuration → Privacy.";
        }
        if (isLinked()) {
            return "This PC is linked. Use the buttons below to pair another device or the website.";
        }
        if (registering) {
            return "Not linked yet — linking… Buttons below still work.";
        }
        if (lastError != null) {
            return "Not linked yet (" + lastError + "). Use the buttons below to retry.";
        }
        return "Not linked yet. Use the buttons below — linking happens in the background.";
    }

    public void addStatusListener(Runnable listener) {
        if (listener != null) {
            statusListeners.add(listener);
        }
    }

    public void onEnabledChanged() {
        if (isEnabled() && !isLinked()) {
            executor.execute(() -> {
                try {
                    ensureRegistered();
                } catch (Exception e) {
                    log.warn("cloud sync register: {}", e.getMessage());
                }
            });
        } else {
            fireStatus();
        }
    }

    public String websiteUrl() {
        return "https://runeassist.com/app/";
    }

    public void onLogin(String displayName) {
        if (!isEnabled() || displayName == null || displayName.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            try {
                syncOnLogin(displayName);
            } catch (Exception e) {
                log.warn("cloud sync on login failed: {}", e.getMessage());
            }
        });
    }

    public void enqueue(Transaction transaction, String displayName) {
        if (!isEnabled() || transaction == null || transaction.getId() == null || displayName == null) {
            return;
        }
        executor.execute(() -> {
            try {
                String osrsAccountId = ensureOsrsAccount(displayName);
                if (osrsAccountId == null) {
                    return;
                }
                outbox.add(new QueuedTx(osrsAccountId, LocalFlipLedger.copyTransaction(transaction)));
            } catch (Exception e) {
                log.debug("cloud sync enqueue: {}", e.getMessage());
            }
        });
    }

    public void flushNow() {
        executor.execute(() -> {
            try {
                flush();
            } catch (Exception e) {
                log.debug("cloud sync flushNow: {}", e.getMessage());
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
    /**
     * Submit a bug report, optionally with a screenshot (raw PNG bytes -- base64-encoded here,
     * matching what the server's {@code /v1/account/feedback} expects). Runs off the calling
     * thread; {@code callback} fires with success/failure.
     */
    public void reportBug(String displayName, String message, byte[] screenshotPng, java.util.function.Consumer<Boolean> callback) {
        executor.execute(() -> {
            boolean ok;
            try {
                ensureRegistered();
                JsonObject req = new JsonObject();
                req.addProperty("message", message);
                if (displayName != null && !displayName.isEmpty()) {
                    req.addProperty("displayName", displayName);
                }
                if (screenshotPng != null && screenshotPng.length > 0) {
                    req.addProperty("screenshot", java.util.Base64.getEncoder().encodeToString(screenshotPng));
                }
                JsonObject body = post("/v1/account/feedback", req, true);
                ok = body != null;
            } catch (Exception e) {
                log.warn("report bug failed: {}", e.getMessage());
                ok = false;
            }
            if (callback != null) {
                boolean result = ok;
                SwingUtilities.invokeLater(() -> callback.accept(result));
            }
        });
    }

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
        }
    }

    private void syncOnLogin(String displayName) throws Exception {
        ensureRegistered();
        String osrsAccountId = ensureOsrsAccount(displayName);
        if (osrsAccountId == null) {
            return;
        }
        pullAndReplay(displayName, osrsAccountId);
        backfill(displayName, osrsAccountId);
        flush();
    }

    private void pullAndReplay(String displayName, String osrsAccountId) {
        String cursor = configManager.getConfiguration(CONFIG_GROUP, cursorKey(displayName));
        String path = "/v1/account/transactions?osrsAccountId=" + urlEnc(osrsAccountId);
        if (cursor != null && !cursor.isEmpty()) {
            path += "&since=" + urlEnc(cursor);
        }
        JsonObject body = get(path, true);
        if (body == null || !body.has("transactions")) {
            return;
        }
        JsonArray arr = body.getAsJsonArray("transactions");
        List<Transaction> incoming = new ArrayList<>();
        Instant newest = null;
        for (JsonElement el : arr) {
            Transaction t = fromJson(el.getAsJsonObject());
            if (t == null) {
                continue;
            }
            incoming.add(t);
            if (t.getTimestamp() != null && (newest == null || t.getTimestamp().isAfter(newest))) {
                newest = t.getTimestamp();
            }
        }
        if (!incoming.isEmpty()) {
            localFlipLedger.applyAll(incoming, displayName);
            log.info("cloud sync pulled {} transactions for {}", incoming.size(), displayName);
        }
        if (body.has("cursor") && !body.get("cursor").isJsonNull()) {
            configManager.setConfiguration(CONFIG_GROUP, cursorKey(displayName), body.get("cursor").getAsString());
        } else if (newest != null) {
            configManager.setConfiguration(CONFIG_GROUP, cursorKey(displayName), newest.toString());
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
            if (!postTransactions(osrsAccountId, all.subList(i, end))) {
                return;
            }
        }
        configManager.setConfiguration(CONFIG_GROUP, doneKey, "1");
    }

    private void flush() {
        if (!isEnabled() || deviceToken() == null) {
            return;
        }
        while (true) {
            List<QueuedTx> batch = new ArrayList<>(BATCH);
            QueuedTx next;
            while (batch.size() < BATCH && (next = outbox.poll()) != null) {
                batch.add(next);
            }
            if (batch.isEmpty()) {
                return;
            }
            String osrsAccountId = batch.get(0).osrsAccountId;
            List<Transaction> txs = new ArrayList<>();
            List<QueuedTx> leftover = new ArrayList<>();
            for (QueuedTx q : batch) {
                if (osrsAccountId.equals(q.osrsAccountId)) {
                    txs.add(q.tx);
                } else {
                    leftover.add(q);
                }
            }
            leftover.forEach(outbox::add);
            if (!postTransactions(osrsAccountId, txs)) {
                txs.forEach(t -> outbox.add(new QueuedTx(osrsAccountId, t)));
                return;
            }
        }
    }

    private boolean postTransactions(String osrsAccountId, List<Transaction> txs) {
        if (txs == null || txs.isEmpty()) {
            return true;
        }
        JsonObject req = new JsonObject();
        req.addProperty("osrsAccountId", osrsAccountId);
        JsonArray arr = new JsonArray();
        for (Transaction t : txs) {
            JsonObject o = toJson(t);
            if (o != null) {
                arr.add(o);
            }
        }
        req.add("transactions", arr);
        JsonObject body = post("/v1/account/transactions", req, true);
        return body != null;
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
            log.info("cloud sync registered user {}", body.get("userId").getAsString());
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
        return "cloudOsrs." + Persistance.hashDisplayName(displayName);
    }

    private String cursorKey(String displayName) {
        return "cloudCursor." + Persistance.hashDisplayName(displayName);
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
                log.warn("cloud sync {} {} → {}", request.method(), request.url().encodedPath(), response.code());
                return null;
            }
            if (raw.isEmpty()) {
                return new JsonObject();
            }
            return gson.fromJson(raw, JsonObject.class);
        } catch (Exception e) {
            log.debug("cloud sync {} failed: {}", request.url().encodedPath(), e.getMessage());
            return null;
        }
    }

    private static String urlEnc(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    static JsonObject toJson(Transaction t) {
        if (t == null || t.getId() == null || t.getType() == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", t.getId().toString());
        o.addProperty("type", t.getType().name());
        o.addProperty("itemId", t.getItemId());
        o.addProperty("price", t.getPrice());
        o.addProperty("quantity", t.getQuantity());
        o.addProperty("boxId", t.getBoxId());
        o.addProperty("amountSpent", t.getAmountSpent());
        Instant ts = t.getTimestamp() != null ? t.getTimestamp() : Instant.now();
        o.addProperty("timestamp", ts.toString());
        return o;
    }

    static Transaction fromJson(JsonObject o) {
        if (o == null || !o.has("id")) {
            return null;
        }
        try {
            Transaction t = new Transaction();
            t.setId(UUID.fromString(o.get("id").getAsString()));
            t.setType(OfferStatus.valueOf(o.get("type").getAsString().toUpperCase()));
            t.setItemId(o.get("itemId").getAsInt());
            t.setPrice(o.get("price").getAsLong());
            t.setQuantity(o.get("quantity").getAsInt());
            t.setBoxId(o.has("boxId") ? o.get("boxId").getAsInt() : 0);
            t.setAmountSpent(o.get("amountSpent").getAsLong());
            String ts = o.get("timestamp").getAsString();
            t.setTimestamp(Instant.parse(ts));
            t.setConsistent(true);
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    private static final class QueuedTx {
        final String osrsAccountId;
        final Transaction tx;

        QueuedTx(String osrsAccountId, Transaction tx) {
            this.osrsAccountId = osrsAccountId;
            this.tx = tx;
        }
    }
}
