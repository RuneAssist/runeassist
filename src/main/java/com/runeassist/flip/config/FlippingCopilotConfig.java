package com.runeassist.flip.config;

import com.runeassist.flip.ui.UIUtilities;
import net.runelite.client.config.*;
import net.runelite.client.ui.ColorScheme;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;


@ConfigGroup("runeassistflip")
public interface FlippingCopilotConfig extends Config
{
    // Keep this block first: RuneLite sorts sections by position, and users look at
    // Configuration → RuneAssist Flipping. Both toggles are opt-in (Hub requirement).
    @ConfigSection(
            name = "Privacy",
            description = "Optional anonymous data contribution and cloud history sync",
            position = 0
    )
    String privacySection = "privacySection";

    @ConfigItem(
            keyName = "shareTelemetry",
            name = "Contribute anonymous data",
            description = "Off by default. Sends anonymised GE fills (hashed account id, never your " +
                    "RSN or chat) to RuneAssist ingest. Does not gate flip suggestions (those use " +
                    "Ares /v1/flips independently). Untick stays local. No endpoint or token needed " +
                    "for the hosted server.",
            warning = "This feature submits your Grand Exchange offers and IP address to a 3rd-party server not controlled or verified by RuneLite developers",
            section = privacySection,
            position = 0
    )
    default boolean shareTelemetry()
    {
        return false;
    }

    @ConfigItem(
            keyName = "cloudSync",
            name = "Cloud sync flip history",
            description = "Off by default. Silently links this client to RuneAssist cloud so GE fills " +
                    "sync across your devices (raw transactions only — not derived profit). Untick to keep " +
                    "history on this PC. Failures never block suggestions. Pair another device from the " +
                    "plugin Preferences panel.",
            warning = "This feature submits your Grand Exchange transactions and IP address to a 3rd-party server not controlled or verified by RuneLite developers",
            section = privacySection,
            position = 1
    )
    default boolean cloudSync()
    {
        return false;
    }

    @ConfigSection(
            name = "Advanced contribution",
            description = "Only needed for a custom ingest server. Production uses the hosted default.",
            position = 20,
            closedByDefault = true
    )
    String advancedContributionSection = "advancedContributionSection";

    @ConfigItem(
            keyName = "telemetryEndpoint",
            name = "Custom ingest URL",
            description = "Leave the default unless you run your own ingest server. " +
                    "Hosted production is https://runeassist.ares-server.co.uk/v1/ingest.",
            section = advancedContributionSection,
            position = 0
    )
    default String telemetryEndpoint()
    {
        return "https://runeassist.ares-server.co.uk/v1/ingest";
    }

    @ConfigItem(
            keyName = "telemetryToken",
            name = "Custom ingest token",
            description = "Leave blank for the hosted RuneAssist server. Only needed if you " +
                    "point the URL above at your own ingest that requires a Bearer token.",
            section = advancedContributionSection,
            position = 1,
            secret = true
    )
    default String telemetryToken()
    {
        return "";
    }

    public enum PriceGraphWebsite
    {
        FLIPPING_COPILOT("RuneAssist"),
        OSRS_WIKI("OSRS Wiki"),
        GE_TRACKER("GE Tracker"),
        PLATINUM_TOKENS("PlatinumTokens"),
        GE_DATABASE("GE Database"),
        OSRS_CLOUD("Osrs.cloud"),
        OSRS_EXCHANGE("OSRS Exchange"),
        FLIPPING_GG("Flipping.gg");


        private final String name;
        PriceGraphWebsite(String name)
        {
            this.name = name;
        }

        @Override
        public String toString()
        {
            return name;
        }

        public String getUrl(String itemName, int itemId)
        {
            switch (this)
            {
                case OSRS_WIKI:
                    return "https://prices.runescape.wiki/osrs/item/" + itemId;
                case GE_TRACKER:
                    return "https://www.ge-tracker.com/item/" + itemId;
                case PLATINUM_TOKENS:
                    String platinumTokensFormattedName = itemName
                            .toLowerCase()
                            .replace("'", "")
                            .replace("(", " ")
                            .replace(")", "")
                            .replace("+", " plus")
                            .replace("  ", " ")
                            .replace(" ", "-");
                    return "https://platinumtokens.com/item/" + platinumTokensFormattedName;
                case GE_DATABASE:
                    return "https://secure.runescape.com/m=itemdb_oldschool/viewitem?obj=" + itemId;
                case OSRS_EXCHANGE:
                    String osrsExchangeFormattedName = itemName.toLowerCase().replace(' ','-');
                    return "https://www.osrs.exchange/item/"
                            + URLEncoder.encode(osrsExchangeFormattedName, StandardCharsets.UTF_8);
                case OSRS_CLOUD:
                    return "https://prices.osrs.cloud/item/" + itemId;
                case FLIPPING_GG:
                    return "https://www.flipping.gg/items/" + itemId;
                default:
                    return "";
            }
        }
    }

    @ConfigSection(
            name = "Offer Setup Assistance",
            description = "Configure suggestion highlights and offer setup features",
            position = 1
    )
    String offerSetupSection = "offerSetupSection";

    @ConfigItem(
            keyName = "suggestionHighlights",
            name = "Highlight suggested actions",
            description = "Show highlight overlays on the GE interface for suggested actions.",
            section = offerSetupSection,
            position = 1
    )
    default boolean suggestionHighlights()
    {
        return true;
    }

    @ConfigItem(
            keyName = "lowDataMode",
            name = "Low data mode",
            description = "When enabled, price graph data is only sent when opening the graph.",
            section = offerSetupSection,
            position = 7
    )
    default boolean lowDataMode()
    {
        return false;
    }
    @ConfigItem(
            keyName = "slotActionSwap",
            name = "Swap slot left-click action",
            description = "Automatically set the left-click option on GE slots to match the suggested action (e.g. Abort offer)",
            section = offerSetupSection,
            position = 2
    )
    default boolean slotActionSwap() {
        return true;
    }

    @ConfigItem(
            keyName = "misClickProtection",
            name = "Mis-click prevention",
            description = "Require right click to confirm when price/quantity set incorrectly",
            section = offerSetupSection,
            position = 3
    )
    default boolean disableLeftClickConfirm()
    {
        return false;
    }

    @ConfigItem(
            keyName = "quickSetKeybind",
            name = "Price/quantity set keybind",
            description = "Keybind to quickly set the price or quantity of a GE offer to the suggested value",
            section = offerSetupSection,
            position = 4
    )
    default Keybind quickSetKeybind()
    {
        return new Keybind(KeyEvent.VK_E, 0);
    }

    @ConfigItem(
            keyName = "skipSuggestionKeybind",
            name = "Skip suggestion keybind",
            description = "Keybind to skip the current suggestion and request a new one",
            section = offerSetupSection,
            position = 5
    )
    default Keybind skipSuggestionKeybind()
    {
        return Keybind.NOT_SET;
    }

    @ConfigItem(
            keyName = "openGraphKeybind",
            name = "Open graph keybind",
            description = "Keybind to open the price graph for the current suggestion",
            section = offerSetupSection,
            position = 6
    )
    default Keybind openGraphKeybind()
    {
        return Keybind.NOT_SET;
    }

    @ConfigSection(
            name = "Appearance",
            description = "Configure visual appearance and colors",
            position = 2
    )
    String appearanceSection = "appearanceSection";

    @ConfigItem(
            keyName = "priceGraphMenuOptionEnabled",
            name = "Enable price graph menu option",
            description = "Adds a menu option to open the RuneAssist price graph on applicable right clicks.",
            section = appearanceSection,
            position = 1
    )
    default boolean priceGraphMenuOptionEnabled()
    {
        return true;
    }

    @ConfigItem(
            keyName = "priceGraphButton",
            name = "Graph button",
            description = "The page to open when the graph button is clicked.",
            section = appearanceSection,
            position = 2
    )
    default PriceGraphWebsite priceGraphWebsite()
    {
        return PriceGraphWebsite.FLIPPING_COPILOT;
    }

    @ConfigItem(
            keyName = "profitAmountColor",
            name = "Flip tracker profit color",
            description = "The color of the profit amount text in the flip tracker",
            section = appearanceSection,
            position = 3
    )
    default Color profitAmountColor() {
        return ColorScheme.GRAND_EXCHANGE_PRICE;
    }

    @ConfigItem(
            keyName = "lossAmountColor",
            name = "Flip tracker loss color",
            description = "The color of the loss amount text in the flip tracker",
            section = appearanceSection,
            position = 4
    )
    default Color lossAmountColor() {
        return UIUtilities.OUTDATED_COLOR;
    }

    @ConfigItem(
            keyName = "portfolioBankTag",
            name = "Portfolio bank tag",
            description = "Create a Bank Tags tab for banked portfolio items.",
            section = appearanceSection,
            position = 5
    )
    default boolean portfolioBankTag()
    {
        return true;
    }

    @ConfigItem(
            keyName = "portfolioTooltips",
            name = "Enable portfolio tooltips",
            description = "Show portfolio-related tooltips on items.",
            section = appearanceSection,
            position = 6
    )
    default boolean portfolioTooltips()
    {
        return true;
    }

    @ConfigItem(
            keyName = "portfolioIcons",
            name = "Enable portfolio icons",
            description = "Show portfolio icons on items.",
            section = appearanceSection,
            position = 7
    )
    default boolean portfolioIcons()
    {
        return true;
    }

    @ConfigSection(
            name = "Slot Price Coloring",
            description = "Configure GE slot price colors based on profitability",
            position = 3
    )
    String slotPriceColorSection = "slotPriceColorSection";

    @ConfigItem(
            keyName = "slotPriceColorEnabled",
            name = "Enable slot price coloring",
            description = "Color GE slot prices based on profitability",
            section = slotPriceColorSection,
            position = 1
    )
    default boolean slotPriceColorEnabled()
    {
        return false;
    }

    @ConfigItem(
            keyName = "slotPriceProfitableColor",
            name = "Buy/Profitable color",
            description = "The color for buy offers at the suggested price, and profitable sell offers (flips)",
            section = slotPriceColorSection,
            position = 2
    )
    default Color slotPriceProfitableColor()
    {
        return new Color(0xAFDCFF);
    }

    @ConfigItem(
            keyName = "slotPriceUnprofitableColor",
            name = "Unprofitable color",
            description = "The color for sell offers (flips) that will result in a loss",
            section = slotPriceColorSection,
            position = 3
    )
    default Color slotPriceUnprofitableColor()
    {
        return new Color(0xFF5E5E);
    }

    @ConfigSection(
            name = "Notifications",
            description = "Configure notification settings for flipping actions",
            position = 4
    )
    String notificationsSection = "notificationsSection";

    @ConfigItem(
            keyName = "dumpAlertSound",
            name = "Dump alert sound",
            description = "Play the dump alert sound when a dump alert arrives.",
            section = notificationsSection,
            position = 1
    )
    default boolean dumpAlertSound()
    {
        return true;
    }

    @ConfigItem(
            keyName = "enableChatNotifications",
            name = "Enable chat notifications",
            description = "Show chat notifications for suggested action when the side panel is closed.",
            section = notificationsSection,
            position = 2
    )
    default boolean enableChatNotifications()
    {
        return true;
    }

    @ConfigItem(
            keyName = "enableTrayNotifications",
            name = "Enable tray notifications",
            description = "Show tray notifications for suggested action when runelite is out of focus.",
            section = notificationsSection,
            position = 3
    )
    default boolean enableTrayNotifications()
    {
        return true;
    }

    @ConfigItem(
            keyName = "chatTextColor",
            name = "Chat text color",
            description = "The color of the text for RuneAssist messages in the chat.",
            section = notificationsSection,
            position = 4
    )
    default Color chatTextColor() {
        return new Color(0x0040FF);
    }

    @ConfigItem(
            keyName = "webhook",
            name = "Webhook URL",
            description = "The Discord Webhook URL for sending display name and profit.",
            section = notificationsSection,
            position = 5
    )
    String webhook();

}
