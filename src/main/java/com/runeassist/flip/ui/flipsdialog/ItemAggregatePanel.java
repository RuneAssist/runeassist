package com.runeassist.flip.ui.flipsdialog;

import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.controller.ItemController;
import com.runeassist.flip.model.FlipManager;
import com.runeassist.flip.model.ItemAggregate;
import com.runeassist.flip.rs.AccountLoginRS;
import com.runeassist.flip.ui.Paginator;
import com.runeassist.flip.ui.components.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;

import static com.runeassist.flip.ui.UIUtilities.addHorizontalGap;

import javax.inject.Named;
import javax.swing.*;
import java.awt.*;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

@Slf4j
public class ItemAggregatePanel extends JPanel {

    private static final NumberFormat GP_FORMAT = NumberFormat.getNumberInstance(Locale.US);
    private static final String[] COLUMN_NAMES = {
            "Item", "Number of flips", "Total quantity flipped", "Biggest loss", "Biggest win",
            "Total profit", "Avg profit", "Avg profit ea."
    };

    // ui components
    private final AccountDropdown accountDropdown;
    private final PaginatedTablePanel<ItemAggregate> tablePanel;

    // state
    private ItemAggregateFilterSort sortAndFilter;

    public ItemAggregatePanel(FlipManager flipsManager,
                              ItemController itemController,
                              AccountLoginRS accountLoginRS,
                              @Named("runeAssistExecutor") ExecutorService executorService,
                              RuneAssistConfig config) {
        setFocusable(true);
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Initialize pagination first (before loadAggregates is called)
        Paginator paginatorPanel = new Paginator((i) -> sortAndFilter.setPage(i));
        tablePanel = new PaginatedTablePanel<>(COLUMN_NAMES, this::toRow);
        sortAndFilter = new ItemAggregateFilterSort(flipsManager, itemController, tablePanel::setRows,
                paginatorPanel::setTotalPages, tablePanel::setSpinnerVisible, executorService);

        ItemSearchMultiSelect searchField = ItemSearchMultiSelect.itemsFilter(this, itemController,
                sortAndFilter::getFilteredItems, sortAndFilter::setFilteredItems);

        IntervalDropdown timeIntervalDropdown = DialogUi.intervalDropdown(sortAndFilter::setInterval);

        accountDropdown = DialogUi.accountDropdown(() -> accountLoginRS.get().displayNameToAccountId, sortAndFilter::setAccountId);
        accountDropdown.refresh();

        tablePanel.leftControls().add(searchField);
        addHorizontalGap(tablePanel.leftControls(), 3);
        tablePanel.leftControls().add(timeIntervalDropdown);
        addHorizontalGap(tablePanel.leftControls(), 3);
        tablePanel.leftControls().add(accountDropdown);

        tablePanel.installHeaderSort(sortAndFilter::getSortColumn, sortAndFilter::getSortDirection, (column, direction) -> {
            sortAndFilter.setSortColumn(column);
            sortAndFilter.setSortDirection(direction);
        });

        // Apply renderers
        tablePanel.centerColumns(1);
        tablePanel.moneyColumns(GP_FORMAT, 2, 3);
        tablePanel.profitColumns(GP_FORMAT, config, 4, 5, 6, 7);

        tablePanel.installPageFooter(paginatorPanel, sortAndFilter.getPageSize(), sortAndFilter::setPageSize);

        add(tablePanel, BorderLayout.CENTER);
    }

    private Object[] toRow(ItemAggregate aggregate) {
        return new Object[]{
                aggregate.getItemName(),
                aggregate.getNumberOfFlips(),
                aggregate.getTotalQuantityFlipped(),
                aggregate.getBiggestLoss(),
                aggregate.getBiggestWin(),
                aggregate.getTotalProfit(),
                aggregate.getAvgProfit(),
                aggregate.getAvgProfitEa()
        };
    }

    public void onTabShown() {
        sortAndFilter.reloadAggregates(true);
        accountDropdown.refresh();
    }
}
