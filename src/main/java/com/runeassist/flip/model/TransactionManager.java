package com.runeassist.flip.model;

import com.runeassist.flip.controller.FlipHistorySyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TransactionManager {
    private final OsrsLoginManager osrsLoginManager;
    private final FlipManager flipManager;
    private final FlipHistorySyncService flipHistorySyncService;

    public long addTransaction(Transaction transaction, String displayName) {
        if (osrsLoginManager.isUnsupportedWorldType()) {
            log.debug("ignoring transaction for {} on unsupported world type(s)", displayName);
            return 0;
        }
        flipHistorySyncService.enqueue(transaction, displayName);
        int accountId = FlipHistorySyncService.accountIdFor(displayName);
        Long est = flipManager.estimateTransactionProfit(accountId, transaction);
        return est == null ? 0L : est;
    }

    public List<Transaction> getUnAckedTransactions(String displayName) {
        return flipHistorySyncService.listUnacked(displayName);
    }
}
