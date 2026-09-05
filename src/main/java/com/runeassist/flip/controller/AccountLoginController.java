package com.runeassist.flip.controller;

import com.runeassist.flip.model.*;
import com.runeassist.flip.rs.AccountLoginRS;
import com.runeassist.flip.ui.LoginPanel;
import com.runeassist.flip.ui.MainPanel;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;

@Slf4j
@Singleton
public class AccountLoginController {

    @Setter
    private LoginPanel loginPanel;
    @Setter
    private MainPanel mainPanel;
    private final FlipManager flipManager;
    private final OsrsLoginManager osrsLoginManager;
    private final SessionManager sessionManager;
    private final TransactionManager transactionManager;
    private final AccountLoginRS accountLoginRS;

    @Inject
    public AccountLoginController(FlipManager flipManager,
                                  HighlightController highlightController,
                                  SuggestionManager suggestionManager,
                                  OsrsLoginManager osrsLoginManager,
                                  SessionManager sessionManager,
                                  TransactionManager transactionManager,
                                  AccountLoginRS accountLoginRS) {
        this.flipManager = flipManager;
        this.osrsLoginManager = osrsLoginManager;
        this.sessionManager = sessionManager;
        this.transactionManager = transactionManager;
        this.accountLoginRS = accountLoginRS;
        // RuneAssist: FlipManager userId stays 0 so session GE fills can merge.
        accountLoginRS.registerListener((s) -> {
            if (s.loginResponse == null) {
                suggestionManager.reset();
                highlightController.removeAll();
                if (mainPanel != null) {
                    mainPanel.refresh();
                }
            }
        });
    }

    public void onLoginPressed(String email, String password) {
        log.debug("legacy copilot login is disabled");
    }

    public void onLoginResponse(LoginResponse loginResponse) {
        accountLoginRS.update((s) -> {
            s.loginResponse = loginResponse;
            return s;
        });
        mainPanel.refresh();
        String displayName = osrsLoginManager.getPlayerDisplayName();
        if (displayName != null) {
            flipManager.setIntervalAccount(null);
            flipManager.setIntervalStartTime(sessionManager.getCachedSessionData().startTime);
            transactionManager.scheduleSyncIn(0, displayName);
        }
        flipManager.setPluginUserId(loginResponse.getUserId());
    }

    public void onLoginFailure(String errorMessage) {
        accountLoginRS.set(new AccountLoginState());
        loginPanel.showLoginErrorMessage(errorMessage);
    }

    public Integer getActiveAccountId() {
        String displayName = osrsLoginManager.getPlayerDisplayName();
        if (displayName == null) {
            return null;
        }
        Integer accountId = accountLoginRS.get().getAccountId(displayName);
        if (accountId == null || accountId == -1) {
            return null;
        }
        return accountId;
    }
}
