package com.runeassist.flip.ui.flipsdialog;

public final class WebAnalyticsLinks {

    public static final String SECTION_PROFIT = "profit";
    public static final String SECTION_FLIPS = "flips";
    public static final String SECTION_ITEMS = "items";
    public static final String SECTION_ATTENTION = "attention";
    public static final String SECTION_ACCOUNTS = "accounts";

    private WebAnalyticsLinks() {
    }

    public static String url(String websiteBaseUrl, String section) {
        String base = normalizeBase(websiteBaseUrl);
        if (section == null || section.trim().isEmpty()) {
            return base + "#/dashboard";
        }
        String s = section.trim().toLowerCase();
        if (SECTION_ACCOUNTS.equals(s)) {
            return base + "#/accounts";
        }
        return base + "#/dashboard?section=" + s;
    }

    static String normalizeBase(String websiteBaseUrl) {
        if (websiteBaseUrl == null || websiteBaseUrl.trim().isEmpty()) {
            return "https://runeassist.com/app/";
        }
        return websiteBaseUrl.endsWith("/") ? websiteBaseUrl : websiteBaseUrl + "/";
    }
}
