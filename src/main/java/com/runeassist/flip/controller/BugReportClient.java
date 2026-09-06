package com.runeassist.flip.controller;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/**
 * Opt-in bug reports. Device token attributes {@code /v1/account/feedback} after dialog confirm.
 */
@Slf4j
@Singleton
public class BugReportClient {

    public static final String CONFIG_GROUP = "runeassistflip";
    public static final String KEY_DEVICE_TOKEN = "cloudDeviceToken";
    public static final String KEY_USER_ID = "cloudUserId";
    public static final String DEFAULT_ORIGIN = "https://runeassist.com";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final Gson gson;
    private final ConfigManager configManager;
    private final ScheduledExecutorService executor;

    @Inject
    public BugReportClient(
            OkHttpClient http,
            Gson gson,
            ConfigManager configManager,
            @Named("runeAssistExecutor") ScheduledExecutorService executor) {
        this.http = http;
        this.gson = gson;
        this.configManager = configManager;
        this.executor = executor;
    }

    public String websiteUrl() {
        return origin();
    }

    /**
     * Submit a bug report after the caller has shown a consent dialog. Registers a device
     * token if needed; independent of telemetry / telemetry toggles.
     */
    public void reportBug(String displayName, String message, byte[] screenshotPng, Consumer<Boolean> callback) {
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
                ok = post("/v1/account/feedback", req, true) != null;
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

    private synchronized void ensureRegistered() throws Exception {
        if (deviceToken() != null && userId() != null) {
            return;
        }
        JsonObject body = post("/v1/account/register", new JsonObject(), false);
        if (body == null || !body.has("deviceToken") || !body.has("userId")) {
            throw new IllegalStateException("register failed");
        }
        configManager.setConfiguration(CONFIG_GROUP, KEY_USER_ID, body.get("userId").getAsString());
        configManager.setConfiguration(CONFIG_GROUP, KEY_DEVICE_TOKEN, body.get("deviceToken").getAsString());
    }

    private String origin() {
        return DEFAULT_ORIGIN;
    }

    private String deviceToken() {
        String t = configManager.getConfiguration(CONFIG_GROUP, KEY_DEVICE_TOKEN);
        return t == null || t.isEmpty() ? null : t;
    }

    private String userId() {
        return configManager.getConfiguration(CONFIG_GROUP, KEY_USER_ID);
    }

    private JsonObject post(String path, JsonObject body, boolean authed) throws Exception {
        Request.Builder b = new Request.Builder()
                .url(origin() + path)
                .header("User-Agent", "RuneAssist-flip/1.0")
                .post(RequestBody.create(JSON, gson.toJson(body)));
        if (authed) {
            String token = deviceToken();
            if (token == null) {
                return null;
            }
            b.header("Authorization", "Bearer " + token);
        }
        try (Response response = http.newCall(b.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            String text = response.body().string();
            if (text == null || text.isEmpty()) {
                return new JsonObject();
            }
            return gson.fromJson(text, JsonObject.class);
        }
    }
}
