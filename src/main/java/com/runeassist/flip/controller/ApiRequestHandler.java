package com.runeassist.flip.controller;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.Singleton;
import com.runeassist.flip.model.ComposeSuggestionMapper;
import com.runeassist.flip.model.ComposeSuggestionResponse;
import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.ui.graph.model.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Live HTTP to RuneAssist Ares for price graphs and the dump-alert stream.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ApiRequestHandler {

    private static final String UA = "RuneAssist-flip/1.0";
    private static final String ARES_ORIGIN = "https://runeassist.ares-server.co.uk";
    private static final String DUMP_ALERTS = ARES_ORIGIN + "/v1/dump-alerts";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final Gson gson;

    /**
     * Fetch an item's price-history graph from RuneAssist's backend (JSON matching {@link Data}).
     */
    public void asyncGetRuneAssistGraph(int itemId, Consumer<Data> onData, Consumer<Throwable> onError) {
        Request request = new Request.Builder()
                .url(ARES_ORIGIN + "/v1/graph?id=" + itemId)
                .header("User-Agent", UA)
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                onError.accept(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) {
                        onError.accept(new RuntimeException("graph HTTP " + r.code()));
                        return;
                    }
                    Data d = gson.fromJson(r.body().charStream(), Data.class);
                    if (d == null) {
                        onError.accept(new RuntimeException("empty graph"));
                        return;
                    }
                    onData.accept(d);
                } catch (Exception ex) {
                    onError.accept(ex);
                }
            }
        });
    }

    /**
     * Open a long-lived dump-alert stream ({@code POST /v1/dump-alerts}). The response is
     * handed to {@code onSuccess} still open — frames are uvarint-length JSON
     * {@code {suggestion}} objects (keepalive = zero-length frame), matching Flipping
     * Copilot's dump-alert framing with JSON instead of protobuf.
     *
     * @param filters dumpMinPredictedProfit / f2pOnly / blockedIds / capital / remainingSlots
     */
    public Call asyncConsumeDumpAlerts(
            Map<String, Object> filters,
            Consumer<Response> onSuccess,
            Consumer<Throwable> onFailure) {
        String bodyJson = gson.toJson(filters != null ? filters : Map.of());
        Request request = new Request.Builder()
                .url(DUMP_ALERTS)
                .header("User-Agent", UA)
                .post(RequestBody.create(JSON, bodyJson))
                .build();

        Call call = client.newBuilder()
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request);

        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                onFailure.accept(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful() || response.body() == null) {
                    int code = response.code();
                    response.close();
                    onFailure.accept(new RuntimeException("dump-alerts HTTP " + code));
                    return;
                }
                onSuccess.accept(response);
            }
        });
        return call;
    }

    /**
     * Decode one dump-alert JSON frame into a {@link Suggestion}, or null if unusable.
     */
    public Suggestion decodeDumpSuggestionFrame(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        try {
            JsonObject root = gson.fromJson(new String(payload, java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
            if (root == null || !root.has("suggestion")) {
                return null;
            }
            ComposeSuggestionResponse.SuggestionDto dto =
                    gson.fromJson(root.get("suggestion"), ComposeSuggestionResponse.SuggestionDto.class);
            return ComposeSuggestionMapper.toSuggestion(dto, "ares-dump");
        } catch (Exception e) {
            log.warn("dump frame decode failed: {}", e.getMessage());
            return null;
        }
    }
}
