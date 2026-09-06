package com.runeassist.flip.ui;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses ControlPanel custom timeframe text (90m, 1h 30m, 1:30). */
final class CustomTimeParse {
    private static final Pattern TOKEN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(h|hr|hrs|hour|hours|m|min|mins|minute|minutes)?",
            Pattern.CASE_INSENSITIVE);

    private CustomTimeParse() {
    }

    static Integer minutes(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (normalized.contains(":")) {
            return colonMinutes(normalized);
        }
        Integer tokenMinutes = tokenMinutes(normalized);
        if (tokenMinutes != null) {
            return tokenMinutes;
        }
        try {
            int minutes = Integer.parseInt(normalized);
            return minutes > 0 ? minutes : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer colonMinutes(String normalized) {
        String[] parts = normalized.split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            int hours = Integer.parseInt(parts[0].trim());
            int minutes = Integer.parseInt(parts[1].trim());
            if (hours < 0 || minutes < 0 || minutes >= 60) {
                return null;
            }
            return hours * 60 + minutes;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer tokenMinutes(String normalized) {
        Matcher matcher = TOKEN.matcher(normalized);
        int total = 0;
        int lastEnd = 0;
        boolean matched = false;
        while (matcher.find()) {
            if (!normalized.substring(lastEnd, matcher.start()).trim().isEmpty()) {
                return null;
            }
            String numberPart = matcher.group(1);
            String unit = matcher.group(2);
            double value;
            try {
                value = Double.parseDouble(numberPart);
            } catch (NumberFormatException ex) {
                return null;
            }
            if (unit == null || unit.toLowerCase(Locale.ROOT).startsWith("m")) {
                if (numberPart.contains(".")) {
                    return null;
                }
                total += (int) value;
            } else {
                total += (int) Math.round(value * 60.0);
            }
            matched = true;
            lastEnd = matcher.end();
        }
        if (!matched || !normalized.substring(lastEnd).trim().isEmpty()) {
            return null;
        }
        return total > 0 ? total : null;
    }
}
