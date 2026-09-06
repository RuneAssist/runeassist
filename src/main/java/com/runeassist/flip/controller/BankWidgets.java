package com.runeassist.flip.controller;

import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.util.Text;

import java.awt.Rectangle;

/** Shared bank / GE widget lookups for highlights and portfolio menus. */
public final class BankWidgets {
    static final int BANK_WIDGET_GROUP = 12;
    static final int[] BANK_ITEM_CONTAINER_CHILDREN = {12, 13, 89};
    static final int BANK_INVENTORY_WIDGET_GROUP = 15;
    static final int BANK_INVENTORY_WIDGET_CHILD = 3;

    private static final String PORTFOLIO_BANK_TAG = "portfolio";
    private static final int BANK_TAG_TAB_CHILD_OFFSET = 4;
    private static final int CLOSE_BUTTON_INDEX = 11;

    private BankWidgets() {
    }

    public static boolean isBankOpen(Client client) {
        Widget bank = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
        return bank != null && !bank.isHidden();
    }

    public static Widget bankCloseButton(Client client) {
        return frameChild(client.getWidget(InterfaceID.Bankmain.FRAME), CLOSE_BUTTON_INDEX);
    }

    public static Widget geCloseButton(Client client) {
        return frameChild(client.getWidget(InterfaceID.GeOffers.FRAME), CLOSE_BUTTON_INDEX);
    }

    public static Widget geItemSearchButton(Client client) {
        Widget setupContainer = client.getWidget(InterfaceID.GE_OFFERS, 26);
        if (setupContainer == null) {
            return null;
        }
        Widget[] children = setupContainer.getChildren();
        return children != null && children.length > 0 ? children[0] : null;
    }

    /** Prefer noted GE-side inventory match, else unnoted. */
    public static Widget geInventoryItem(Client client, int unnotedItemId) {
        Widget inventory = client.getWidget(InterfaceID.GE_OFFERS_SIDE, 0);
        if (inventory == null) {
            return null;
        }
        Widget[] children = inventory.getDynamicChildren();
        if (children == null) {
            return null;
        }
        Widget noted = null;
        Widget unnoted = null;
        for (Widget widget : children) {
            if (widget == null || widget.isHidden() || widget.getItemQuantity() <= 0) {
                continue;
            }
            int itemId = widget.getItemId();
            if (itemId <= 0 || !matchesItemId(client, itemId, unnotedItemId)) {
                continue;
            }
            if (client.getItemDefinition(itemId).getNote() != -1) {
                noted = widget;
            } else {
                unnoted = widget;
            }
        }
        return noted != null ? noted : unnoted;
    }

    public static Widget bankItem(Client client, int unnotedItemId) {
        for (int childId : BANK_ITEM_CONTAINER_CHILDREN) {
            Widget bankItems = client.getWidget(BANK_WIDGET_GROUP, childId);
            if (bankItems == null || bankItems.isHidden()) {
                continue;
            }
            Widget itemWidget = visibleItem(client, bankItems, unnotedItemId);
            if (itemWidget != null) {
                return itemWidget;
            }
        }
        return null;
    }

    public static Widget portfolioTagButton(Client client, PluginManager pluginManager, boolean enabled) {
        if (!enabled) {
            return null;
        }
        BankTagsPlugin bankTagsPlugin = BankTagsLookup.findActive(pluginManager);
        if (bankTagsPlugin == null || PORTFOLIO_BANK_TAG.equals(bankTagsPlugin.getActiveTag())) {
            return null;
        }
        Widget parent = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
        if (parent == null || parent.isHidden() || parent.getChildren() == null) {
            return null;
        }
        Widget[] children = parent.getChildren();
        for (int i = BANK_TAG_TAB_CHILD_OFFSET; i < children.length; i += 2) {
            Widget button = children[i];
            if (button == null || button.isHidden()) {
                continue;
            }
            String widgetName = button.getName();
            if (widgetName != null && PORTFOLIO_BANK_TAG.equals(Text.removeTags(widgetName))) {
                return button;
            }
        }
        return null;
    }

    public static boolean matchesItemId(Client client, int itemId, int unnotedItemId) {
        if (itemId == unnotedItemId) {
            return true;
        }
        if (itemId <= 0) {
            return false;
        }
        ItemComposition itemComposition = client.getItemDefinition(itemId);
        return itemComposition.getNote() != -1 && itemComposition.getLinkedNoteId() == unnotedItemId;
    }

    private static Widget frameChild(Widget frame, int index) {
        if (frame == null || frame.getDynamicChildren() == null) {
            return null;
        }
        Widget[] children = frame.getDynamicChildren();
        return children.length > index ? children[index] : null;
    }

    private static Widget visibleItem(Client client, Widget itemContainer, int unnotedItemId) {
        Widget[] children = itemContainer.getDynamicChildren();
        if (children == null) {
            return null;
        }
        Rectangle containerBounds = itemContainer.getBounds();
        boolean clip = containerBounds != null && containerBounds.width > 0 && containerBounds.height > 0;
        for (Widget widget : children) {
            if (widget == null || widget.isHidden() || widget.getItemQuantity() <= 0) {
                continue;
            }
            if (!matchesItemId(client, widget.getItemId(), unnotedItemId)) {
                continue;
            }
            Rectangle bounds = widget.getBounds();
            if (clip && (bounds == null || !containerBounds.intersects(bounds))) {
                continue;
            }
            return widget;
        }
        return null;
    }
}
