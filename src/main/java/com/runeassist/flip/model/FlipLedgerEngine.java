package com.runeassist.flip.model;

import com.runeassist.flip.util.ProfitCalculator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Canonical FIFO open/close matching used by {@link LocalFlipLedger}. Extracted so the
 * JS port ({@code server/flip-ledger.mjs}) and the shared test vectors can exercise the
 * same algorithm without Guice or disk I/O.
 */
public final class FlipLedgerEngine {

    private FlipLedgerEngine() {}

    public static final class Book {
        public int accountId;
        public Map<UUID, FlipV2> flips = new LinkedHashMap<>();
        public Map<Integer, FlipV2> openByItemId = new LinkedHashMap<>();
        public Supplier<UUID> flipIds = UUID::randomUUID;
    }

    public static final class ApplyResult {
        public final long profitThisTx;
        public final UUID flipId;
        public final boolean buy;
        public final FlipV2 touched;

        ApplyResult(long profitThisTx, UUID flipId, boolean buy, FlipV2 touched) {
            this.profitThisTx = profitThisTx;
            this.flipId = flipId;
            this.buy = buy;
            this.touched = touched;
        }
    }

    public static ApplyResult apply(Book book, Transaction transaction) {
        int now = transaction.getTimestamp() != null
                ? (int) transaction.getTimestamp().getEpochSecond()
                : (int) Instant.now().getEpochSecond();
        boolean buy = OfferStatus.BUY.equals(transaction.getType());
        FlipV2 open = book.openByItemId.get(transaction.getItemId());
        UUID flipId = UUID.fromString("00000000-0000-0000-0000-000000000000");
        long profitThisTx = 0L;
        FlipV2 touched = null;

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
            touched = open;
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
                touched = open;
            }
        }

        return new ApplyResult(profitThisTx, flipId, buy, touched);
    }

    public static Book replay(List<Transaction> transactions) {
        Book book = new Book();
        book.accountId = 1;
        if (transactions == null) {
            return book;
        }
        for (Transaction transaction : transactions) {
            if (transaction == null) {
                continue;
            }
            apply(book, transaction);
        }
        return book;
    }

    public static Stats statsOf(Book book) {
        Stats stats = new Stats();
        for (FlipV2 flip : book.flips.values()) {
            stats.addFlip(flip);
        }
        return stats;
    }

    public static long portfolioValue(Book book) {
        long value = 0L;
        for (FlipV2 open : book.openByItemId.values()) {
            if (open == null || FlipStatus.FINISHED.equals(open.getStatus()) || open.isDeleted()) {
                continue;
            }
            int remaining = open.getOpenedQuantity() - open.getClosedQuantity();
            if (remaining <= 0 || open.getOpenedQuantity() <= 0) {
                continue;
            }
            value += (open.getSpent() * remaining) / open.getOpenedQuantity();
        }
        return value;
    }

    public static List<FlipV2> closedNewestFirst(Book book) {
        List<FlipV2> closed = new ArrayList<>();
        for (FlipV2 flip : book.flips.values()) {
            if (flip != null && FlipStatus.FINISHED.equals(flip.getStatus()) && !flip.isDeleted()) {
                closed.add(copyFlip(flip));
            }
        }
        closed.sort(Comparator.comparingInt(FlipV2::getClosedTime).reversed()
                .thenComparing(f -> f.getId() != null ? f.getId().toString() : ""));
        return closed;
    }

    public static List<FlipV2> openPositions(Book book) {
        List<FlipV2> open = new ArrayList<>();
        for (FlipV2 flip : book.openByItemId.values()) {
            if (flip != null && !FlipStatus.FINISHED.equals(flip.getStatus()) && !flip.isDeleted()) {
                open.add(copyFlip(flip));
            }
        }
        open.sort(Comparator.comparingInt(FlipV2::getOpenedTime).reversed());
        return open;
    }

    private static FlipV2 newOpenFlip(Book book, Transaction transaction, int now) {
        FlipV2 flip = new FlipV2();
        flip.setId(book.flipIds.get());
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
        flip.setUserId(LocalFlipLedger.LOCAL_USER_ID);
        book.openByItemId.put(transaction.getItemId(), flip);
        return flip;
    }

    public static FlipV2 copyFlip(FlipV2 src) {
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
}
