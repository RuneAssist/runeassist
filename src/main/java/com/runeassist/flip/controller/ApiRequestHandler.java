package com.runeassist.flip.controller;

import com.google.gson.Gson;
import com.google.inject.Singleton;
import com.runeassist.flip.ui.graph.model.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;
import java.util.function.Consumer;

/**
 * Live HTTP to RuneAssist Ares for price graphs. Legacy Flipping Copilot v2 stubs removed.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ApiRequestHandler {

    private final OkHttpClient client;
    private final Gson gson;

    /**
     * Fetch an item's price-history graph from RuneAssist's backend (JSON matching {@link Data}).
     */
    public void asyncGetRuneAssistGraph(int itemId, Consumer<Data> onData, Consumer<Throwable> onError) {
        Request request = new Request.Builder()
                .url("https://runeassist.ares-server.co.uk/v1/graph?id=" + itemId)
                .header("User-Agent", "RuneAssist-flip/1.0")
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
}
