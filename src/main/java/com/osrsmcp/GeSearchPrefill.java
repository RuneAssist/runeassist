package com.osrsmcp;

import net.runelite.api.Client;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Puts the suggested item into the GE buy-search "last searched" row so the player can click
 * it to select the item. The player still clicks — nothing selects the item automatically;
 * the row's op-listener is wired to the game's own item-select routine, exactly as a real
 * "last searched" entry would be, so the click is user input.
 *
 * <p>Ported from Flipping Copilot's {@code GePreviousSearch} (BSD 2-Clause, Copyright (c)
 * 2024 Cillian Brewitt; see THIRD_PARTY_LICENSES.md) and adapted to RuneAssist's single
 * suggestion. Gated by {@code geSearchPrefill}.
 */
@Singleton
public class GeSearchPrefill
{
    private final Client client;
    private final ClientThread clientThread;
    private final OsrsMcpConfig config;
    private final SharedFlipState flip;
    private final GeWidgets ge;

    @Inject
    GeSearchPrefill(Client client, ClientThread clientThread, OsrsMcpConfig config, SharedFlipState flip)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.flip = flip;
        this.ge = new GeWidgets(client);
    }

    /**
     * Request a pre-fill. Deferred to the next client cycle so the GE search widgets are
     * fully built before we touch them (populating synchronously on the varc change is too
     * early — the row isn't there yet).
     */
    public void request()
    {
        clientThread.invokeLater(this::showSuggestedItem);
    }

    /** Populate the last-searched row with the suggestion. Runs on the client thread. */
    public void showSuggestedItem()
    {
        if (config == null || !config.geSearchPrefill() || !flip.valid) return;
        Widget searchResults = client.getWidget(ComponentID.CHATBOX_GE_SEARCH_RESULTS);
        if (searchResults == null) return;

        int itemId = flip.itemId;
        String name = flip.name;
        if (itemId <= 0 || name == null || name.isEmpty()) return;

        if ((ge.isPreviousSearchSet() || copilotRowExists()) && ge.showLastSearchEnabled())
        {
            setPreviousSearch(itemId, name);
        }
        else
        {
            createRowRect(itemId, name);
            createRowLabel();
            createRowItemName(name);
            createRowItemIcon(itemId);
        }
    }

    private boolean copilotRowExists()
    {
        Widget r = client.getWidget(ComponentID.CHATBOX_GE_SEARCH_RESULTS);
        if (r == null || r.getChildren() == null || r.getChildren().length < 2) return false;
        for (Widget c : r.getChildren())
        {
            if (c == null) continue;
            String t = c.getText();
            if (t != null && t.startsWith("RuneAssist:")) return true;
        }
        return false;
    }

    // Reuse the existing last-searched row children (0..3).
    private void setPreviousSearch(int itemId, String itemName)
    {
        Widget r = client.getWidget(ComponentID.CHATBOX_GE_SEARCH_RESULTS);
        if (r == null || r.getChild(0) == null) return;

        Widget row = r.getChild(0);
        row.setHasListener(true);
        row.setOnOpListener(754, itemId, 84);
        row.setOnKeyListener(754, itemId, -2147483640);
        row.setName("<col=ff9040>" + itemName + "</col>");
        row.setAction(0, "Select");
        row.revalidate();

        Widget label = r.getChild(1);
        if (label != null) { label.setText("RuneAssist:"); label.setOriginalWidth(95);
            label.setXTextAlignment(WidgetTextAlignment.LEFT); label.revalidate(); }

        Widget nameW = r.getChild(2);
        if (nameW != null) { nameW.setText(itemName); nameW.revalidate(); }

        Widget icon = r.getChild(3);
        if (icon != null) { icon.setItemId(itemId); icon.revalidate(); }
    }

    // Create the row when there's no existing last-searched entry.
    private void createRowRect(int itemId, String itemName)
    {
        Widget parent = client.getWidget(ComponentID.CHATBOX_GE_SEARCH_RESULTS);
        if (parent == null) return;
        Widget w = parent.createChild(0, WidgetType.RECTANGLE);
        w.setTextColor(0xFFFFFF);
        w.setOpacity(255);
        w.setName("<col=ff9040>" + itemName + "</col>");
        w.setHasListener(true);
        w.setFilled(true);
        w.setOriginalX(114);
        w.setOriginalY(0);
        w.setOriginalWidth(256);
        w.setOriginalHeight(32);
        w.setOnOpListener(754, itemId, 84);
        w.setOnKeyListener(754, itemId, -2147483640);
        w.setAction(0, "Select");
        w.setOnMouseOverListener((JavaScriptCallback) ev -> w.setOpacity(200));
        w.setOnMouseLeaveListener((JavaScriptCallback) ev -> w.setOpacity(255));
        w.revalidate();
    }

    private void createRowLabel()
    {
        Widget parent = client.getWidget(ComponentID.CHATBOX_GE_SEARCH_RESULTS);
        if (parent == null) return;
        Widget w = parent.createChild(1, WidgetType.TEXT);
        w.setText("RuneAssist:");
        w.setFontId(495);
        w.setOriginalX(114);
        w.setOriginalY(0);
        w.setOriginalWidth(95);
        w.setOriginalHeight(32);
        w.setYTextAlignment(1);
        w.revalidate();
    }

    private void createRowItemName(String itemName)
    {
        Widget parent = client.getWidget(ComponentID.CHATBOX_GE_SEARCH_RESULTS);
        if (parent == null) return;
        Widget w = parent.createChild(2, WidgetType.TEXT);
        w.setText(itemName);
        w.setFontId(495);
        w.setOriginalX(254);
        w.setOriginalY(0);
        w.setOriginalWidth(116);
        w.setOriginalHeight(32);
        w.setYTextAlignment(1);
        w.revalidate();
    }

    private void createRowItemIcon(int itemId)
    {
        Widget parent = client.getWidget(ComponentID.CHATBOX_GE_SEARCH_RESULTS);
        if (parent == null) return;
        Widget w = parent.createChild(3, WidgetType.GRAPHIC);
        w.setItemId(itemId);
        w.setItemQuantity(1);
        w.setItemQuantityMode(0);
        w.setRotationX(550);
        w.setModelZoom(1031);
        w.setBorderType(1);
        w.setOriginalX(214);
        w.setOriginalY(0);
        w.setOriginalWidth(36);
        w.setOriginalHeight(32);
        w.revalidate();
    }
}
