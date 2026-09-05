package com.runeassist.flip.controller;

import com.google.gson.Gson;
import com.google.inject.Singleton;
import com.runeassist.flip.AresMarketClient;
import com.runeassist.flip.model.DumpAlert;
import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionPreferencesManager;
import com.runeassist.flip.rs.AccountSuggestionPreferencesRS;
import com.runeassist.flip.rs.GrandExchangeOpenRS;
import com.runeassist.flip.rs.OsrsLoginRS;
import com.runeassist.flip.rs.ReactiveState;
import com.runeassist.flip.rs.ReactiveStateUtil;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import okhttp3.Call;
import okhttp3.Response;
import okio.BufferedSource;

import javax.inject.Inject;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Subscribes to Ares {@code POST /v1/dump-alerts} when dump-alert prefs are on,
 * the player is logged in, and the GE is open — same lifecycle as Flipping Copilot's
 * {@code DumpsStreamController}, without a Copilot account.
 */
@Slf4j
@Singleton
public class DumpsStreamController {

    // a dump alert is a few hundred bytes; anything near this is a framing bug
    private static final long MAX_FRAME_BYTES = 1 << 20;

    private final ClientThread clientThread;
    private final AresMarketClient aresMarketClient;
    private final SuggestionController suggestionController;
    private final SuggestionPreferencesManager preferencesManager;
    private final Gson gson;
    private final AtomicReference<Call> activeCall = new AtomicReference<>();
    private final ReactiveState<String> subscriptionKey;

    @Inject
    public DumpsStreamController(ClientThread clientThread,
                                 AresMarketClient aresMarketClient,
                                 SuggestionController suggestionController,
                                 SuggestionPreferencesManager preferencesManager,
                                 AccountSuggestionPreferencesRS accountSuggestionPreferencesRS,
                                 OsrsLoginRS osrsLoginRS,
                                 GrandExchangeOpenRS grandExchangeOpenRS,
                                 Gson gson) {
        this.clientThread = clientThread;
        this.aresMarketClient = aresMarketClient;
        this.suggestionController = suggestionController;
        this.preferencesManager = preferencesManager;
        this.gson = gson;
        this.subscriptionKey = ReactiveStateUtil.derive(
                osrsLoginRS,
                accountSuggestionPreferencesRS,
                grandExchangeOpenRS,
                (loginState, preferences, isGrandExchangeOpen) -> {
                    if (loginState == null
                            || !loginState.loggedIn
                            || loginState.displayName == null
                            || loginState.displayName.isBlank()
                            || preferences == null
                            || !preferences.isReceiveDumpSuggestions()
                            || !Boolean.TRUE.equals(isGrandExchangeOpen)) {
                        return null;
                    }
                    long min = preferences.getDumpMinPredictedProfit() != null
                            ? preferences.getDumpMinPredictedProfit()
                            : SuggestionPreferencesManager.DEFAULT_DUMP_MIN_PROFIT;
                    return loginState.displayName + "|" + min + "|" + preferences.isF2pOnlyMode();
                });
        subscriptionKey.registerListener(key -> {
            if (key != null) {
                consumeDumps();
            } else {
                ensureUnsubscribed();
            }
        });
    }

    public void ensureUnsubscribed() {
        Call call = activeCall.getAndSet(null);
        if (call != null) {
            call.cancel();
        }
    }

    private void consumeDumps() {
        String key = subscriptionKey.get();
        if (key == null) {
            return;
        }
        Call previous = activeCall.get();
        Long effective = preferencesManager.getEffectiveDumpMinPredictedProfit();
        long minProfit = effective != null
                ? effective
                : SuggestionPreferencesManager.DEFAULT_DUMP_MIN_PROFIT;
        Call call = aresMarketClient.asyncConsumeDumpAlerts(
                minProfit,
                preferencesManager.isF2pOnlyMode(),
                new ArrayList<>(preferencesManager.blockedItems()),
                this::consumeDumpStream,
                error -> {
                    log.warn("dump alerts connection failed, re-connecting: {}", error.getMessage());
                    if (subscriptionKey.get() != null) {
                        consumeDumps();
                    }
                });
        log.info("subscribing to dumps ({})", key);
        if (call == null) {
            return;
        }
        if (activeCall.getAndSet(call) != previous || subscriptionKey.get() == null) {
            ensureUnsubscribed();
        }
    }

    // each frame is a uvarint byte length followed by that many bytes of JSON DumpAlert;
    // a zero-length frame is the keepalive
    private void consumeDumpStream(Response response) {
        try (Response resp = response) {
            if (resp.body() == null) {
                throw new IOException("empty dump-alert body");
            }
            BufferedSource source = resp.body().source();
            while (activeCall.get() != null) {
                long length = readUvarint(source);
                if (length == 0) {
                    continue;
                }
                if (length < 0 || length > MAX_FRAME_BYTES) {
                    throw new IOException("invalid dump frame length: " + length);
                }
                handleDumpMessage(source.readByteArray(length));
            }
        } catch (IOException e) {
            if (subscriptionKey.get() != null) {
                log.warn("dump alerts stream error", e);
                consumeDumps();
            } else {
                log.info("consumeDumpStream ended gracefully");
            }
        }
    }

    static long readUvarint(BufferedSource source) throws IOException {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int b = source.readByte() & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("dump frame length is not a valid uvarint");
    }

    private void handleDumpMessage(byte[] data) {
        Suggestion suggestion;
        try {
            suggestion = DumpAlert.decodeJson(data, gson).suggestion;
        } catch (Exception e) {
            log.warn("dump suggestion decode failed", e);
            return;
        }
        if (suggestion == null) {
            log.warn("dump suggestion decode failed");
            return;
        }
        suggestion.setMessage("<html><b><font color=#FA4A4B>Dump alert!!</font></b></html>");
        suggestion.setDumpAlert(true);
        suggestion.setDumpAlertReceived(Instant.now());
        clientThread.invoke(() -> suggestionController.handleDumpSuggestion(suggestion));
        log.info("received dump suggestion {} {}", suggestion.getName(), suggestion.getType());
    }
}
