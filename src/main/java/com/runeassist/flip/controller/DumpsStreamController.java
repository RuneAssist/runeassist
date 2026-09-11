package com.runeassist.flip.controller;

import com.google.inject.Singleton;
import com.runeassist.flip.model.AccountStatus;
import com.runeassist.flip.model.AccountStatusManager;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Subscribes to Ares {@code POST /v1/dump-alerts} when dump prefs are on, the
 * player is logged in, and the GE is open — same gating Flipping Copilot used,
 * without a Copilot login. Frames are uvarint-length JSON suggestion DTOs
 * (keepalive = zero-length frame).
 */
@Slf4j
@Singleton
public class DumpsStreamController {

    private static final long MAX_FRAME_BYTES = 1 << 20;

    private final ClientThread clientThread;
    private final ApiRequestHandler apiRequestHandler;
    private final SuggestionController suggestionController;
    private final SuggestionPreferencesManager preferencesManager;
    private final AccountStatusManager accountStatusManager;
    private final AtomicReference<Call> activeCall = new AtomicReference<>();
    private final ReactiveState<Boolean> shouldSubscribe;
    private long subscribedGeneration = -1;

    @Inject
    public DumpsStreamController(
            ClientThread clientThread,
            ApiRequestHandler apiRequestHandler,
            SuggestionController suggestionController,
            SuggestionPreferencesManager preferencesManager,
            AccountStatusManager accountStatusManager,
            AccountSuggestionPreferencesRS accountSuggestionPreferencesRS,
            OsrsLoginRS osrsLoginRS,
            GrandExchangeOpenRS grandExchangeOpenRS) {
        this.clientThread = clientThread;
        this.apiRequestHandler = apiRequestHandler;
        this.suggestionController = suggestionController;
        this.preferencesManager = preferencesManager;
        this.accountStatusManager = accountStatusManager;
        this.shouldSubscribe = ReactiveStateUtil.derive(
                osrsLoginRS,
                accountSuggestionPreferencesRS,
                grandExchangeOpenRS,
                (loginState, preferences, isGrandExchangeOpen) ->
                        loginState != null
                                && loginState.loggedIn
                                && preferences != null
                                && preferences.isReceiveDumpSuggestions()
                                && Boolean.TRUE.equals(isGrandExchangeOpen));
        shouldSubscribe.registerListener(active -> {
            if (Boolean.TRUE.equals(active)) {
                clientThread.invokeLater(this::onGameTick);
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

    /** Keep the optional stream bound to the same local account/trading session. */
    void onGameTick() {
        long generation = suggestionController.getTradingContext().generation();
        boolean active = Boolean.TRUE.equals(shouldSubscribe.get())
                && suggestionController.getTradingContext().canRequest();
        if (!active || generation != subscribedGeneration || activeCall.get() == null) {
            ensureUnsubscribed();
            subscribedGeneration = generation;
            if (active) consumeDumps();
        }
    }

    private void consumeDumps() {
        if (!Boolean.TRUE.equals(shouldSubscribe.get())
                || !suggestionController.getTradingContext().canRequest()) {
            return;
        }
        final long streamGeneration = suggestionController.getTradingContext().generation();
        Call previous = activeCall.get();
        Map<String, Object> filters = buildFilters();
        Call call = apiRequestHandler.asyncConsumeDumpAlerts(
                filters,
                response -> consumeDumpStream(response, streamGeneration),
                error -> {
                    log.warn("dump alerts connection failed, re-connecting: {}", error.getMessage());
                    retryIfCurrent(streamGeneration);
                });
        log.info("subscribing to dump alerts");
        if (activeCall.getAndSet(call) != previous || !Boolean.TRUE.equals(shouldSubscribe.get())) {
            ensureUnsubscribed();
        }
    }

    private void retryIfCurrent(long generation) {
        clientThread.invokeLater(() -> {
            if (Boolean.TRUE.equals(shouldSubscribe.get())
                    && suggestionController.getTradingContext().accepts(generation)) consumeDumps();
        });
    }

    private Map<String, Object> buildFilters() {
        Map<String, Object> body = new HashMap<>();
        Long minProfit = preferencesManager.getEffectiveDumpMinPredictedProfit();
        if (minProfit != null) {
            body.put("dumpMinPredictedProfit", minProfit);
        }
        body.put("f2pOnly", preferencesManager.isF2pOnlyMode());
        List<Integer> blocked = preferencesManager.blockedItems();
        if (blocked != null && !blocked.isEmpty()) {
            body.put("blockedIds", blocked);
        }
        try {
            AccountStatus status = accountStatusManager.getAccountStatus();
            if (status != null) {
                long cash = status.currentCashStack();
                if (cash > 0) {
                    body.put("capital", cash);
                }
                if (status.emptySlotExists()) {
                    body.put("remainingSlots", 1);
                }
            }
        } catch (Exception e) {
            log.debug("dump filter account snapshot failed: {}", e.getMessage());
        }
        return body;
    }

    /** Each frame is a uvarint byte length followed by that many bytes of JSON; zero-length = keepalive. */
    private void consumeDumpStream(Response response, long streamGeneration) {
        try (Response resp = response) {
            BufferedSource source = resp.body().source();
            while (activeCall.get() != null && suggestionController.getTradingContext().accepts(streamGeneration)) {
                long length = readUvarint(source);
                if (length == 0) {
                    continue;
                }
                if (length < 0 || length > MAX_FRAME_BYTES) {
                    throw new IOException("invalid dump frame length: " + length);
                }
                handleDumpMessage(source.readByteArray(length), streamGeneration);
            }
        } catch (IOException e) {
            if (Boolean.TRUE.equals(shouldSubscribe.get())) {
                log.warn("dump alerts stream error", e);
                retryIfCurrent(streamGeneration);
            } else {
                log.info("consumeDumpStream ended gracefully");
            }
        }
    }

    private static long readUvarint(BufferedSource source) throws IOException {
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

    private void handleDumpMessage(byte[] data, long streamGeneration) {
        Suggestion suggestion = apiRequestHandler.decodeDumpSuggestionFrame(data);
        if (suggestion == null) {
            log.warn("dump suggestion decode failed");
            return;
        }
        suggestion.setDumpAlert(true);
        suggestion.setDumpAlertReceived(Instant.now());
        if (suggestion.getMessage() == null || suggestion.getMessage().isEmpty()) {
            suggestion.setMessage("<html><b><font color=#FA4A4B>Dump alert!!</font></b></html>");
        }
        clientThread.invoke(() -> suggestionController.handleDumpSuggestion(suggestion, streamGeneration));
        log.info("received dump suggestion {} {}", suggestion.getName(), suggestion.getType());
    }
}
