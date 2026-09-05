package com.runeassist.flip.ui.flipsdialog;

import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.controller.ItemController;
import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.LinkBrowser;

import javax.swing.*;
import java.awt.*;

/** Website CTA for price graphs (in-plugin chart UI removed for Hub token budget). */
public class PriceGraphPanel extends JPanel {
    private final ItemController itemController;
    private final RuneAssistConfig config;
    private final SuggestionManager suggestionManager;
    private Integer pendingItemId;

    public PriceGraphPanel(ItemController itemController, RuneAssistConfig config, SuggestionManager suggestionManager) {
        this.itemController = itemController;
        this.config = config;
        this.suggestionManager = suggestionManager;
        setLayout(new GridBagLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Price graphs live on the website");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel blurb = new JLabel("<html><center>Open an item graph in your browser.<br>"
                + "Use the Graph button on a suggestion, or right-click an offer.</center></html>");
        blurb.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        blurb.setAlignmentX(Component.CENTER_ALIGNMENT);

        JButton openBtn = new JButton("Open website graph");
        openBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        openBtn.addActionListener(e -> openCurrent());

        box.add(title);
        box.add(Box.createVerticalStrut(10));
        box.add(blurb);
        box.add(Box.createVerticalStrut(16));
        box.add(openBtn);
        add(box);
    }

    public void onTabShown() {}

    public void showItem(int itemId) {
        pendingItemId = itemId > 0 ? itemId : null;
        openCurrent();
    }

    public void showSuggestionPriceGraph() {
        Suggestion s = suggestionManager.getSuggestion();
        if (s != null && !s.isWaitSuggestion() && s.getItemId() > 0) {
            showItem(s.getItemId());
        } else {
            LinkBrowser.browse(home());
        }
    }

    public void showLandingCard() {
        pendingItemId = null;
    }

    private void openCurrent() {
        int itemId = pendingItemId != null ? pendingItemId : suggestionItemId();
        if (itemId > 0) {
            String name = itemController != null ? itemController.getItemName(itemId) : "";
            PriceGraphWebsite.open(config, name, itemId);
            return;
        }
        LinkBrowser.browse(home());
    }

    private int suggestionItemId() {
        Suggestion s = suggestionManager.getSuggestion();
        return s != null && !s.isWaitSuggestion() ? s.getItemId() : 0;
    }

    private String home() {
        String url = PriceGraphWebsite.itemUrl(config, "", 0);
        return url != null ? url : "https://runeassist.com/app/";
    }
}
