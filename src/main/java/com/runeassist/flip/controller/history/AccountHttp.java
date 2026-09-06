package com.runeassist.flip.controller.history;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.runeassist.flip.controller.BugReportClient;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Shared JSON HTTP + device-token helpers for account APIs. */
@Slf4j
@Singleton
public class AccountHttp {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String UA = "RuneAssist-flip/1.0";

    private final OkHttpClient http;
    private final Gson gson;
    private final ConfigManager config;

    @Inject
    public AccountHttp(OkHttpClient http, Gson gson, ConfigManager config) {
        this.http = http;
        this.gson = gson;
        this.config = config;
    }

    public Gson gson() {
        return gson;
    }

    public String origin() {
        return BugReportClient.DEFAULT_ORIGIN;
    }

    public String deviceToken() {
        String t = config.getConfiguration(BugReportClient.CONFIG_GROUP, BugReportClient.KEY_DEVICE_TOKEN);
        return t == null || t.isEmpty() ? null : t;
    }

    public String userId() {
        return config.getConfiguration(BugReportClient.CONFIG_GROUP, BugReportClient.KEY_USER_ID);
    }

    public void storeToken(String userId, String token) {
        config.setConfiguration(BugReportClient.CONFIG_GROUP, BugReportClient.KEY_USER_ID, userId);
        config.setConfiguration(BugReportClient.CONFIG_GROUP, BugReportClient.KEY_DEVICE_TOKEN, token);
    }

    public boolean isLinked() {
        return deviceToken() != null && userId() != null;
    }

    public JsonObject get(String path, boolean authed) {
        Request.Builder b = new Request.Builder().url(origin() + path).header("User-Agent", UA).get();
        return auth(b, authed) ? execute(b.build()) : null;
    }

    public JsonObject post(String path, JsonObject json, boolean authed) {
        Request.Builder b = new Request.Builder()
                .url(origin() + path)
                .header("User-Agent", UA)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(JSON, gson.toJson(json)));
        return auth(b, authed) ? execute(b.build()) : null;
    }

    private boolean auth(Request.Builder b, boolean authed) {
        if (!authed) {
            return true;
        }
        String token = deviceToken();
        if (token == null) {
            return false;
        }
        b.header("Authorization", "Bearer " + token);
        return true;
    }

    private JsonObject execute(Request request) {
        try (Response response = http.newCall(request).execute()) {
            String raw = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.warn("account http {} {} → {}", request.method(), request.url().encodedPath(), response.code());
                return null;
            }
            return raw.isEmpty() ? new JsonObject() : gson.fromJson(raw, JsonObject.class);
        } catch (Exception e) {
            log.debug("account http {} failed: {}", request.url().encodedPath(), e.getMessage());
            return null;
        }
    }

    public static String urlEnc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
