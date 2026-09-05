package com.runeassist.flip.model;

import com.google.gson.Gson;
import com.runeassist.flip.controller.Persistance;
import com.runeassist.flip.rs.AccountLoginRS;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOfferState;

import java.nio.charset.StandardCharsets;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Live-session FIFO matching for instant Recent Flips UI. GE fills become
 * {@link Transaction}s in {@link TransactionManager}; this class matches them
 * into {@link FlipV2}s and pushes them into {@link FlipManager} so the panel
 * updates immediately. Durable flip history is server-owned via
 * {@link com.runeassist.flip.controller.FlipHistorySyncService} (upload +
 * flips-delta pull after device/OSRS link) — this ledger is not a local-only
 * history mode.
 */
@Slf4j
@Singleton
public class LocalFlipLedger {

    /** Matches {@link FlipManager}'s default {@code pluginUserId} (0). */
    public static final int LOCAL_USER_ID = 0;

    private final FlipManager flipManager;
    private final AccountLoginRS accountLoginRS;
    private final Gson gson;

    private final Map<String, AccountBook> books = new LinkedHashMap<>();
    private final Set<String> hydratedNames = new HashSet<>();

    @Inject
    public LocalFlipLedger(FlipManager flipManager, AccountLoginRS accountLoginRS, Gson gson) {
        this.flipManager = flipManager;
        this.accountLoginRS = accountLoginRS;
        this.gson = gson;
    }

        public synchronized void hydrate(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return;
        }
        AccountBook book = books.computeIfAbsent(displayName, this::loadBook);
        registerAccount(book);
        flipManager.setPluginUserId(LOCAL_USER_ID);
        if (!hydratedNames.add(displayName)) {
            return;
        }
        if (!book.flips.isEmpty()) {
            List<FlipV2> copies = new ArrayList<>(book.flips.size());
            for (FlipV2 flip : book.flips.values()) {
                FlipV2 copy = copyFlip(flip);
                if (copy != null
                        && !FlipStatus.FINISHED.equals(copy.getStatus())
                        && book.dismissals.contains(dismissalKey(copy.getItemId(), copy.getOpenedTime()))) {
                    copy.setDeleted(true);
                }
                copies.add(copy);
            }
            flipManager.mergeFlips(copies, LOCAL_USER_ID);
        }
    }

    public synchronized boolean hasFlips(String displayName) {
        if (displayName == null) {
            return false;
        }
        AccountBook book = books.get(displayName);
        if (book == null) {
            book = books.computeIfAbsent(displayName, this::loadBook);
            registerAccount(book);
        }
        return !book.flips.isEmpty();
    }

    /**
     * When the local ledger is empty, book current GE fills so session profit/unrealized
     * are not stuck at 0 after a re-login (saved offers already match, so login
     * does not re-infer those transactions).
     */
    public synchronized void seedFromSavedOffers(String displayName, List<SavedOffer> offers) {
        if (displayName == null || displayName.isEmpty() || offers == null || offers.isEmpty()) {
            return;
        }
        hydrate(displayName);
        if (hasFlips(displayName)) {
            return;
        }
        List<Transaction> seeds = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            boolean wantBuy = pass == 0;
            for (int slot = 0; slot < offers.size(); slot++) {
                SavedOffer offer = offers.get(slot);
                if (offer == null || offer.getItemId() <= 0 || offer.getQuantitySold() <= 0) {
                    continue;
                }
                OfferStatus status = offer.getOfferStatus();
                if (status == OfferStatus.EMPTY) {
                    continue;
                }
                if (wantBuy != OfferStatus.BUY.equals(status)) {
                    continue;
                }
                Transaction t = new Transaction();
                String key = "local-seed:" + displayName + ":" + slot + ":" + offer.getItemId()
                        + ":" + offer.getQuantitySold() + ":" + offer.getSpent();
                t.setId(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)));
                t.setType(status);
                t.setItemId(offer.getItemId());
                t.setPrice(offer.getPrice());
                t.setQuantity(offer.getQuantitySold());
                t.setBoxId(slot);
                long spent = offer.getSpent();
                if (spent <= 0) {
                    spent = offer.getPrice() * (long) offer.getQuantitySold();
                }
                t.setAmountSpent(spent);
                t.setTimestamp(Instant.now());
                t.setConsistent(true);
                seeds.add(t);
            }
        }
        if (!seeds.isEmpty()) {
            applyAll(seeds, displayName);
            log.info("seeded {} GE fills into empty local flip ledger for {}", seeds.size(), displayName);
        }
    }

    public synchronized long apply(Transaction transaction, String displayName) {
        if (transaction == null || displayName == null || displayName.isEmpty()) {
            return 0L;
        }
        hydrate(displayName);
        AccountBook book = books.get(displayName);
        UUID txId = transaction.getId();
        if (txId != null && book.appliedTxIds.contains(txId)) {
            return 0L;
        }
        long profit = applyToBook(book, transaction);
        return profit;
    }

    public synchronized void applyAll(List<Transaction> transactions, String displayName) {
        if (transactions == null || transactions.isEmpty() || displayName == null || displayName.isEmpty()) {
            return;
        }
        hydrate(displayName);
        AccountBook book = books.get(displayName);
        boolean changed = false;
        for (Transaction transaction : transactions) {
            if (transaction == null) {
                continue;
            }
            UUID txId = transaction.getId();
            if (txId != null && book.appliedTxIds.contains(txId)) {
                continue;
            }
            applyToBook(book, transaction);
            changed = true;
        }
        if (changed) {
            }
    }

    public synchronized void ensureHydrated(int accountId) {
        for (AccountBook book : books.values()) {
            if (book.accountId == accountId) {
                hydrate(book.displayName);
                return;
            }
        }
        Map<Integer, String> names = accountLoginRS.get().accountIdToDisplayName;
        if (names != null) {
            String name = names.get(accountId);
            if (name != null) {
                hydrate(name);
            }
        }
    }

    public synchronized List<AckedTransaction> transactionsForFlip(UUID flipId) {
        if (flipId == null) {
            return Collections.emptyList();
        }
        List<AckedTransaction> lots = new ArrayList<>();
        for (AccountBook book : books.values()) {
            for (AckedTransaction tx : book.transactions) {
                if (tx != null && flipId.equals(tx.getClientFlipId())) {
                    lots.add(tx);
                }
            }
        }
        lots.sort(Comparator.comparingInt(AckedTransaction::getTime));
        return lots;
    }

    public synchronized void captureCancelledFromOffers(String displayName, List<SavedOffer> offers) {
        if (displayName == null || displayName.isEmpty() || offers == null) {
            return;
        }
        boolean changed = false;
        for (SavedOffer offer : offers) {
            if (addCancelled(displayName, offer)) {
                changed = true;
            }
        }
        if (changed) {
        }
    }

    public synchronized void recordCancelled(String displayName, SavedOffer offer) {
        if (addCancelled(displayName, offer)) {
        }
    }

    public synchronized List<CancelledLeftover> listCancelled(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return Collections.emptyList();
        }
        hydrate(displayName);
        AccountBook book = books.get(displayName);
        if (book == null || book.cancelled.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(book.cancelled.values());
    }

    /**
     * Remove an open/incomplete flip from the local portfolio without deleting GE
     * transactions or closed-flip history. Records a dismissal key so UI/open lists
     * hide the lot; the flip stays in the ledger so later sells still close it.
     *
     * @return the open flip copy that was dismissed, or null if nothing matched
     */
    public synchronized FlipV2 dismissOpenFlip(String displayName, UUID flipId) {
        if (displayName == null || displayName.isEmpty() || flipId == null) {
            return null;
        }
        hydrate(displayName);
        AccountBook book = books.get(displayName);
        if (book == null) {
            return null;
        }
        FlipV2 flip = book.flips.get(flipId);
        if (flip == null || flip.isDeleted() || FlipStatus.FINISHED.equals(flip.getStatus())) {
            return null;
        }
        book.dismissals.add(dismissalKey(flip.getItemId(), flip.getOpenedTime()));
        // Push a deleted copy into FlipManager so open/incomplete UI drops it immediately,
        // while the ledger book keeps the live flip for future sell matching.
        FlipV2 hidden = copyFlip(flip);
        hidden.setDeleted(true);
        hidden.setSeqNo(hidden.getSeqNo() + 1);
        hidden.setUpdatedTime((int) Instant.now().getEpochSecond());
        push(hidden);
        return copyFlip(flip);
    }


    /**
     * Soft-delete a flip from the live UI (FlipManager).
     */
    public synchronized FlipV2 deleteFlip(String displayName, UUID flipId) {
        if (displayName == null || displayName.isEmpty() || flipId == null) {
            return null;
        }
        hydrate(displayName);
        AccountBook book = books.get(displayName);
        if (book == null) {
            return null;
        }
        FlipV2 flip = book.flips.get(flipId);
        if (flip == null) {
            return null;
        }
        FlipV2 hidden = copyFlip(flip);
        hidden.setDeleted(true);
        hidden.setSeqNo(hidden.getSeqNo() + 1);
        hidden.setUpdatedTime((int) Instant.now().getEpochSecond());
        book.flips.put(flipId, hidden);
        FlipV2 open = book.openByItemId.get(flip.getItemId());
        if (open != null && flipId.equals(open.getId())) {
            book.openByItemId.remove(flip.getItemId());
        }
        push(hidden);
        return hidden;
    }

    public synchronized boolean isDismissed(String displayName, int itemId, int openedTime) {
        if (displayName == null || displayName.isEmpty()) {
            return false;
        }
        hydrate(displayName);
        AccountBook book = books.get(displayName);
        return book != null && book.dismissals.contains(dismissalKey(itemId, openedTime));
    }

    private static String dismissalKey(int itemId, int openedTime) {
        return itemId + ":" + openedTime;
    }

    private boolean addCancelled(String displayName, SavedOffer offer) {
        if (displayName == null || displayName.isEmpty() || offer == null) {
            return false;
        }
        GrandExchangeOfferState state = offer.getState();
        boolean buy;
        if (state == GrandExchangeOfferState.CANCELLED_BUY) {
            buy = true;
        } else if (state == GrandExchangeOfferState.CANCELLED_SELL) {
            buy = false;
        } else {
            return false;
        }
        int remaining = offer.getTotalQuantity() - offer.getQuantitySold();
        if (remaining <= 0 || offer.getItemId() <= 0) {
            return false;
        }
        hydrate(displayName);
        AccountBook book = books.get(displayName);
        String key = cancelKey(displayName, offer.getItemId(), remaining, offer.getQuantitySold(), offer.getPrice(), buy);
        if (book.cancelled.containsKey(key)) {
            return false;
        }
        CancelledLeftover row = new CancelledLeftover();
        row.id = key;
        row.itemId = offer.getItemId();
        row.remainingQty = remaining;
        row.filledQty = offer.getQuantitySold();
        row.listedQty = offer.getTotalQuantity();
        row.listedPrice = offer.getPrice();
        row.time = (int) Instant.now().getEpochSecond();
        row.buy = buy;
        row.reason = buy
                ? "Cancelled buy (unfilled leftover)"
                : "Cancelled sell (unsold leftover)";
        book.cancelled.put(key, row);
        return true;
    }

    private static String cancelKey(String displayName, int itemId, int remaining, int filled, long price, boolean buy) {
        String raw = "cancel:" + displayName + ":" + itemId + ":" + remaining + ":" + filled + ":" + price + ":" + (buy ? "b" : "s");
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
    }

            /**
     * Raw GE fills for cloud upload. Oldest first. Reconstructs from signed acked rows
     * when a ledger file predates {@code sourceTransactions}.
     */
    public synchronized List<Transaction> listSourceTransactions(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return Collections.emptyList();
        }
        hydrate(displayName);
        AccountBook book = books.get(displayName);
        if (book == null) {
            return Collections.emptyList();
        }
        if (!book.sourceTransactions.isEmpty()) {
            List<Transaction> copy = new ArrayList<>(book.sourceTransactions.size());
            for (Transaction t : book.sourceTransactions) {
                copy.add(copyTransaction(t));
            }
            return copy;
        }
        List<Transaction> reconstructed = new ArrayList<>();
        for (int i = book.transactions.size() - 1; i >= 0; i--) {
            Transaction t = fromAcked(book.transactions.get(i));
            if (t != null) {
                reconstructed.add(t);
            }
        }
        return reconstructed;
    }

    private long applyToBook(AccountBook book, Transaction transaction) {
        UUID txId = transaction.getId() != null ? transaction.getId() : UUID.randomUUID();
        transaction.setId(txId);

        FlipLedgerEngine.Book engineBook = new FlipLedgerEngine.Book();
        engineBook.accountId = book.accountId;
        engineBook.flips = book.flips;
        engineBook.openByItemId = book.openByItemId;

        FlipLedgerEngine.ApplyResult result = FlipLedgerEngine.apply(engineBook, transaction);
        if (result.touched != null) {
            pushForUi(book, result.touched);
        }

        int now = transaction.getTimestamp() != null
                ? (int) transaction.getTimestamp().getEpochSecond()
                : (int) Instant.now().getEpochSecond();
        AckedTransaction acked = new AckedTransaction();
        acked.setId(txId);
        acked.setClientFlipId(result.flipId);
        acked.setAccountId(book.accountId);
        acked.setTime(now);
        acked.setItemId(transaction.getItemId());
        acked.setQuantity(result.buy ? transaction.getQuantity() : -transaction.getQuantity());
        acked.setPrice(transaction.getPrice());
        acked.setAmountSpent(result.buy ? transaction.getAmountSpent() : -transaction.getAmountSpent());
        book.transactions.add(0, acked);
        book.appliedTxIds.add(txId);
        book.sourceTransactions.add(copyTransaction(transaction));
        return result.profitThisTx;
    }

    private void pushForUi(AccountBook book, FlipV2 flip) {
        FlipV2 copy = copyFlip(flip);
        if (FlipStatus.FINISHED.equals(flip.getStatus())) {
            book.dismissals.remove(dismissalKey(flip.getItemId(), flip.getOpenedTime()));
        } else if (book.dismissals.contains(dismissalKey(flip.getItemId(), flip.getOpenedTime()))) {
            // Keep ledger open for sell matching, but hide from open/incomplete UI.
            copy.setDeleted(true);
        }
        push(copy);
    }

    private void push(FlipV2 flip) {
        flipManager.setPluginUserId(LOCAL_USER_ID);
        List<FlipV2> batch = new ArrayList<>(1);
        batch.add(copyFlip(flip));
        flipManager.mergeFlips(batch, LOCAL_USER_ID);
    }

    private void registerAccount(AccountBook book) {
        accountLoginRS.addAccountIfMissing(book.accountId, book.displayName);
    }

    private AccountBook loadBook(String displayName) {
        AccountBook book = new AccountBook();
        book.displayName = displayName;
        book.accountId = accountIdFor(displayName);
        return book;
    }

    public static int accountIdFor(String displayName) {
        int h = Persistance.hashDisplayName(displayName).hashCode();
        if (h == Integer.MIN_VALUE || h == 0) {
            return 1;
        }
        return Math.abs(h);
    }

    private static FlipV2 copyFlip(FlipV2 src) {
        return FlipLedgerEngine.copyFlip(src);
    }

    public static Transaction copyTransaction(Transaction src) {
        if (src == null) {
            return null;
        }
        Transaction t = new Transaction();
        t.setId(src.getId());
        t.setType(src.getType());
        t.setItemId(src.getItemId());
        t.setPrice(src.getPrice());
        t.setQuantity(src.getQuantity());
        t.setBoxId(src.getBoxId());
        t.setAmountSpent(src.getAmountSpent());
        t.setTimestamp(src.getTimestamp());
        t.setLogin(src.isLogin());
        t.setConsistent(src.isConsistent());
        return t;
    }

    static Transaction fromAcked(AckedTransaction acked) {
        if (acked == null || acked.getId() == null) {
            return null;
        }
        Transaction t = new Transaction();
        t.setId(acked.getId());
        boolean buy = acked.getQuantity() >= 0;
        t.setType(buy ? OfferStatus.BUY : OfferStatus.SELL);
        t.setItemId(acked.getItemId());
        t.setPrice(acked.getPrice());
        t.setQuantity(Math.abs(acked.getQuantity()));
        t.setBoxId(0);
        t.setAmountSpent(Math.abs(acked.getAmountSpent()));
        t.setTimestamp(Instant.ofEpochSecond(acked.getTime()));
        t.setConsistent(true);
        return t;
    }

    private static final class AccountBook {
        int accountId;
        String displayName;
        final Map<UUID, FlipV2> flips = new LinkedHashMap<>();
        final Map<Integer, FlipV2> openByItemId = new LinkedHashMap<>();
        final List<AckedTransaction> transactions = new ArrayList<>();
        final Set<UUID> appliedTxIds = new HashSet<>();
        final Map<String, CancelledLeftover> cancelled = new LinkedHashMap<>();
        final List<Transaction> sourceTransactions = new ArrayList<>();
        /** itemId:openedTime keys for open lots removed from the portfolio UI. */
        final Set<String> dismissals = new HashSet<>();
    }

    public static final class CancelledLeftover {
        public String id;
        public int itemId;
        public int remainingQty;
        public int filledQty;
        public int listedQty;
        public long listedPrice;
        public int time;
        public boolean buy;
        public String reason;
    }
}
