package com.runeassist.flip.rs;

import com.runeassist.flip.model.AccountLoginState;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;


@Singleton
@Slf4j
public class AccountLoginRS extends ReactiveStateImpl<AccountLoginState> {

    @Inject
    public AccountLoginRS() {
        super(new AccountLoginState());
        registerListener((s) -> log.debug("AccountLoginRS to {}", s));
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

    public void addAccountIfMissing(Integer accountId, String displayName) {
        update((s) -> {
            if (accountId == null || displayName == null || s.accountIdToDisplayName.containsKey(accountId)) {
                return s;
            }
            AccountLoginState updated = s.copy();
            updated.displayNameToAccountId.put(displayName, accountId);
            updated.accountIdToDisplayName.put(accountId, displayName);
            return updated;
        });
    }
}
