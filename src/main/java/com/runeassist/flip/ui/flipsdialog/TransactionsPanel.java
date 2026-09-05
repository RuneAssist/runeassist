package com.runeassist.flip.ui.flipsdialog;

import com.runeassist.flip.controller.FlipHistorySyncService;
import com.runeassist.flip.controller.ItemController;
import com.runeassist.flip.model.OfferStatus;
import com.runeassist.flip.model.OsrsLoginManager;
import com.runeassist.flip.model.Transaction;
import com.runeassist.flip.ui.Paginator;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.runeassist.flip.util.DateUtil.formatEpoch;

/** Lean FC TransactionsPanel: browse GE fills + delete/orphan via existing APIs. */
@Slf4j
public class TransactionsPanel extends JPanel {

    private static final String[] COLUMNS = {"Time", "Side", "Item", "Qty", "Price", "Spent"};
    private static final NumberFormat GP = NumberFormat.getIntegerInstance(Locale.US);
    private static final int PAGE_SIZE = 100;

    private final FlipHistorySyncService sync;
    private final ItemController itemController;
    private final OsrsLoginManager osrsLoginManager;
    private final ExecutorService executor;
    private final PaginatedTablePanel<Transaction> tablePanel;
    private final Paginator paginator;
    private final AtomicBoolean loading = new AtomicBoolean(false);

    private List<Transaction> all = Collections.emptyList();
    private int page = 1;

    public TransactionsPanel(
            FlipHistorySyncService sync,
            ItemController itemController,
            OsrsLoginManager osrsLoginManager,
            ExecutorService executor) {
        this.sync = sync;
        this.itemController = itemController;
        this.osrsLoginManager = osrsLoginManager;
        this.executor = executor;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        tablePanel = new PaginatedTablePanel<>(COLUMNS, this::toRow);
        tablePanel.installPopupHandler(this::showMenu);
        tablePanel.centerColumns(1, 3);
        tablePanel.moneyColumns(GP, 4, 5);

        paginator = new Paginator(n -> {
            page = n;
            renderPage();
        });
        tablePanel.installPageFooter(paginator, PAGE_SIZE, ignored -> {
            page = 1;
            paginator.setPageNumber(1);
            renderPage();
        });

        JButton refresh = new JButton("Refresh");
        refresh.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        refresh.setFocusable(false);
        refresh.addActionListener(e -> load(true));
        tablePanel.rightControls().add(refresh);

        add(tablePanel, BorderLayout.CENTER);
    }

    public void onTabShown() {
        load(false);
    }

    private void load(boolean force) {
        String displayName = osrsLoginManager.getPlayerDisplayName();
        if (displayName == null || sync == null || !sync.isLinked()) {
            all = Collections.emptyList();
            page = 1;
            paginator.setPageNumber(1);
            tablePanel.setRows(Collections.emptyList());
            paginator.setTotalPages(1);
            return;
        }
        if (!force && !all.isEmpty()) {
            renderPage();
            return;
        }
        if (!loading.compareAndSet(false, true)) {
            return;
        }
        tablePanel.setSpinnerVisible(true);
        executor.execute(() -> {
            List<Transaction> rows;
            try {
                rows = sync.listTransactions(displayName);
            } catch (Exception e) {
                log.warn("transactions load failed: {}", e.getMessage());
                rows = Collections.emptyList();
            }
            List<Transaction> finalRows = rows != null ? rows : Collections.emptyList();
            List<Transaction> reversed = new ArrayList<>(finalRows);
            Collections.reverse(reversed);
            SwingUtilities.invokeLater(() -> {
                all = reversed;
                page = 1;
                paginator.setPageNumber(1);
                renderPage();
                tablePanel.setSpinnerVisible(false);
                loading.set(false);
            });
        });
    }

    private void renderPage() {
        int totalPages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        paginator.setTotalPages(totalPages);
        if (page > totalPages) {
            page = totalPages;
            paginator.setPageNumber(page);
        }
        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(all.size(), from + PAGE_SIZE);
        tablePanel.setRows(from >= to ? Collections.emptyList() : all.subList(from, to));
    }

    private Object[] toRow(Transaction tx) {
        String side = OfferStatus.BUY.equals(tx.getType()) ? "BUY" : "SELL";
        String item = itemController != null ? itemController.getItemName(tx.getItemId()) : String.valueOf(tx.getItemId());
        String time = tx.getTimestamp() != null
                ? formatEpoch((int) tx.getTimestamp().getEpochSecond())
                : "";
        return new Object[]{time, side, item, tx.getQuantity(), tx.getPrice(), tx.getAmountSpent()};
    }

    private void showMenu(MouseEvent e, int modelRow) {
        Transaction tx = tablePanel.row(modelRow);
        if (tx == null || tx.getId() == null) {
            return;
        }
        String displayName = osrsLoginManager.getPlayerDisplayName();
        if (displayName == null || sync == null || !sync.isLinked()) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem orphan = new JMenuItem("Remove from flip");
        orphan.addActionListener(evt -> {
            if (JOptionPane.showConfirmDialog(this,
                    "Remove this transaction from its flip? Flip profit will update.",
                    "Confirm", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                return;
            }
            sync.orphanTransaction(displayName, tx.getId());
            all = new ArrayList<>(all);
            all.removeIf(t -> tx.getId().equals(t.getId()));
            renderPage();
        });
        menu.add(orphan);

        JMenuItem delete = new JMenuItem("Delete transaction");
        delete.addActionListener(evt -> {
            if (JOptionPane.showConfirmDialog(this,
                    "Delete this transaction? Related flips will update.",
                    "Confirm", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                return;
            }
            sync.deleteTransaction(displayName, tx.getId());
            all = new ArrayList<>(all);
            all.removeIf(t -> tx.getId().equals(t.getId()));
            renderPage();
        });
        menu.add(delete);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }
}
