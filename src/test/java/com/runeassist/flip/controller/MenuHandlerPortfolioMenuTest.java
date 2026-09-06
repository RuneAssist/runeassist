package com.runeassist.flip.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FC-parity inventory portfolio menu gating (Add/Remove + X qty prompts). */
public class MenuHandlerPortfolioMenuTest {

    @Test
    public void unknownItemNearGeShowsAddOnly() {
        PortfolioMenuEntries.MenuVisibility v = PortfolioMenuEntries.menuVisibility(10, 0, 0, true);
        assertTrue(v.showAdd);
        assertFalse(v.showAddX);
        assertFalse(v.showRemove);
        assertFalse(v.showRemoveX);
    }

    @Test
    public void partialPortfolioShowsAddAndRemoveWithX() {
        PortfolioMenuEntries.MenuVisibility v = PortfolioMenuEntries.menuVisibility(5, 8, 4, false);
        assertTrue(v.showAdd);
        assertTrue(v.showAddX);
        assertTrue(v.showRemove);
        assertTrue(v.showRemoveX);
    }

    @Test
    public void fullyTrackedShowsRemoveOnly() {
        PortfolioMenuEntries.MenuVisibility v = PortfolioMenuEntries.menuVisibility(3, 3, 0, false);
        assertFalse(v.showAdd);
        assertFalse(v.showAddX);
        assertTrue(v.showRemove);
        assertTrue(v.showRemoveX);
    }

    @Test
    public void singleTrackedUnitOmitsRemoveX() {
        PortfolioMenuEntries.MenuVisibility v = PortfolioMenuEntries.menuVisibility(1, 1, 0, false);
        assertTrue(v.showRemove);
        assertFalse(v.showRemoveX);
        assertFalse(v.showAdd);
    }

    @Test
    public void emptyLocationOmitsLocationScopedButKeepsXWhenEligible() {
        // FC gates Add/Remove-All on locationQty; Add-X / Remove-X use portfolio totals.
        PortfolioMenuEntries.MenuVisibility v = PortfolioMenuEntries.menuVisibility(0, 5, 2, false);
        assertFalse(v.showAdd);
        assertFalse(v.showRemove);
        assertTrue(v.showAddX);
        assertTrue(v.showRemoveX);
    }
}
