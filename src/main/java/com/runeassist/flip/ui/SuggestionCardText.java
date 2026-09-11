package com.runeassist.flip.ui;

import java.util.Locale;
import java.awt.Font;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.BorderFactory;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.GridBagLayout;
import java.text.NumberFormat;
import net.runelite.client.ui.FontManager;
import com.runeassist.flip.model.Suggestion;

/** Presentation only: never changes eligibility, telemetry or trading decisions. */
final class SuggestionCardText {
    private SuggestionCardText() { }

    static void styleText(JLabel label, boolean heading) {
        label.setFont(FontManager.getRunescapeFont().deriveFont(heading ? Font.BOLD : Font.PLAIN,
                16f));
    }

    static String instruction(String action, String name, int quantity, long price, boolean priced) {
        NumberFormat numbers = NumberFormat.getIntegerInstance();
        String count = quantity > 0 ? " <font color='#FFFF00'>" + numbers.format(quantity) + "</font>" : "";
        String quote = priced ? "<br>for <font color='#FFFF00'>" + numbers.format(price) + "</font> gp" : "";
        return "<html><body width='104'><center>" + escape(action) + count + "<br>"
                + escape(name) + quote + "</center></body></html>";
    }

    /** Shared production layout also rendered by the visual regression harness. */
    static JPanel tradeBody(JLabel icon, JLabel instruction, JLabel profit) {
        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        JPanel centered = new JPanel(new GridBagLayout());
        centered.setOpaque(false);
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        icon.setBorder(BorderFactory.createEmptyBorder());
        row.add(icon, BorderLayout.WEST);
        instruction.setHorizontalAlignment(SwingConstants.CENTER);
        instruction.setForeground(RuneAssistColors.TEXT);
        row.add(instruction, BorderLayout.CENTER);
        centered.add(row);
        body.add(centered, BorderLayout.CENTER);
        profit.setHorizontalAlignment(SwingConstants.CENTER);
        profit.setBorder(BorderFactory.createEmptyBorder(5, 0, 4, 0));
        body.add(profit, BorderLayout.SOUTH);
        return body;
    }

    static String waitStatus(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (text.contains("market data") && text.contains("unavailable")) return "Waiting for fresh prices";
        if (text.contains("unreachable") || text.contains("unavailable") || text.contains("offline")) {
            return "Reconnecting…";
        }
        if (text.contains("slots are full") || text.contains("slots full")) return "Waiting for offers to fill";
        if (text.contains("not enough coins")) return "More coins needed";
        if (text.contains("buy limit") || text.contains("buy-limit")) return "Waiting for buy limits";
        if (text.contains("paused")) return "Suggestions paused";
        if (text.contains("blocked every") || text.contains("skipped every")) return "No unblocked flips";
        return "Waiting for a suitable flip";
    }

    static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String profitBasis(Suggestion suggestion) {
        if (suggestion == null) return "";
        if (suggestion.isSellSuggestion()) {
            return "Potential profit if the full quantity sells at this price, after tax. Not guaranteed.";
        }
        if (suggestion.isBuySuggestion()) {
            if (suggestion.getProfitEstimateBasis() == null
                    || "heuristic_net".equals(suggestion.getProfitEstimateBasis())) {
                return "Estimate from quotes, tax and a slippage allowance; not a calibrated expected return."
                        + " Partial fills and repricing can reduce profit.";
            }
            return "Estimated profit, not a guarantee. Partial fills and repricing can reduce profit.";
        }
        return "";
    }

    static String details(String message, String why) {
        String first = message == null ? "" : message.trim();
        String second = why == null ? "" : why.trim();
        if (first.isEmpty()) return escape(second);
        if (second.isEmpty() || first.equals(second)) return escape(first);
        return escape(first) + "<br>" + escape(second);
    }
}
