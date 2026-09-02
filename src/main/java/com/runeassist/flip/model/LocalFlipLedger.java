package com.runeassist.flip.model;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.runeassist.flip.controller.Persistance;
import com.runeassist.flip.rs.CopilotLoginRS;
import com.runeassist.flip.util.ProfitCalculator;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOfferState;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * Local replacement for Flipping Copilot's transaction→flip server.
 * GE fills already become {@link Transaction}s in {@link TransactionManager}; this class
 * matches them FIFO-style into {@link FlipV2}s, persists them, and pushes them into
 * {@link FlipManager} so the panel / flips dialog can show profit without an FC account.
 */
@Slf4j
@Singleton
public class LocalFlipLedger {

    /** Matches {@link FlipManager}'s default {@code copilotUserId} (0). */
    public static final int LOCAL_USER_ID = 0;

    private final FlipManager flipManager;
    private final CopilotLoginRS copilotLoginRS;
    private final Gson gson;

    private final Map<String, AccountBook> books = new LinkedHashMap<>();
    private final Set<String> hydratedNames = new HashSet<>();

    @Inject
    public LocalFlipLedger(FlipManager flipManager, CopilotLoginRS copilotLoginRS, Gson gson) {
        this.flipManager = flipManager;
        this.copilotLoginRS = copilotLoginRS;
        this.gson = gson;
    }

        public synchronized void hydrate(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return;
        }
        AccountBook book = books.computeIfAbsent(displayName, this::loadBook);
        registerAccount(book);
        flipManager.setCopilotUserId(LOCAL_USER_ID);
        if (!hydratedNames.add(displayName)) {
            return;
        }
        if (!book.flips.isEmpty()) {
            List<FlipV2> copies = new ArrayList<>(book.flips.size());
            for (FlipV2 flip : book.flips.values()) {
                copies.add(copyFlip(flip));
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
     * are not stuck at 0 after the FC-server cutover (saved offers already match, so login
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
        persist(book);
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
            persist(book);
        }
    }

    public synchronized void ensureHydrated(int accountId) {
        for (AccountBook book : books.values()) {
            if (book.accountId == accountId) {
                hydrate(book.displayName);
                return;
            }
        }
        Map<Integer, String> names = copilotLoginRS.get().accountIdToDisplayName;
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
            persist(books.get(displayName));
        }
    }

    public synchronized void recordCancelled(String displayName, SavedOffer offer) {
        if (addCancelled(displayName, offer)) {
            persist(books.get(displayName));
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

    public synchronized byte[] encodeAckedTransactionsRaw() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (AccountBook book : books.values()) {
            for (AckedTransaction tx : book.transactions) {
                byte[] raw = tx.toRaw();
                out.write(raw, 0, raw.length);
            }
        }
        return out.toByteArray();
    }

    public synchronized byte[] encodeAckedTransactionsRaw(String displayName) {
        if (displayName == null) {
            return encodeAckedTransactionsRaw();
        }
        AccountBook book = books.get(displayName);
        if (book == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (AckedTransaction tx : book.transactions) {
            byte[] raw = tx.toRaw();
            out.write(raw, 0, raw.length);
        }
        return out.toByteArray();
    }

    private long applyToBook(AccountBook book, Transaction transaction) {
        UUID txId = transaction.getId() != null ? transaction.getId() : UUID.randomUUID();
        transaction.setId(txId);

        int now = transaction.getTimestamp() != null
                ? (int) transaction.getTimestamp().getEpochSecond()
                : (int) Instant.now().getEpochSecond();
        boolean buy = OfferStatus.BUY.equals(transaction.getType());
        FlipV2 open = book.openByItemId.get(transaction.getItemId());
        UUID flipId = UUID.fromString("00000000-0000-0000-0000-000000000000");
        long profitThisTx = 0L;

        if (buy) {
            if (open == null || FlipStatus.FINISHED.equals(open.getStatus())) {
                open = newOpenFlip(book, transaction, now);
            } else {
                open.setOpenedQuantity(open.getOpenedQuantity() + transaction.getQuantity());
                open.setSpent(open.getSpent() + transaction.getAmountSpent());
                open.setUpdatedTime(now);
                open.setSeqNo(open.getSeqNo() + 1);
                open.setStatus(open.getClosedQuantity() > 0 ? FlipStatus.SELLING : FlipStatus.BUYING);
            }
            flipId = open.getId();
            book.flips.put(open.getId(), copyFlip(open));
            push(open);
        } else if (open != null && !FlipStatus.FINISHED.equals(open.getStatus())) {
            int remaining = open.getOpenedQuantity() - open.getClosedQuantity();
            int amountToClose = Math.min(remaining, transaction.getQuantity());
            if (amountToClose > 0) {
                long sellPrice = transaction.getQuantity() > 0
                        ? transaction.getAmountSpent() / transaction.getQuantity()
                        : transaction.getPrice();
                long sellPostTax = ProfitCalculator.getPostTaxPrice(transaction.getItemId(), sellPrice);
                long taxEach = sellPrice - sellPostTax;
                long gpOut = (open.getSpent() * amountToClose) / Math.max(1, open.getOpenedQuantity());
                long gpIn = (long) amountToClose * sellPostTax;
                profitThisTx = gpIn - gpOut;

                open.setClosedQuantity(open.getClosedQuantity() + amountToClose);
                open.setReceivedPostTax(open.getReceivedPostTax() + gpIn);
                open.setTaxPaid(open.getTaxPaid() + taxEach * amountToClose);
                open.setProfit(open.getProfit() + profitThisTx);
                open.setClosedTime(now);
                open.setUpdatedTime(now);
                open.setSeqNo(open.getSeqNo() + 1);
                if (open.getClosedQuantity() >= open.getOpenedQuantity()) {
                    open.setStatus(FlipStatus.FINISHED);
                    book.openByItemId.remove(transaction.getItemId());
                } else {
                    open.setStatus(FlipStatus.SELLING);
                }
                flipId = open.getId();
                book.flips.put(open.getId(), copyFlip(open));
                push(open);
            }
        }

        AckedTransaction acked = new AckedTransaction();
        acked.setId(txId);
        acked.setClientFlipId(flipId);
        acked.setAccountId(book.accountId);
        acked.setTime(now);
        acked.setItemId(transaction.getItemId());
        acked.setQuantity(buy ? transaction.getQuantity() : -transaction.getQuantity());
        acked.setPrice(transaction.getPrice());
        acked.setAmountSpent(buy ? transaction.getAmountSpent() : -transaction.getAmountSpent());
        book.transactions.add(0, acked);
        book.appliedTxIds.add(txId);
        return profitThisTx;
    }

    private FlipV2 newOpenFlip(AccountBook book, Transaction transaction, int now) {
        FlipV2 flip = new FlipV2();
        flip.setId(UUID.randomUUID());
        flip.setAccountId(book.accountId);
        flip.setItemId(transaction.getItemId());
        flip.setOpenedTime(now);
        flip.setOpenedQuantity(transaction.getQuantity());
        flip.setSpent(transaction.getAmountSpent());
        flip.setClosedTime(0);
        flip.setClosedQuantity(0);
        flip.setReceivedPostTax(0);
        flip.setProfit(0);
        flip.setTaxPaid(0);
        flip.setStatus(FlipStatus.BUYING);
        flip.setUpdatedTime(now);
        flip.setDeleted(false);
        flip.setPortfolioId(PortfolioId.PERSONAL_PORTFOLIO);
        flip.setSeqNo(1);
        flip.setUserId(LOCAL_USER_ID);
        book.openByItemId.put(transaction.getItemId(), flip);
        return flip;
    }

    private void push(FlipV2 flip) {
        flipManager.setCopilotUserId(LOCAL_USER_ID);
        List<FlipV2> batch = new ArrayList<>(1);
        batch.add(copyFlip(flip));
        flipManager.mergeFlips(batch, LOCAL_USER_ID);
    }

    private void registerAccount(AccountBook book) {
        copilotLoginRS.addAccountIfMissing(book.accountId, book.displayName, LOCAL_USER_ID);
    }

    private AccountBook loadBook(String displayName) {
        AccountBook book = new AccountBook();
        book.displayName = displayName;
        book.accountId = accountIdFor(displayName);
        File file = bookFile(displayName);
        if (!file.exists()) {
            return book;
        }
        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            PersistedState saved = gson.fromJson(json, new TypeToken<PersistedState>(){}.getType());
            if (saved == null) {
                return book;
            }
            if (saved.accountId != 0) {
                book.accountId = saved.accountId;
            }
            if (saved.flips != null) {
                for (FlipV2 flip : saved.flips) {
                    if (flip == null || flip.getId() == null) {
                        continue;
                    }
                    flip.setAccountId(book.accountId);
                    flip.setUserId(LOCAL_USER_ID);
                    book.flips.put(flip.getId(), flip);
                    if (!FlipStatus.FINISHED.equals(flip.getStatus()) && !flip.isDeleted()) {
                        book.openByItemId.put(flip.getItemId(), flip);
                    }
                }
            }
            if (saved.transactions != null) {
                book.transactions.addAll(saved.transactions);
            }
            if (saved.appliedTransactionIds != null) {
                for (String id : saved.appliedTransactionIds) {
                    try {
                        book.appliedTxIds.add(UUID.fromString(id));
                    } catch (IllegalArgumentException ignored) {
                        // skip malformed ids
                    }
                }
            }
            if (saved.cancelled != null) {
                for (CancelledLeftover row : saved.cancelled) {
                    if (row == null || row.id == null) {
                        continue;
                    }
                    book.cancelled.put(row.id, row);
                }
            }
            log.info("loaded {} local flips / {} transactions / {} cancelled leftovers for {}",
                    book.flips.size(), book.transactions.size(), book.cancelled.size(), displayName);
        } catch (Exception e) {
            log.warn("failed loading local flip ledger for {}", displayName, e);
        }
        return book;
    }

    private void persist(AccountBook book) {
        try {
            File dir = Persistance.COPILOT_DIR;
            if (!dir.exists() && !dir.mkdirs()) {
                log.warn("unable to create {}", dir);
                return;
            }
            PersistedState state = new PersistedState();
            state.accountId = book.accountId;
            state.displayName = book.displayName;
            state.flips = new ArrayList<>(book.flips.values());
            state.transactions = new ArrayList<>(book.transactions);
            state.appliedTransactionIds = new ArrayList<>();
            for (UUID id : book.appliedTxIds) {
                state.appliedTransactionIds.add(id.toString());
            }
            state.cancelled = new ArrayList<>(book.cancelled.values());
            File file = bookFile(book.displayName);
            Files.writeString(file.toPath(), gson.toJson(state), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("failed saving local flip ledger for {}", book.displayName, e);
        }
    }

    private static File bookFile(String displayName) {
        return new File(Persistance.COPILOT_DIR, Persistance.hashDisplayName(displayName) + "_local_flips.json");
    }

    public static int accountIdFor(String displayName) {
        int h = Persistance.hashDisplayName(displayName).hashCode();
        if (h == Integer.MIN_VALUE || h == 0) {
            return 1;
        }
        return Math.abs(h);
    }

    private static FlipV2 copyFlip(FlipV2 src) {
        FlipV2 f = new FlipV2();
        f.setId(src.getId());
        f.setAccountId(src.getAccountId());
        f.setItemId(src.getItemId());
        f.setOpenedTime(src.getOpenedTime());
        f.setOpenedQuantity(src.getOpenedQuantity());
        f.setSpent(src.getSpent());
        f.setClosedTime(src.getClosedTime());
        f.setClosedQuantity(src.getClosedQuantity());
        f.setReceivedPostTax(src.getReceivedPostTax());
        f.setProfit(src.getProfit());
        f.setTaxPaid(src.getTaxPaid());
        f.setStatus(src.getStatus());
        f.setUpdatedTime(src.getUpdatedTime());
        f.setDeleted(src.isDeleted());
        f.setPortfolioId(src.getPortfolioId());
        f.setSeqNo(src.getSeqNo());
        f.setUserId(src.getUserId());
        f.setCachedItemName(src.getCachedItemName());
        return f;
    }

    private static final class AccountBook {
        int accountId;
        String displayName;
        final Map<UUID, FlipV2> flips = new LinkedHashMap<>();
        final Map<Integer, FlipV2> openByItemId = new LinkedHashMap<>();
        final List<AckedTransaction> transactions = new ArrayList<>();
        final Set<UUID> appliedTxIds = new HashSet<>();
        final Map<String, CancelledLeftover> cancelled = new LinkedHashMap<>();
    }

    static final class PersistedState {
        int accountId;
        String displayName;
        List<FlipV2> flips;
        List<AckedTransaction> transactions;
        List<String> appliedTransactionIds;
        List<CancelledLeftover> cancelled;
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
