package com.runeassist.flip.controller;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.runeassist.flip.model.*;
import com.runeassist.flip.rs.CopilotLoginRS;
import com.runeassist.flip.ui.LoginPanel;
import com.runeassist.flip.ui.MainPanel;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;

@Slf4j
@Singleton
public class CopilotLoginController {

    // dependencies
    @Setter
    private LoginPanel loginPanel;
    @Setter
    private MainPanel mainPanel;
    private final ApiRequestHandler apiRequestHandler;
    private final FlipManager flipManager;
    private final OsrsLoginManager osrsLoginManager;
    private final SessionManager sessionManager;
    private final TransactionManager transactionManager;
    private final ScheduledExecutorService executorService;
    private final CopilotLoginRS copilotLoginRS;


    @Inject
    public CopilotLoginController(ApiRequestHandler apiRequestHandler,
                                  FlipManager flipManager,
                                  HighlightController highlightController,
                                  SuggestionManager suggestionManager,
                                  OsrsLoginManager osrsLoginManager,
                                  SessionManager sessionManager,
                                  TransactionManager transactionManager,
                                  ScheduledExecutorService executorService,
                                  CopilotLoginRS copilotLoginRS) {
        this.apiRequestHandler = apiRequestHandler;
        this.flipManager = flipManager;
        this.osrsLoginManager = osrsLoginManager;
        this.sessionManager = sessionManager;
        this.transactionManager = transactionManager;
        this.executorService = executorService;
        this.copilotLoginRS = copilotLoginRS;
        // RuneAssist fork: keep FlipManager userId at 0 so local GE fills can merge.
        loadCopilotAccounts(0);
        copilotLoginRS.registerListener((s) -> {
            if(s.loginResponse == null) {
                // Do not flipManager.reset() — local flips are the source of truth after the fork.
                suggestionManager.reset();
                highlightController.removeAll();
                if (mainPanel != null) {
                    mainPanel.refresh();
                }
            }
        });
    }

    private void loadCopilotAccounts(int previousFailures) {
        // Legacy Flipping Copilot account/flip sync is disabled. LocalFlipLedger
        // plus CloudSyncService are the RuneAssist sources of truth.
    }

    private void syncFlips(int userId, Map<Integer, Integer> accountIdTime, int previousFailures) {
        // Continuously sync's the delta of new or updated flips from the server with back off on failure
        if(copilotLoginRS.get().getUserId() != userId) {
            log.info("user={}, no longer logged in, stopping syncFlips.", userId);
            return;
        }
        Set<Integer> accountIds = copilotLoginRS.get().accountIds();
        if(accountIds.isEmpty()) {
            long backOffSeconds = Math.min(45, (long) 1+previousFailures);
            log.info("user={}, no accounts loaded - re-scheduling runSyncFlips in {}s", userId, backOffSeconds);
            executorService.schedule(() -> syncFlips(userId, accountIdTime, previousFailures+1), backOffSeconds, TimeUnit.SECONDS);
            return;
        }
        accountIds.forEach(a -> accountIdTime.computeIfAbsent(a, i -> 0));
        long s = System.nanoTime();
        BiConsumer<Integer, FlipsDeltaResult> onSuccess = (Integer copilotUserId, FlipsDeltaResult r) -> {
            if(!flipManager.mergeFlips(r.flips, userId)) {
                log.info("user={}, no longer logged in, stopping syncFlips.", userId);
                return;
            }
            log.debug("user={}, loading {} updated flips - took {}ms", userId, r.flips.size(), (System.nanoTime() - s) / 1000_000);
            accountIds.forEach((a) -> accountIdTime.put(a, r.time));
            executorService.schedule(() -> syncFlips(userId, accountIdTime, 0), 5, TimeUnit.SECONDS);
        };
        Consumer<String> onFailure = (errorMessage) -> {
            long backOffSeconds = Math.min(45, (long) Math.exp(previousFailures));
            log.info("user={}, failed to load updated flips ({}) retrying in {}s", userId, errorMessage, backOffSeconds);
            executorService.schedule(() -> syncFlips(userId, accountIdTime, previousFailures + 1), backOffSeconds, TimeUnit.SECONDS);
        };
        apiRequestHandler.asyncLoadFlips(accountIdTime, onSuccess, onFailure);
    }

    public void onLoginPressed(String email, String password) {
        log.debug("legacy copilot login is disabled");
    }

    public void onLoginResponse(LoginResponse loginResponse) {
        copilotLoginRS.update((s) -> {
            s.loginResponse = loginResponse;
            return s;
        });
        mainPanel.refresh();
        String displayName = osrsLoginManager.getPlayerDisplayName();
        if(displayName != null) {
            flipManager.setIntervalAccount(null);
            flipManager.setIntervalStartTime(sessionManager.getCachedSessionData().startTime);
            transactionManager.scheduleSyncIn(0, displayName);
        }
        flipManager.setCopilotUserId(loginResponse.getUserId());
        loadCopilotAccounts(0);
    }

    public void onLoginFailure(String errorMessage) {
        copilotLoginRS.set(new CopilotLoginState());
        loginPanel.showLoginErrorMessage(errorMessage);
    }

    public Integer getActiveAccountId() {
        String displayName = osrsLoginManager.getPlayerDisplayName();
        if (displayName == null) {
            return null;
        }
        Integer accountId = copilotLoginRS.get().getAccountId(displayName);
        if (accountId == null || accountId == -1) {
            return null;
        }
        return accountId;
    }
}
