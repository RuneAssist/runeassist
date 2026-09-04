package com.runeassist.flip.ui.flipsdialog;

import com.runeassist.flip.controller.CloudSyncService;
import com.runeassist.flip.controller.ItemController;
import com.runeassist.flip.model.*;
import com.runeassist.flip.rs.*;
import com.runeassist.flip.ui.RuneAssistColors;
import com.runeassist.flip.ui.Spinner;
import com.runeassist.flip.ui.components.ItemSearchMultiSelect;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;

import static com.runeassist.flip.util.DateUtil.formatEpoch;
import static com.runeassist.flip.util.DateUtil.formatEpochOrNa;
import java.util.List;

@Slf4j
public class MissedFlipsPanel extends JPanel {

    private static final NumberFormat GP_FORMAT = NumberFormat.getNumberInstance(Locale.US);
    private static final String[] COLUMN_NAMES = {
            "Time", "Item", "Why", "Qty left", "Listed price", "Filled", "Listed qty"
    };

    private static final String SECTIONS_CARD = "sections";
    private static final String LOGIN_PROMPT_CARD = "login";

    private static final long MAX_AGE_SECONDS = 30L * 24 * 60 * 60;

    private final FlipManager flipsManager;
    private final ItemController itemController;
    private final AccountLoginRS accountLoginRS;
    private final OsrsLoginRS osrsLoginRS;
    private final ExecutorService executorService;
    private final GeHistoryStateRS geHistoryStateRS;
    private final LocalFlipLedger localFlipLedger;
    private final OfferManager offerManager;
    private final CloudSyncService cloudSyncService;

    private final Spinner spinner;
    private final JPanel spinnerOverlay;
    private final ItemSearchMultiSelect searchField;
    private final JLabel geHistoryStatusLabel;
    private final CardLayout cardLayout;
    private final JPanel cardPanel;

    private final Section incompleteSection;
    private final Section cancelledSection;

    private Set<Integer> filteredItems = new HashSet<>();

    public MissedFlipsPanel(OsrsLoginRS osrsLoginRS,
                            FlipManager flipsManager,
                            ItemController itemController,
                            AccountLoginRS accountLoginRS,
                            ExecutorService executorService,
                            GeHistoryStateRS geHistoryStateRS,
                            LocalFlipLedger localFlipLedger,
                            OfferManager offerManager,
                            CloudSyncService cloudSyncService) {
        this.osrsLoginRS = osrsLoginRS;
        this.flipsManager = flipsManager;
        this.itemController = itemController;
        this.accountLoginRS = accountLoginRS;
        this.executorService = executorService;
        this.geHistoryStateRS = geHistoryStateRS;
        this.localFlipLedger = localFlipLedger;
        this.offerManager = offerManager;
        this.cloudSyncService = cloudSyncService;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        topPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        searchField = ItemSearchMultiSelect.itemsFilter(this, itemController,
                () -> new HashSet<>(filteredItems), this::setFilteredItems);

        leftPanel.add(searchField);
        topPanel.add(leftPanel, BorderLayout.WEST);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        rightPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        geHistoryStatusLabel = new JLabel();
        geHistoryStatusLabel.setForeground(RuneAssistColors.ACCENT);
        geHistoryStatusLabel.setVisible(false);
        rightPanel.add(geHistoryStatusLabel);
        topPanel.add(rightPanel, BorderLayout.EAST);

        add(topPanel, BorderLayout.NORTH);

        incompleteSection = new Section("Incomplete flips (unsold remainder)");
        cancelledSection = new Section("Cancelled GE offers (leftover thrown away)");

        JPanel sectionsPanel = new JPanel(new GridLayout(2, 1, 0, 10));
        sectionsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        sectionsPanel.add(incompleteSection.container);
        sectionsPanel.add(cancelledSection.container);

        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);
        cardPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        cardPanel.add(sectionsPanel, SECTIONS_CARD);
        cardPanel.add(DialogUi.centeredMessage(
                "Log into the game to view missed flips from this account's local ledger.",
                ColorScheme.DARK_GRAY_COLOR, true, 18f), LOGIN_PROMPT_CARD);

        spinner = new Spinner();
        spinner.show();
        spinnerOverlay = new JPanel(new GridBagLayout());
        spinnerOverlay.setBackground(ColorScheme.DARK_GRAY_COLOR);
        spinnerOverlay.setOpaque(true);
        spinnerOverlay.add(spinner);
        spinnerOverlay.setVisible(false);

        JLayeredPane layeredPane = new JLayeredPane();
        layeredPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
        layeredPane.setOpaque(true);
        layeredPane.setLayout(new OverlayLayout(layeredPane));
        layeredPane.add(spinnerOverlay, JLayeredPane.MODAL_LAYER);
        layeredPane.add(cardPanel, JLayeredPane.DEFAULT_LAYER);

        add(layeredPane, BorderLayout.CENTER);

        osrsLoginRS.registerListener(state -> SwingUtilities.invokeLater(this::refresh));
        geHistoryStateRS.registerListener(state -> SwingUtilities.invokeLater(this::updateGeHistoryStatusLabel));
        updateGeHistoryStatusLabel();
    }

    private void updateGeHistoryStatusLabel() {
        GeHistoryState state = geHistoryStateRS.get();
        if (state == null || !state.isLoaded() || state.getCapturedAt() <= 0) {
            geHistoryStatusLabel.setVisible(false);
            return;
        }
        geHistoryStatusLabel.setText("GE history known since " + formatEpoch(state.getCapturedAt()));
        geHistoryStatusLabel.setVisible(true);
    }

    private void setFilteredItems(Set<Integer> items) {
        filteredItems = items == null ? new HashSet<>() : new HashSet<>(items);
        refresh();
    }

    public void onTabShown() {
        refresh();
    }

    private String resolveDisplayName() {
        if (osrsLoginRS == null || osrsLoginRS.get() == null) {
            return null;
        }
        String displayName = osrsLoginRS.get().displayName;
        return displayName == null || displayName.isEmpty() ? null : displayName;
    }

    private Integer resolveAccountId(String displayName) {
        Map<String, Integer> map = accountLoginRS.get().displayNameToAccountId;
        if (map != null && map.get(displayName) != null) {
            return map.get(displayName);
        }
        return LocalFlipLedger.accountIdFor(displayName);
    }

    private void refresh() {
        executorService.submit(() -> {
            String displayName = resolveDisplayName();
            List<LocalMissedFlip> incomplete = new ArrayList<>();
            List<LocalMissedFlip> cancelled = new ArrayList<>();
            if (displayName != null) {
                localFlipLedger.hydrate(displayName);
                Integer accountId = resolveAccountId(displayName);
                int cutoff = (int) (Instant.now().getEpochSecond() - MAX_AGE_SECONDS);
                for (FlipV2 f : flipsManager.getIncompleteFlipsForAccount(accountId)) {
                    if (f.getUpdatedTime() < cutoff && f.getOpenedTime() < cutoff) {
                        continue;
                    }
                    if (!filteredItems.isEmpty() && !filteredItems.contains(f.getItemId())) {
                        continue;
                    }
                    incomplete.add(fromIncomplete(f));
                }
                Set<String> seen = new HashSet<>();
                for (LocalFlipLedger.CancelledLeftover row : localFlipLedger.listCancelled(displayName)) {
                    if (row.time > 0 && row.time < cutoff) {
                        continue;
                    }
                    if (!filteredItems.isEmpty() && !filteredItems.contains(row.itemId)) {
                        continue;
                    }
                    LocalMissedFlip m = fromCancelled(row);
                    cancelled.add(m);
                    seen.add(dedupeKey(m));
                }
                for (LocalMissedFlip live : liveCancelledSlots()) {
                    if (!filteredItems.isEmpty() && !filteredItems.contains(live.getItemId())) {
                        continue;
                    }
                    if (seen.add(dedupeKey(live))) {
                        cancelled.add(live);
                    }
                }
            }
            boolean showLoginPrompt = displayName == null;
            SwingUtilities.invokeLater(() -> {
                cardLayout.show(cardPanel, showLoginPrompt ? LOGIN_PROMPT_CARD : SECTIONS_CARD);
                incompleteSection.update(incomplete);
                cancelledSection.update(cancelled);
            });
        });
    }

    private List<LocalMissedFlip> liveCancelledSlots() {
        Long accountHash = osrsLoginRS.get() != null ? osrsLoginRS.get().accountHash : null;
        if (accountHash == null || offerManager == null) {
            return Collections.emptyList();
        }
        List<LocalMissedFlip> live = new ArrayList<>();
        for (int slot = 0; slot < 8; slot++) {
            SavedOffer offer = offerManager.loadOffer(accountHash, slot);
            if (offer == null || offer.getItemId() <= 0) {
                continue;
            }
            GrandExchangeOfferState state = offer.getState();
            boolean buy = state == GrandExchangeOfferState.CANCELLED_BUY;
            if (!buy && state != GrandExchangeOfferState.CANCELLED_SELL) {
                continue;
            }
            int remaining = offer.getTotalQuantity() - offer.getQuantitySold();
            if (remaining <= 0) {
                continue;
            }
            LocalMissedFlip m = new LocalMissedFlip();
            m.setKind(LocalMissedFlip.Kind.CANCELLED);
            m.setItemId(offer.getItemId());
            m.setItemName(itemController.getItemName(offer.getItemId()));
            m.setWhy(buy ? "Cancelled buy (unfilled leftover)" : "Cancelled sell (unsold leftover)");
            m.setTime((int) Instant.now().getEpochSecond());
            m.setQtyLeft(remaining);
            m.setFilledQty(offer.getQuantitySold());
            m.setListedQty(offer.getTotalQuantity());
            m.setListedPrice(offer.getPrice());
            live.add(m);
        }
        return live;
    }

    private LocalMissedFlip fromIncomplete(FlipV2 flip) {
        LocalMissedFlip m = new LocalMissedFlip();
        m.setKind(LocalMissedFlip.Kind.INCOMPLETE);
        m.setItemId(flip.getItemId());
        m.setItemName(flip.getCachedItemName());
        m.setWhy("Unsold remainder");
        m.setTime(flip.getUpdatedTime() > 0 ? flip.getUpdatedTime() : flip.getOpenedTime());
        m.setQtyLeft(flip.getOpenedQuantity() - flip.getClosedQuantity());
        m.setFilledQty(flip.getClosedQuantity());
        m.setListedQty(flip.getOpenedQuantity());
        m.setListedPrice(flip.getAvgBuyPrice());
        m.setSourceFlip(flip);
        return m;
    }

    private LocalMissedFlip fromCancelled(LocalFlipLedger.CancelledLeftover row) {
        LocalMissedFlip m = new LocalMissedFlip();
        m.setKind(LocalMissedFlip.Kind.CANCELLED);
        m.setItemId(row.itemId);
        m.setItemName(itemController.getItemName(row.itemId));
        m.setWhy(row.reason != null ? row.reason : (row.buy ? "Cancelled buy" : "Cancelled sell"));
        m.setTime(row.time);
        m.setQtyLeft(row.remainingQty);
        m.setFilledQty(row.filledQty);
        m.setListedQty(row.listedQty);
        m.setListedPrice(row.listedPrice);
        return m;
    }

    private static String dedupeKey(LocalMissedFlip m) {
        return m.getItemId() + ":" + m.getQtyLeft() + ":" + m.getFilledQty() + ":" + m.getListedPrice() + ":" + m.getWhy();
    }

    private Object[] toRow(LocalMissedFlip row) {
        return new Object[]{
                formatEpochOrNa(row.getTime()),
                row.getItemName(),
                row.getWhy(),
                row.getQtyLeft(),
                row.getListedPrice(),
                row.getFilledQty(),
                row.getListedQty()
        };
    }

    private class Section {
        final JPanel container;
        final PaginatedTablePanel<LocalMissedFlip> tablePanel;
        List<LocalMissedFlip> currentRows = new ArrayList<>();
        String sortColumn = "Time";
        SortDirection sortDirection = SortDirection.DESC;

        Section(String title) {
            tablePanel = new PaginatedTablePanel<>(COLUMN_NAMES, MissedFlipsPanel.this::toRow);
            tablePanel.setTopControlsVisible(false);
            tablePanel.installHeaderSort(
                    () -> sortColumn,
                    () -> sortDirection,
                    (clickedColumn, newDirection) -> {
                        sortColumn = clickedColumn;
                        sortDirection = newDirection;
                        rerender();
                    });

            tablePanel.moneyColumns(GP_FORMAT, true, 4);
            tablePanel.centerColumns(3, 5, 6);
            tablePanel.installPopupHandler(this::showRowMenu);

            JLabel titleLabel = new JLabel(title);
            titleLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            titleLabel.setBorder(new EmptyBorder(10, 8, 10, 8));

            container = new JPanel(new BorderLayout());
            container.setBackground(ColorScheme.DARK_GRAY_COLOR);
            container.add(titleLabel, BorderLayout.NORTH);
            container.add(tablePanel, BorderLayout.CENTER);
        }

        void update(List<LocalMissedFlip> rows) {
            currentRows = new ArrayList<>(rows);
            rerender();
        }

        private void rerender() {
            FilterSortUtil.sort(currentRows, COMPARATORS, sortColumn, sortDirection);
            tablePanel.setRows(new ArrayList<>(currentRows));
        }

        private void showRowMenu(MouseEvent e, int row) {
            LocalMissedFlip missed = tablePanel.row(row);
            if (missed == null || missed.getKind() != LocalMissedFlip.Kind.INCOMPLETE || missed.getSourceFlip() == null) {
                return;
            }
            JPopupMenu menu = new JPopupMenu();
            JMenuItem remove = new JMenuItem("Remove from portfolio");
            remove.addActionListener(evt -> {
                int confirm = JOptionPane.showConfirmDialog(
                        MissedFlipsPanel.this,
                        "Remove this incomplete position from your portfolio? Closed flip history is kept.",
                        "Remove from portfolio",
                        JOptionPane.YES_NO_OPTION);
                if (confirm != JOptionPane.YES_OPTION) {
                    return;
                }
                FlipV2 flip = missed.getSourceFlip();
                String displayName = resolveDisplayName();
                if (displayName == null) {
                    return;
                }
                tablePanel.setSpinnerVisible(true);
                if (cloudSyncService != null) {
                    cloudSyncService.dismissOpenPosition(displayName, flip, ok -> {
                        tablePanel.setSpinnerVisible(false);
                        refresh();
                        if (!Boolean.TRUE.equals(ok)) {
                            JOptionPane.showMessageDialog(
                                    MissedFlipsPanel.this,
                                    "Could not remove this position from the portfolio.",
                                    "Remove failed",
                                    JOptionPane.ERROR_MESSAGE);
                        }
                    });
                } else {
                    localFlipLedger.dismissOpenFlip(displayName, flip.getId());
                    tablePanel.setSpinnerVisible(false);
                    refresh();
                }
            });
            menu.add(remove);
            menu.show(e.getComponent(), e.getX(), e.getY());
        }
    }

    private static final Map<String, Comparator<LocalMissedFlip>> COMPARATORS = new HashMap<>();

    static {
        COMPARATORS.put("Time", Comparator.comparingInt(LocalMissedFlip::getTime).reversed());
        COMPARATORS.put("Item", Comparator.comparing(m -> m.getItemName() != null ? m.getItemName() : ""));
        COMPARATORS.put("Why", Comparator.comparing(m -> m.getWhy() != null ? m.getWhy() : ""));
        COMPARATORS.put("Qty left", Comparator.comparingInt(LocalMissedFlip::getQtyLeft));
        COMPARATORS.put("Listed price", Comparator.comparingLong(LocalMissedFlip::getListedPrice));
        COMPARATORS.put("Filled", Comparator.comparingInt(LocalMissedFlip::getFilledQty));
        COMPARATORS.put("Listed qty", Comparator.comparingInt(LocalMissedFlip::getListedQty));
    }
}
