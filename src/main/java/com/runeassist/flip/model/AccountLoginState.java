package com.runeassist.flip.model;

import lombok.*;

import java.util.*;


@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class AccountLoginState {

    public Map<String,Integer> displayNameToAccountId = new HashMap<>();
    public Map<Integer, String> accountIdToDisplayName = new HashMap<>();

    /** Accounts are registered locally from GE fills; flip records still carry a user id field. */
    public int getUserId() {
        return -1;
    }

    public Set<Integer> accountIds() {
        return accountIdToDisplayName.keySet();
    }

    public Integer getAccountId(String displayName) {
        if(displayName == null) {
            return null;
        }
        return displayNameToAccountId.getOrDefault(displayName, -1);
    }

    public String getDisplayName(Integer accountId) {
        if(accountId == null){
            return null;
        }
        return accountIdToDisplayName.getOrDefault(accountId, "Unknown");
    }

    public AccountLoginState copy() {
        return new AccountLoginState(new HashMap<>(displayNameToAccountId), new HashMap<>(accountIdToDisplayName));
    }
}
