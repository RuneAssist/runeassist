package com.runeassist.flip.ui.flipsdialog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class WebAnalyticsLinksTest {

    @Test
    public void dashboardRootAppendsHashRoute() {
        assertEquals(
                "https://runeassist.com/app/#/dashboard",
                WebAnalyticsLinks.url("https://runeassist.com/app/", null));
        assertEquals(
                "https://runeassist.com/app/#/dashboard",
                WebAnalyticsLinks.url("https://runeassist.com/app", "  "));
    }

    @Test
    public void sectionQueryAndAccountsPath() {
        assertEquals(
                "https://runeassist.com/app/#/dashboard?section=profit",
                WebAnalyticsLinks.url("https://runeassist.com/app/", WebAnalyticsLinks.SECTION_PROFIT));
        assertEquals(
                "https://runeassist.com/app/#/dashboard?section=attention",
                WebAnalyticsLinks.url("https://runeassist.com/app/", WebAnalyticsLinks.SECTION_ATTENTION));
        assertEquals(
                "https://runeassist.com/app/#/accounts",
                WebAnalyticsLinks.url("https://runeassist.com/app/", WebAnalyticsLinks.SECTION_ACCOUNTS));
    }

    @Test
    public void blankBaseFallsBackToProduction() {
        assertEquals(
                "https://runeassist.com/app/#/dashboard?section=flips",
                WebAnalyticsLinks.url(null, WebAnalyticsLinks.SECTION_FLIPS));
    }
}
