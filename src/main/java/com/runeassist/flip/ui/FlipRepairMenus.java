package com.runeassist.flip.ui;

import com.runeassist.flip.controller.FlipHistorySyncService;
import com.runeassist.flip.model.FlipStatus;
import com.runeassist.flip.model.FlipV2;
import com.runeassist.flip.model.PortfolioId;
import com.runeassist.flip.util.ProfitCalculator;

import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import java.awt.Component;

/** Shared right-click repair actions for Recent Flips / missed flips. */
public final class FlipRepairMenus {

    private FlipRepairMenus() {}

    public static void addStandardActions(
            JPopupMenu menu,
            Component parent,
            FlipV2 flip,
            String displayName,
            FlipHistorySyncService sync,
            boolean includeMissedSale,
            boolean includeRevive) {
        if (menu == null || flip == null || displayName == null || sync == null || !sync.isLinked()) {
            return;
        }
        if (includeMissedSale && canMissedSale(flip)) {
            JMenuItem missed = new JMenuItem("Add missed sell");
            missed.addActionListener(e -> promptMissedSale(parent, flip, displayName, sync));
            menu.add(missed);
        }
        if (includeRevive && PortfolioId.isMissed(flip.getPortfolioId())) {
            JMenuItem revive = new JMenuItem("Revive flip");
            revive.addActionListener(e -> {
                if (confirm(parent, "Revive this flip into your portfolio?")) {
                    sync.reviveFlip(displayName, flip.getId());
                }
            });
            menu.add(revive);
        }
        if (PortfolioId.isInPortfolio(flip.getPortfolioId()) && !flip.isDeleted()) {
            JMenuItem orphan = new JMenuItem("Orphan flip");
            orphan.addActionListener(e -> {
                if (confirm(parent, "Move this flip to the missed/ghost bucket? (Does not delete GE history.)")) {
                    sync.orphanFlip(displayName, flip.getId());
                }
            });
            menu.add(orphan);
        }
        JMenuItem delete = new JMenuItem("Delete flip");
        delete.addActionListener(e -> {
            if (confirm(parent, "Delete this flip from history?")) {
                sync.deleteFlip(displayName, flip.getId());
            }
        });
        menu.add(delete);
    }

    public static boolean canMissedSale(FlipV2 flip) {
        if (flip == null || FlipStatus.FINISHED.equals(flip.getStatus())) {
            return false;
        }
        return flip.getOpenedQuantity() - flip.getClosedQuantity() > 0;
    }

    private static void promptMissedSale(Component parent, FlipV2 flip, String displayName, FlipHistorySyncService sync) {
        int qty = flip.getOpenedQuantity() - flip.getClosedQuantity();
        long suggested = Math.max(1L, (long) (flip.getAvgBuyPrice() * 1.02));
        JTextField priceField = new JTextField(String.valueOf(suggested), 10);
        Object[] form = {
                "Item: " + (flip.getCachedItemName() != null ? flip.getCachedItemName() : flip.getItemId()),
                "Quantity: " + qty,
                "Sell price each:",
                priceField
        };
        int result = JOptionPane.showConfirmDialog(
                parent, form, "Add missed sell", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        long price;
        try {
            price = Long.parseLong(priceField.getText().trim());
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(parent, "Enter a valid price.", "Invalid price", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (price <= 0) {
            JOptionPane.showMessageDialog(parent, "Price must be positive.", "Invalid price", JOptionPane.ERROR_MESSAGE);
            return;
        }
        long postTax = ProfitCalculator.getPostTaxPrice(flip.getItemId(), price);
        long est = (long) qty * (postTax - flip.getAvgBuyPrice());
        if (!confirm(parent, "Record missed sell of " + qty + " @ " + price + " gp?\nEst. profit: "
                + UIUtilities.formatProfit(est))) {
            return;
        }
        sync.addMissedSale(displayName, flip.getId(), qty, price);
    }

    private static boolean confirm(Component parent, String message) {
        return JOptionPane.showConfirmDialog(
                parent, message, "Confirm", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }
}
