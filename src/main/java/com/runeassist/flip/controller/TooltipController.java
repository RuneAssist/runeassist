package com.runeassist.flip.controller;

import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.ui.OfferAge;
import com.runeassist.flip.ui.UIUtilities;
import com.runeassist.flip.util.ProfitCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.FontTypeFace;
import net.runelite.api.Point;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.awt.Rectangle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TooltipController {
    private static final int SCRIPT_TOOLTIP_GE = 526;
    private static final int LINE_HEIGHT = 15;
    private static final int GE_SLOT_COUNT = 8;
    private static final int FIRST_SLOT_CHILD_ID = 7;

    private static final int WIDTH_PADDING = 4;

    private final Client client;
    private final ProfitCalculator profitCalculator;
    private final OfferAge offerAge;
    private final RuneAssistConfig config;

    public void tooltip(ScriptPostFired e) {
        if(e.getScriptId() != SCRIPT_TOOLTIP_GE) {
            return;
        }

        Widget tooltip = client.getWidget(InterfaceID.GeOffers.TOOLTIP);

        if(tooltip == null || tooltip.isHidden()) {
            return;
        }

        Widget background = tooltip.getChild(0);
        Widget border = tooltip.getChild(1);
        Widget text = tooltip.getChild(2);

        if (text != null && background != null && border != null) {

            if (text.getText().contains("Profit:") || text.getText().contains("Listed ")) {
                return; // already carries our lines; the script re-fires while the mouse rests
            }

            int added = 0;
            String name = getItemNameFromTooltipText(text.getText());
            if (name != null && isItemSelling(text.getText())) {
                long profit = profitCalculator.getProfitByItemName(name);
                text.setText(text.getText()  + "<br>Profit: " + UIUtilities.quantityToRSDecimalStack(profit, false) + " gp");
                added++;
            }
            String age = name != null && config.slotOfferAge() ? offerAge.tooltipLine(hoveredSlot(), System.currentTimeMillis()) : null;
            if (age != null) {
                text.setText(text.getText() + "<br>" + age);
                added++;
            }
            if (added == 0) {
                return;
            }
            tooltip.setOriginalHeight(tooltip.getOriginalHeight() + LINE_HEIGHT * added);

            int width = calculateTooltipWidth(text.getFont(), text.getText());
            tooltip.setOriginalWidth(width);

            tooltip.revalidate();
            border.revalidate();
            background.revalidate();
            text.revalidate();
        }
    }

    /** The slot under the mouse, the one the tooltip is for; -1 if none. */
    private int hoveredSlot() {
        Point mouse = client.getMouseCanvasPosition();
        if (mouse == null) {
            return -1;
        }
        for (int slot = 0; slot < GE_SLOT_COUNT; slot++) {
            Widget widget = client.getWidget(InterfaceID.GE_OFFERS, FIRST_SLOT_CHILD_ID + slot);
            Rectangle bounds = widget == null || widget.isHidden() ? null : widget.getBounds();
            if (bounds != null && bounds.contains(mouse.getX(), mouse.getY())) {
                return slot;
            }
        }
        return -1;
    }

    public boolean isItemSelling(String text) {
        text = text.replaceAll("<br>", " ").trim();
        Pattern pattern = Pattern.compile("^(Buying|Selling): (.+) (\\d{1,3}(?:,\\d{3})*|\\d+) / (\\d{1,3}(?:,\\d{3})*|\\d+)( Profit: -?[\\d,]+ gp?)?$");
        Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            String action = matcher.group(1);
            return action.equals("Selling");
        } else {
            return false; // or handle as you wish
        }
    }

    public String getItemNameFromTooltipText(String text) {
        text = text.replaceAll("<br>", " ").trim();
        Pattern pattern = Pattern.compile("^(Buying|Selling): (.+) (\\d{1,3}(?:,\\d{3})*|\\d+) / (\\d{1,3}(?:,\\d{3})*|\\d+)( Profit: -?[\\d,]+ gp?)?$");
        Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            return matcher.group(2);
        } else {
            return null; // or handle as you wish
        }
    }

    private int calculateTooltipWidth(FontTypeFace f, String text)
    {
        final String[] lines = text.split("<br>");
        int maxWidth = 0;
        for (String line : lines) {
            String left = "";
            left = line;

            int width = f.getTextWidth(left);
            if (width > maxWidth) {
                maxWidth = width;
            }
        }
        return maxWidth + WIDTH_PADDING;
    }
}
