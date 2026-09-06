package com.runeassist.flip.controller;

import com.runeassist.flip.controller.history.AccountHttp;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/** Opt-in bug reports. Device token attributes {@code /v1/account/feedback} after dialog confirm. */
@Slf4j
@Singleton
public class BugReportClient {

    public static final String CONFIG_GROUP = "runeassistflip";
    public static final String KEY_DEVICE_TOKEN = "cloudDeviceToken";
    public static final String KEY_USER_ID = "cloudUserId";
    public static final String DEFAULT_ORIGIN = "https://runeassist.com";

    private final AccountHttp api;
    private final ScheduledExecutorService executor;

    @Inject
    public BugReportClient(AccountHttp api, @Named("runeAssistExecutor") ScheduledExecutorService executor) {
        this.api = api;
        this.executor = executor;
    }

    public String websiteUrl() {
        return DEFAULT_ORIGIN;
    }

    /** Submit after consent dialog. Registers a device token if needed. */
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
                ok = api.post("/v1/account/feedback", req, true) != null;
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
        if (api.isLinked()) {
            return;
        }
        JsonObject body = api.post("/v1/account/register", new JsonObject(), false);
        if (body == null || !body.has("deviceToken") || !body.has("userId")) {
            throw new IllegalStateException("register failed");
        }
        api.storeToken(body.get("userId").getAsString(), body.get("deviceToken").getAsString());
    }
}
