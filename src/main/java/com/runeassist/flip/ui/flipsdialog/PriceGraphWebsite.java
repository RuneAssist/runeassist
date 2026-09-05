package com.runeassist.flip.ui.flipsdialog;

import com.runeassist.flip.config.RuneAssistConfig;
import net.runelite.client.util.LinkBrowser;

/** Opens item price graphs on the configured website (no in-plugin chart UI). */
final class PriceGraphWebsite {
    private PriceGraphWebsite() {}

    static String itemUrl(RuneAssistConfig config, String itemName, int itemId) {
        RuneAssistConfig.PriceGraphWebsite site = config == null ? null : config.priceGraphWebsite();
        if (site == null) {
            site = RuneAssistConfig.PriceGraphWebsite.RUNEASSIST;
        }
        String url = site.getUrl(itemName == null ? "" : itemName, itemId);
        return url == null || url.isEmpty() ? null : url;
    }

    static void open(RuneAssistConfig config, String itemName, int itemId) {
        String url = itemUrl(config, itemName, itemId);
        if (url != null) {
            LinkBrowser.browse(url);
        }
    }
}
