package com.runeassist.flip.ui;

import java.util.Locale;
import java.awt.Font;
import javax.swing.JLabel;

/** Presentation only: never changes eligibility, telemetry or trading decisions. */
final class SuggestionCardText {
    private SuggestionCardText() { }

    static void styleText(JLabel label, boolean heading) {
        label.setFont(label.getFont().deriveFont(heading ? Font.BOLD : Font.PLAIN,
                heading ? 16f : 14f));
    }

    static String waitStatus(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (text.contains("market data") && text.contains("unavailable")) return "Waiting for fresh prices";
        if (text.contains("unreachable") || text.contains("unavailable") || text.contains("offline")) {
            return "Connection unavailable";
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

    static String details(String message, String why) {
        String first = message == null ? "" : message.trim();
        String second = why == null ? "" : why.trim();
        if (first.isEmpty()) return escape(second);
        if (second.isEmpty() || first.equals(second)) return escape(first);
        return escape(first) + "<br>" + escape(second);
    }
}
