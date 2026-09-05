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
import java.util.function.Consumer;
import java.util.List;

@Slf4j
public class PortfolioPanel extends JPanel {
    private static final NumberFormat GP_FORMAT = NumberFormat.getNumberInstance(Locale.US);
    private static final String CONTENT_CARD = "content";
    private static final String LOGIN_PROMPT_CARD = "login";
    private static final String[] COLUMN_NAMES = {
            "Item", "Market value", "Quantity", "Unrealized Profit", "Unrealized ROI", "Avg buy price", "Time held"
    };

    private static final Map<String, Comparator<PortfolioItemCardData>> SORT_COMPARATORS = new HashMap<>();
    static {
        SORT_COMPARATORS.put("Item", Comparator.comparing(
                PortfolioItemCardData::getItemName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        // Numeric columns are pre-reversed so the default DESC direction shows largest-first
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

        JPanel contentPanel = new JPanel(new BorderLayout(0, 12));
        contentPanel.setOpaque(false);
        contentPanel.setBorder(new EmptyBorder(8, 0, 0, 0));

        JPanel summarySection = new JPanel(new BorderLayout(28, 0));
        summarySection.setOpaque(false);
        summarySection.setBorder(new EmptyBorder(0, 0, 10, 0));

        summaryTablePanel = new JPanel(new GridLayout(0, 2, 24, 10));
        summaryTablePanel.setOpaque(false);
        summaryTablePanel.setBorder(new EmptyBorder(4, 0, 4, 0));
        summarySection.add(summaryTablePanel, BorderLayout.WEST);

        clearPortfolioButton = new JButton("Remove everything from portfolio");
        clearPortfolioButton.setFont(FontManager.getRunescapeFont());
        clearPortfolioButton.setFocusable(false);
        clearPortfolioButton.addActionListener(e -> onClearPortfolioClicked());

        JPanel rightControlsPanel = new JPanel();
        rightControlsPanel.setOpaque(false);
        rightControlsPanel.setLayout(new BorderLayout());
        rightControlsPanel.setBorder(new EmptyBorder(0, 12, 0, 0));

        JPanel bottomRightWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        bottomRightWrap.setOpaque(false);
        autoSyncInfoLabel = new JLabel();
        autoSyncInfoLabel.setForeground(RuneAssistColors.ACCENT);
        autoSyncInfoLabel.setFont(FontManager.getRunescapeFont());
        autoSyncInfoLabel.setHorizontalAlignment(SwingConstants.LEFT);
        bottomRightWrap.add(autoSyncInfoLabel);

        rightControlsPanel.add(bottomRightWrap, BorderLayout.SOUTH);
        summarySection.add(rightControlsPanel, BorderLayout.CENTER);

        contentPanel.add(summarySection, BorderLayout.NORTH);

        tablePanel = new PaginatedTablePanel<>(COLUMN_NAMES, this::toRow, 40);
        tablePanel.rightControls().add(clearPortfolioButton);
        tablePanel.installHeaderSort(() -> sortColumn, () -> sortDirection, (clickedColumn, newDirection) -> {
            sortColumn = clickedColumn;
            sortDirection = newDirection;
            renderTable();
        });
        tablePanel.installPopupHandler((e, row) -> showPortfolioMenu(e, tablePanel.row(row)));
        tablePanel.rightColumns(2, 6); // Quantity, Time held
        tablePanel.setRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (value instanceof Long) {
                    setText(formatGp((Long) value, false));
                } else if (value == null) {
                    setText("Unknown");
                }
                setHorizontalAlignment(RIGHT);
                return c;
            }
        }, 1, 5);
        tablePanel.setRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (value instanceof ItemCell) {
                    ItemCell itemCell = (ItemCell) value;
                    label.setText(itemCell.name);
                    ImageIcon cachedIcon = itemIconCache.get(itemCell.itemId);
                    if (cachedIcon != null) {
                        label.setIcon(cachedIcon);
                    } else {
                        label.setIcon(null);
                        itemController.loadImage(itemCell.itemId, image -> {
                            if (image != null) {
                                itemIconCache.put(itemCell.itemId, new ImageIcon(image));
                                SwingUtilities.invokeLater(table::repaint);
                            }
                        });
                    }
                } else {
                    label.setIcon(null);
                }
                return label;
            }
        }, 0);

        DefaultTableCellRenderer profitRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (value instanceof Long) {
                    long amount = (Long) value;
                    setText(formatGp(amount, true));
                    setHorizontalAlignment(RIGHT);
                    if (!isSelected) {
                        setForeground(UIUtilities.getProfitColor(amount, config));
                    }
                }
                return c;
            }
        };
        tablePanel.setRenderer(profitRenderer, 3);

        DefaultTableCellRenderer roiRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setHorizontalAlignment(RIGHT);
                if (value instanceof Double) {
                    double roi = (Double) value;
                    setText(String.format("%.2f%%", roi * 100.0d));
                    if (!isSelected) {
                        setForeground(UIUtilities.getProfitColor(roi, config));
                    }
                } else {
                    setText(value == null ? "Unknown" : value.toString());
                }
                return c;
            }
        };
        tablePanel.setRenderer(roiRenderer, 4);
        contentPanel.add(tablePanel, BorderLayout.CENTER);

        cardPanel.add(contentPanel, CONTENT_CARD);
        cardPanel.add(DialogUi.centeredMessage("Log into the game to see account portfolio", null, false, 18f), LOGIN_PROMPT_CARD);
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
        // Gated on OSRS login; removal is local.
        clearPortfolioButton.setEnabled(true);
        refreshAutoSyncLabel();
        renderFromState(portfolioStateRS.get());
    }

    private void onClearPortfolioClicked() {
        String displayName = osrsLoginRS.get().displayName;
        if (displayName == null) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to remove all items from your portfolio?",
                "Confirm",
                JOptionPane.YES_NO_OPTION);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        clearPortfolioButton.setEnabled(false);
        executorService.execute(() -> {
            if (flipHistorySyncService != null && flipHistorySyncService.isLinked()
                    && flipHistorySyncService.clearPortfolio(displayName)) {
                log.info("cleared portfolio via server for {}", displayName);
            } else {
                int removed = heldCostTracker.clearLots(displayName);
                suggestionManager.setSuggestionNeeded(true);
                log.info("cleared {} tracked units from local portfolio", removed);
            }
            SwingUtilities.invokeLater(() -> clearPortfolioButton.setEnabled(true));
        });
    }

    private void refreshAutoSyncLabel() {
        String labelText = bankStateRS.get().isLoaded()
                ? "Bank loaded. Full quantity syncing enabled. Note: items are excluded from syncing whilst active in one of your Grand Exchange slots."
                : "Please open your bank once to enable more accurate quantity syncing.";
        autoSyncInfoLabel.setText(String.format("<html><div style='width: 560px; text-align: left;'>%s</div></html>", labelText));
    }

    private List<PortfolioItemCardData> filterInPortfolioItems(List<PortfolioItemCardData> items) {
        List<PortfolioItemCardData> filteredItems = new ArrayList<>();
        for (PortfolioItemCardData item : items) {
            if (item.isInPortfolio()) {
                filteredItems.add(item);
            }
        }
        return filteredItems;
    }

    private void renderFromState(PortfolioState state) {
        List<PortfolioItemCardData> items = new ArrayList<>(state.getItemCardDataByItemId().values());
        currentItems = filterInPortfolioItems(items);
        PortfolioSummaryData sm = state.getSummaryData();
        renderSummary(sm, currentItems.size());
        renderTable();
        revalidate();
        repaint();
    }

    private void renderSummary(PortfolioSummaryData data, int totalItemsInPortfolio) {
        summaryTablePanel.removeAll();
        if (data == null) {
            return;
        }
        addSummaryRow("Portfolio Market Value", formatGp(data.getPortfolioMarketValue(), false), config.profitAmountColor());
        addSummaryRow("Unrealized Profit", formatGp(data.getUnrealizedProfit(), true), UIUtilities.getProfitColor(data.getUnrealizedProfit(), config));
        addSummaryRow("Cash Value", formatGp(data.getCashValue(), false));
        addSummaryRow("Cash in Buy Offers", formatGp(data.getLockedBuyCash(), false));
        addSummaryRow("Assets Value", formatGp(data.getAssetsValue(), false));
        addSummaryRow("Unique Items in Portfolio", NumberFormat.getIntegerInstance(Locale.US).format(totalItemsInPortfolio));
    }

    private void addSummaryRow(String label, String value) {
        addSummaryRow(label, value, ColorScheme.LIGHT_GRAY_COLOR);
    }

    private void addSummaryRow(String label, String value, Color valueColor) {
        JLabel keyLabel = new JLabel(label);
        keyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        keyLabel.setFont(FontManager.getRunescapeFont());

        JLabel valueLabel = new JLabel(value, SwingConstants.RIGHT);
        valueLabel.setForeground(valueColor);
        valueLabel.setFont(FontManager.getRunescapeBoldFont());

        summaryTablePanel.add(keyLabel);
        summaryTablePanel.add(valueLabel);
    }

    private void renderTable() {
        List<PortfolioItemCardData> sortedItems = new ArrayList<>(currentItems);
        FilterSortUtil.sort(sortedItems, SORT_COMPARATORS, sortColumn, sortDirection);
        tablePanel.setRows(sortedItems);
    }

    private Object[] toRow(PortfolioItemCardData item) {
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        long avgBuyPrice = item.getUnitBuyPrice();
        return new Object[]{
                new ItemCell(item.getItemId(), item.getItemName()),
                item.getPostTaxSellUnitPrice() * item.getPortfolioQuantity(),
                nf.format(item.getPortfolioQuantity()),
                item.portfolioUnrealizedProfit(),
                calculateUnrealizedRoi(item),
                avgBuyPrice > 0 ? avgBuyPrice : null,
                UIUtilities.formatDurationMinutes(item.getHeldMinutes())
        };
    }

    private void showPortfolioMenu(MouseEvent e, PortfolioItemCardData item) {
        JPopupMenu popupMenu = new JPopupMenu();
        buildContextMenu(popupMenu, item);
        popupMenu.show(e.getComponent(), e.getX(), e.getY());
    }

    private void buildContextMenu(JPopupMenu menu, PortfolioItemCardData item) {
        int portfolioQty = item.getPortfolioQuantity();

        JMenuItem showGraph = new JMenuItem("Show price graph");
        showGraph.addActionListener(e -> openPriceGraph.accept(item.getItemId()));
        menu.add(showGraph);

        if (portfolioQty > 0) {
            JMenuItem removeAll = new JMenuItem("Remove from portfolio");
            removeAll.addActionListener(e -> removeFromPortfolio(item.getItemId(), 0));
            menu.add(removeAll);
        }

        if (portfolioQty > 1) {
            JMenuItem removeX = new JMenuItem("Remove X from portfolio");
            removeX.addActionListener(e -> {
                String input = JOptionPane.showInputDialog(this, "Quantity to remove:", "Remove from portfolio", JOptionPane.PLAIN_MESSAGE);
                if (input != null) {
                    try {
                        int qty = Integer.parseInt(input.trim());
                        if (qty > 0) {
                            removeFromPortfolio(item.getItemId(), qty);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            });
            menu.add(removeX);
        }
    }

    /**
     * Forget the tracked cost basis for held stock. When linked, posts to the
     * server toggle-item-portfolio endpoint and refreshes from delta; otherwise
     * falls back to local {@link HeldCostTracker} only.
     *
     * @param quantity units to remove, oldest lot first; {@code <= 0} removes all of the item
     */
    private void removeFromPortfolio(int itemId, int quantity) {
        String displayName = osrsLoginRS.get().displayName;
        if (displayName == null) {
            return;
        }
        executorService.execute(() -> {
            if (flipHistorySyncService != null && flipHistorySyncService.isLinked()
                    && flipHistorySyncService.toggleItemPortfolio(displayName, itemId, quantity, 0L, true)) {
                log.info("removed item {} qty {} from server portfolio", itemId, quantity);
            } else {
                int removed = heldCostTracker.removeLots(displayName, itemId, quantity);
                suggestionManager.setSuggestionNeeded(true);
                log.info("removed {} x item {} from local portfolio", removed, itemId);
            }
        });
    }

    private String formatGp(long amount, boolean signed) {
        String prefix = signed && amount > 0 ? "+" : "";
        return prefix + GP_FORMAT.format(amount) + " gp";
    }

    private static Double calculateUnrealizedRoi(PortfolioItemCardData item) {
        if (item.getUnrealizedUnitProfit() == null) {
            return null;
        }
        long unitBuyPrice = item.getPostTaxSellUnitPrice() - item.getUnrealizedUnitProfit();
        if (unitBuyPrice <= 0) {
            return null;
        }
        return (double) item.getUnrealizedUnitProfit() / (double) unitBuyPrice;
    }

    private static class ItemCell {
        private final int itemId;
        private final String name;

        private ItemCell(int itemId, String name) {
            this.itemId = itemId;
            this.name = name;
        }
    }

}
