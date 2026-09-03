package com.runeassist.flip.rs;

import com.runeassist.flip.controller.Persistance;
import com.runeassist.flip.model.AccountLoginState;
import com.runeassist.flip.model.LoginResponse;
import com.google.gson.*;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.ExecutorService;

@Singleton
@Slf4j
public class AccountLoginRS extends ReactiveStateImpl<AccountLoginState> {

    private final File file = new File(Persistance.PLUGIN_DIR, Persistance.LOGIN_RESPONSE_JSON_FILE);

    private final Gson gson;
    private final ExecutorService executorService;

    @Inject
    public AccountLoginRS(Gson gson, ExecutorService executorService) {
        super(new AccountLoginState());
        this.gson = gson;
        this.executorService = executorService;
        registerListener((s) -> log.debug("AccountLoginRS to {}", s));
        update(s -> {
            s.loginResponse = loadLoginResponse();
            return s;
        });
        ReactiveStateUtil.derive(this, (s)-> s.loginResponse).registerListener(this::saveLoginResponseAsync);
    }

    private void saveLoginResponseAsync(LoginResponse lr) {
        if (lr == null) {
            return;
        }
        executorService.submit(() -> {
            try {
                String json = gson.toJson(lr);
                Path target = file.toPath();
                Path tmp = Files.createTempFile(target.getParent(), "login-response", ".tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                log.warn("Error saving login response", e);
            }
        });
    }

    private LoginResponse loadLoginResponse() {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return gson.fromJson(reader, LoginResponse.class);
        } catch (FileNotFoundException ignored) {
            return null;
        } catch (JsonSyntaxException | JsonIOException | IOException e) {
            log.warn("error loading saved login json file {}", file, e);
            return null;
        }
    }

    public void clear () {
        if (file.exists() && !file.delete()) {
            log.warn("failed to delete login response file {}", file);
        }
        set(new AccountLoginState());
    }


    public void removeAccount(Integer accountId) {
        update((s) -> {
            AccountLoginState updated = s.copy();
            String displayName = updated.accountIdToDisplayName.get(accountId);
            updated.accountIdToDisplayName.remove(accountId);
            if(displayName != null){
                updated.displayNameToAccountId.remove(displayName);
            }
            return updated;
        });
    }

    public void addAccountIfMissing(Integer accountId, String displayName, int pluginUserId) {
        update((s) -> {
            if (accountId == null || displayName == null || s.accountIdToDisplayName.containsKey(accountId)) {
                return s;
            }
            // RuneAssist fork: local GE fills register an account without an FC login.
            if (s.isLoggedIn() && s.getUserId() != pluginUserId
                    && pluginUserId != 0) {
                return s;
            }
            AccountLoginState updated = s.copy();
            updated.displayNameToAccountId.put(displayName, accountId);
            updated.accountIdToDisplayName.put(accountId, displayName);
            return updated;
        });
    }
}
