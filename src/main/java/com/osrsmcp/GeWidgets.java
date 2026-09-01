package com.osrsmcp;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/**
 * Accessors for the Grand Exchange interface widgets (group {@link InterfaceID#GE_OFFERS} =
 * 465) and the offer-setup varbits. Used to locate the button the player should click next
 * so the highlight overlay can point at it.
 *
 * <p>The widget child indices and varbit usage here are ported from Flipping Copilot's
 * {@code GrandExchange} controller (BSD 2-Clause, Copyright (c) 2024 Cillian Brewitt,
 * https://github.com/cbrewitt/flipping-copilot) and adapted to this plugin. Read-only.
 */
class GeWidgets
{
    private final Client client;

    GeWidgets(Client client) { this.client = client; }

    boolean isOpen() { return client.getWidget(InterfaceID.GE_OFFERS, 7) != null; }

    /** Selected slot on the offer screen, or -1 on the home (8-slot) screen. */
    int openSlot() { return client.getVarbitValue(VarbitID.GE_SELECTEDSLOT) - 1; }

    boolean isSlotOpen() { return openSlot() != -1; }

    boolean isHomeScreenOpen() { return isOpen() && !isSlotOpen(); }

    Widget offerContainer() { return client.getWidget(InterfaceID.GE_OFFERS, 26); }

    Widget slotWidget(int slot) { return client.getWidget(InterfaceID.GE_OFFERS, 7 + slot); }

    /** The "create buy offer" (+) button in a home-screen slot. */
    Widget buyButton(int slot)
    {
        Widget s = slotWidget(slot);
        return s == null ? null : s.getChild(0);
    }

    Widget confirmButton()
    {
        Widget c = offerContainer();
        return c == null ? null : c.getChild(58);
    }

    Widget setPriceButton()
    {
        Widget c = offerContainer();
        return c == null ? null : c.getChild(54);
    }

    Widget setQuantityButton()
    {
        Widget c = offerContainer();
        return c == null ? null : c.getChild(51);
    }

    Widget backButton() { return client.getWidget(InterfaceID.GE_OFFERS, 4); }

    /** The setup screen (choosing price/qty before confirming) is open when Confirm shows. */
    boolean isSetupOfferOpen()
    {
        Widget confirm = confirmButton();
        return confirm != null && !confirm.isHidden();
    }

    int setupItemId()   { return client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH); }
    int setupPrice()    { return client.getVarbitValue(VarbitID.GE_NEWOFFER_PRICE); }
    int setupQuantity() { return client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY); }
    boolean setupIsBuy(){ return client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 0; }

    /** The game's "last searched" GE item row exists / is enabled (for search pre-fill). */
    boolean isPreviousSearchSet()   { return client.getVarpValue(VarPlayerID.GE_LAST_SEARCHED) != -1; }
    boolean showLastSearchEnabled() { return client.getVarbitValue(VarbitID.DISABLE_LAST_SEARCHED) == 0; }
}
