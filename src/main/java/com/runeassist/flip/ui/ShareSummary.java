package com.runeassist.flip.ui;

import com.runeassist.flip.model.FlipV2;
import com.runeassist.flip.model.Stats;

/** One line a player can paste into Discord #wins. */
public final class ShareSummary {
    private ShareSummary() {
    }

    public static String format(String intervalLabel, Stats stats, FlipV2 best) {
        String label = intervalLabel == null || intervalLabel.isEmpty() ? "Session" : intervalLabel;
        int flips = stats == null ? 0 : stats.flipsMade;
        long profit = stats == null ? 0 : stats.profit;
        StringBuilder sb = new StringBuilder("RuneAssist \u00b7 ").append(label).append(": ")
                .append(flips).append(flips == 1 ? " flip, " : " flips, ")
                .append(signed(profit)).append(" gp");
        if (best != null && best.getProfit() != 0) {
            String name = best.getCachedItemName() != null ? best.getCachedItemName() : ("item " + best.getItemId());
            sb.append(" \u00b7 best: ").append(name).append(' ').append(signed(best.getProfit()));
        }
        return sb.toString();
    }

    private static String signed(long gp) {
        String text = UIUtilities.formatProfit(Math.abs(gp));
        return (gp < 0 ? "-" : "+") + text;
    }
}
