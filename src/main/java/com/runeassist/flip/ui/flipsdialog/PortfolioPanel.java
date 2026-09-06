package com.runeassist.flip.ui.flipsdialog;

import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.HeldCostTracker;
import com.runeassist.flip.controller.FlipHistorySyncService;
import com.runeassist.flip.controller.ItemController;
import com.runeassist.flip.model.*;
import com.runeassist.flip.rs.*;
import com.runeassist.flip.ui.RuneAssistColors;
import com.runeassist.flip.ui.UIUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.List;

@Slf4j
public class PortfolioPanel extends JPanel {
    private static final NumberFormat GP_FORMAT = NumberFormat.getNumberInstance(Locale.US);
    private static final NumberFormat INT_FORMAT = NumberFormat.getIntegerInstance(Locale.US);
    private static final String CONTENT_CARD = "content";
    private static final String LOGIN_PROMPT_CARD = "login";
    private static final String[] COLUMN_NAMES = {
            "Item", "Market value", "Quantity", "Unrealized Profit", "Unrealized ROI", "Avg buy price", "Time held"
    };

    private static final Map<String, Comparator<PortfolioItemCardData>> SORT_COMPARATORS = new HashMap<>();
    static {
        SORT_COMPARATORS.put("Item", Comparator.comparing(
                PortfolioItemCardData::getItemName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        // Numeric columns pre-reversed so default DESC shows largest-first
        SORT_COMPARATORS.put("Market value", Comparator.<PortfolioItemCardData>comparingLong(
                i -> i.getPostTaxSellUnitPrice() * (long) i.getPortfolioQuantity()).reversed());
        SORT_COMPARATORS.put("Quantity", Comparator.comparingInt(PortfolioItemCardData::getPortfolioQuantity).reversed());
        SORT_COMPARATORS.put("Avg buy price", Comparator.comparingLong(PortfolioItemCardData::getUnitBuyPrice).reversed());
        SORT_COMPARATORS.put("Time held", Comparator.comparingInt(PortfolioItemCardData::getHeldMinutes));
        SORT_COMPARATORS.put("Unrealized Profit", Comparator.comparingLong(PortfolioItemCardData::portfolioUnrealizedProfit).reversed());
        SORT_COMPARATORS.put("Unrealized ROI", Comparator.comparing(
                PortfolioPanel::calculateUnrealizedRoi,
                Comparator.nullsLast(Comparator.<Double>naturalOrder().reversed())));
    }

    private final ItemController itemController;
    private final RuneAssistConfig config;
    private final HeldCostTracker heldCostTracker;
    private final FlipHistorySyncService flipHistorySyncService;
    private final ExecutorService executorService;
    private final SuggestionManager suggestionManager;
    private final OsrsLoginRS osrsLoginRS;
    private final PortfolioStateRS portfolioStateRS;
    private final BankStateRS bankStateRS;
    private final Consumer<Integer> openPriceGraph;
    private final CardLayout cardLayout;
    private final JPanel cardPanel;
    private final JPanel summaryTablePanel;
    private final PaginatedTablePanel<PortfolioItemCardData> tablePanel;
    private final JLabel autoSyncInfoLabel;
    private final JButton clearPortfolioButton;
    private final Map<Integer, ImageIcon> itemIconCache = new ConcurrentHashMap<>();

    private List<PortfolioItemCardData> currentItems = new ArrayList<>();
    private String sortColumn = "Market value";
    private SortDirection sortDirection = SortDirection.DESC;

    public PortfolioPanel(ItemController itemController,
                          RuneAssistConfig config,
                          HeldCostTracker heldCostTracker,
                          FlipHistorySyncService flipHistorySyncService,
                          ExecutorService executorService,
                          SuggestionManager suggestionManager,
                          OsrsLoginRS osrsLoginRs,
                          PortfolioStateRS portfolioStateRS,
                          BankStateRS bankStateRS,
                          Consumer<Integer> openPriceGraph) {
        this.itemController = itemController;
        this.config = config;
        this.heldCostTracker = heldCostTracker;
        this.flipHistorySyncService = flipHistorySyncService;
        this.executorService = executorService;
        this.suggestionManager = suggestionManager;
        this.osrsLoginRS = osrsLoginRs;
        this.portfolioStateRS = portfolioStateRS;
        this.bankStateRS = bankStateRS;
        this.openPriceGraph = openPriceGraph;

        setLayout(new BorderLayout(0, 12));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(new EmptyBorder(10, 10, 10, 10));

        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);
        cardPanel.setOpaque(false);

        JPanel contentPanel = plain(new BorderLayout(0, 12));
        contentPanel.setBorder(new EmptyBorder(8, 0, 0, 0));

        JPanel summarySection = plain(new BorderLayout(28, 0));
        summarySection.setBorder(new EmptyBorder(0, 0, 10, 0));
        summaryTablePanel = plain(new GridLayout(0, 2, 24, 10));
        summaryTablePanel.setBorder(new EmptyBorder(4, 0, 4, 0));
        summarySection.add(summaryTablePanel, BorderLayout.WEST);

        clearPortfolioButton = new JButton("Remove everything from portfolio");
        clearPortfolioButton.setFont(FontManager.getRunescapeFont());
        clearPortfolioButton.setFocusable(false);
        clearPortfolioButton.addActionListener(e -> onClearPortfolioClicked());

        JPanel rightControls = plain(new BorderLayout());
        rightControls.setBorder(new EmptyBorder(0, 12, 0, 0));
        JPanel syncWrap = plain(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        autoSyncInfoLabel = new JLabel();
        autoSyncInfoLabel.setForeground(RuneAssistColors.ACCENT);
        autoSyncInfoLabel.setFont(FontManager.getRunescapeFont());
        autoSyncInfoLabel.setHorizontalAlignment(SwingConstants.LEFT);
        syncWrap.add(autoSyncInfoLabel);
        rightControls.add(syncWrap, BorderLayout.SOUTH);
        summarySection.add(rightControls, BorderLayout.CENTER);
        contentPanel.add(summarySection, BorderLayout.NORTH);

        tablePanel = new PaginatedTablePanel<>(COLUMN_NAMES, this::toRow, 40);
        tablePanel.rightControls().add(clearPortfolioButton);
        tablePanel.installHeaderSort(() -> sortColumn, () -> sortDirection, (col, dir) -> {
            sortColumn = col;
            sortDirection = dir;
            renderTable();
        });
        tablePanel.installPopupHandler((e, row) -> showPortfolioMenu(e, tablePanel.row(row)));
        tablePanel.rightColumns(2, 6);
        tablePanel.gpColumns(GP_FORMAT, false, 1, 5);
        tablePanel.gpProfitColumns(GP_FORMAT, config, 3);
        tablePanel.percentRoiColumns(config, 4);
        tablePanel.setRenderer(itemCellRenderer(), 0);
        contentPanel.add(tablePanel, BorderLayout.CENTER);

        cardPanel.add(contentPanel, CONTENT_CARD);
        cardPanel.add(DialogUi.centeredMessage(
                "Log into the game to see account portfolio", null, false, 18f), LOGIN_PROMPT_CARD);
        add(cardPanel, BorderLayout.CENTER);

        portfolioStateRS.registerListener(state -> SwingUtilities.invokeLater(() -> {
            if (osrsLoginRS.get().loggedIn) {
                renderFromState(state);
            }
        }));
        osrsLoginRS.registerListener(state -> SwingUtilities.invokeLater(this::refresh));
        bankStateRS.registerListener(state -> SwingUtilities.invokeLater(this::refreshAutoSyncLabel));
        refresh();
    }

    public void onTabShown() {
        refresh();
    }

    private void refresh() {
        if (!osrsLoginRS.get().loggedIn) {
            cardLayout.show(cardPanel, LOGIN_PROMPT_CARD);
            clearPortfolioButton.setEnabled(false);
            revalidate();
            repaint();
            return;
        }
        cardLayout.show(cardPanel, CONTENT_CARD);
        clearPortfolioButton.setEnabled(true);
        refreshAutoSyncLabel();
        renderFromState(portfolioStateRS.get());
    }

    private void onClearPortfolioClicked() {
        String displayName = osrsLoginRS.get().displayName;
        if (displayName == null) {
            return;
        }
        if (JOptionPane.showConfirmDialog(this,
                "Are you sure you want to remove all items from your portfolio?",
                "Confirm", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        clearPortfolioButton.setEnabled(false);
        executorService.execute(() -> {
            mutatePortfolio(
                    () -> flipHistorySyncService.clearPortfolio(displayName),
                    () -> heldCostTracker.clearLots(displayName),
                    "cleared portfolio via server for " + displayName,
                    "cleared {} tracked units from local portfolio");
            SwingUtilities.invokeLater(() -> clearPortfolioButton.setEnabled(true));
        });
    }

    private void refreshAutoSyncLabel() {
        String text = bankStateRS.get().isLoaded()
                ? "Bank loaded. Full quantity syncing enabled. Note: items are excluded from syncing whilst active in one of your Grand Exchange slots."
                : "Please open your bank once to enable more accurate quantity syncing.";
        autoSyncInfoLabel.setText(
                "<html><div style='width: 560px; text-align: left;'>" + text + "</div></html>");
    }

    private void renderFromState(PortfolioState state) {
        currentItems = new ArrayList<>();
        for (PortfolioItemCardData item : state.getItemCardDataByItemId().values()) {
            if (item.isInPortfolio()) {
                currentItems.add(item);
            }
        }
        renderSummary(state.getSummaryData(), currentItems.size());
        renderTable();
        revalidate();
        repaint();
    }

    private void renderSummary(PortfolioSummaryData data, int totalItems) {
        summaryTablePanel.removeAll();
        if (data == null) {
            return;
        }
        addSummaryRow("Portfolio Market Value", formatGp(data.getPortfolioMarketValue(), false), config.profitAmountColor());
        addSummaryRow("Unrealized Profit", formatGp(data.getUnrealizedProfit(), true),
                UIUtilities.getProfitColor(data.getUnrealizedProfit(), config));
        addSummaryRow("Cash Value", formatGp(data.getCashValue(), false));
        addSummaryRow("Cash in Buy Offers", formatGp(data.getLockedBuyCash(), false));
        addSummaryRow("Assets Value", formatGp(data.getAssetsValue(), false));
        addSummaryRow("Unique Items in Portfolio", INT_FORMAT.format(totalItems));
    }

    private void addSummaryRow(String label, String value) {
        addSummaryRow(label, value, ColorScheme.LIGHT_GRAY_COLOR);
    }

    private void addSummaryRow(String label, String value, Color valueColor) {
        JLabel key = new JLabel(label);
        key.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        key.setFont(FontManager.getRunescapeFont());
        JLabel val = new JLabel(value, SwingConstants.RIGHT);
        val.setForeground(valueColor);
        val.setFont(FontManager.getRunescapeBoldFont());
        summaryTablePanel.add(key);
        summaryTablePanel.add(val);
    }

    private void renderTable() {
        List<PortfolioItemCardData> sorted = new ArrayList<>(currentItems);
        FilterSortUtil.sort(sorted, SORT_COMPARATORS, sortColumn, sortDirection);
        tablePanel.setRows(sorted);
    }

    private Object[] toRow(PortfolioItemCardData item) {
        long avgBuy = item.getUnitBuyPrice();
        return new Object[]{
                new ItemCell(item.getItemId(), item.getItemName()),
                item.getPostTaxSellUnitPrice() * item.getPortfolioQuantity(),
                INT_FORMAT.format(item.getPortfolioQuantity()),
                item.portfolioUnrealizedProfit(),
                calculateUnrealizedRoi(item),
                avgBuy > 0 ? avgBuy : null,
                UIUtilities.formatDurationMinutes(item.getHeldMinutes())
        };
    }

    private void showPortfolioMenu(MouseEvent e, PortfolioItemCardData item) {
        JPopupMenu menu = new JPopupMenu();
        addMenu(menu, "Show price graph", () -> openPriceGraph.accept(item.getItemId()));
        int qty = item.getPortfolioQuantity();
        if (qty > 0) {
            addMenu(menu, "Remove from portfolio", () -> removeFromPortfolio(item.getItemId(), 0));
        }
        if (qty > 1) {
            addMenu(menu, "Remove X from portfolio", () -> promptRemoveQuantity(item.getItemId()));
        }
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private void promptRemoveQuantity(int itemId) {
        String input = JOptionPane.showInputDialog(
                this, "Quantity to remove:", "Remove from portfolio", JOptionPane.PLAIN_MESSAGE);
        if (input == null) {
            return;
        }
        try {
            int qty = Integer.parseInt(input.trim());
            if (qty > 0) {
                removeFromPortfolio(itemId, qty);
            }
        } catch (NumberFormatException ignored) {
        }
    }

    /** quantity <= 0 removes all tracked lots for the item. */
    private void removeFromPortfolio(int itemId, int quantity) {
        String displayName = osrsLoginRS.get().displayName;
        if (displayName == null) {
            return;
        }
        executorService.execute(() -> mutatePortfolio(
                () -> flipHistorySyncService.toggleItemPortfolio(displayName, itemId, quantity, 0L, true),
                () -> heldCostTracker.removeLots(displayName, itemId, quantity),
                "removed item " + itemId + " qty " + quantity + " from server portfolio",
                "removed {} x item " + itemId + " from local portfolio"));
    }

    private void mutatePortfolio(BooleanSupplier serverOp, IntSupplier localOp, String serverLog, String localLog) {
        if (flipHistorySyncService != null && flipHistorySyncService.isLinked() && serverOp.getAsBoolean()) {
            log.info(serverLog);
            return;
        }
        log.info(localLog, localOp.getAsInt());
        suggestionManager.setSuggestionNeeded(true);
    }

    private static void addMenu(JPopupMenu menu, String label, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> action.run());
        menu.add(item);
    }

    private DefaultTableCellRenderer itemCellRenderer() {
        return new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);
                if (!(value instanceof ItemCell)) {
                    label.setIcon(null);
                    return label;
                }
                ItemCell cell = (ItemCell) value;
                label.setText(cell.name);
                ImageIcon cached = itemIconCache.get(cell.itemId);
                if (cached != null) {
                    label.setIcon(cached);
                    return label;
                }
                label.setIcon(null);
                itemController.loadImage(cell.itemId, image -> {
                    if (image != null) {
                        itemIconCache.put(cell.itemId, new ImageIcon(image));
                        SwingUtilities.invokeLater(table::repaint);
                    }
                });
                return label;
            }
        };
    }

    private String formatGp(long amount, boolean signed) {
        return (signed && amount > 0 ? "+" : "") + GP_FORMAT.format(amount) + " gp";
    }

    private static Double calculateUnrealizedRoi(PortfolioItemCardData item) {
        if (item.getUnrealizedUnitProfit() == null) {
            return null;
        }
        long unitBuyPrice = item.getPostTaxSellUnitPrice() - item.getUnrealizedUnitProfit();
        return unitBuyPrice <= 0 ? null
                : (double) item.getUnrealizedUnitProfit() / (double) unitBuyPrice;
    }

    private static JPanel plain(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    private static final class ItemCell {
        final int itemId;
        final String name;

        ItemCell(int itemId, String name) {
            this.itemId = itemId;
            this.name = name;
        }
    }
}
