package com.runeassist.flip.ui.flipsdialog;

import com.runeassist.flip.model.SortDirection;

import java.util.*;

final class FilterSortUtil {
    private FilterSortUtil() {
    }

    static <T> void sort(List<T> rows,
                         Map<String, Comparator<T>> comparators,
                         String sortColumn,
                         SortDirection sortDirection) {
        Comparator<T> comparator = comparators.get(sortColumn);
        if (comparator == null) {
            return;
        }
        if (sortDirection == SortDirection.ASC) {
            comparator = comparator.reversed();
        }

        // Apply sorting
        rows.sort(comparator);
    }
}
