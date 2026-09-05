package com.runeassist.flip.ui.flipsdialog;

import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.controller.ItemController;
import com.runeassist.flip.model.FlipV2;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import java.awt.*;

/** Opens flip item charts on the website (no in-plugin chart). */
public class VisualizeFlipPanel extends JPanel {
    private final ItemController itemController;
    private final RuneAssistConfig config;
    private final JLabel statusLabel = new JLabel();
    private FlipV2 currentFlip;

    public VisualizeFlipPanel(ItemController itemController, RuneAssistConfig config) {
        this.itemController = itemController;
        this.config = config;
        setLayout(new GridBagLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Flip charts are on the website");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        statusLabel.setText("<html><center>Open a flip from Recent Flips to view it in the browser.</center></html>");

        JButton openBtn = new JButton("Open on website");
        openBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        openBtn.addActionListener(e -> openCurrent());

        box.add(title);
        box.add(Box.createVerticalStrut(10));
        box.add(statusLabel);
        box.add(Box.createVerticalStrut(16));
        box.add(openBtn);
        add(box);
    }

    public void showFlipVisualization(FlipV2 flip) {
        currentFlip = flip;
        if (flip == null) {
            statusLabel.setText("<html><center>Open a flip from Recent Flips to view it in the browser.</center></html>");
            return;
        }
        String name = itemController != null ? itemController.getItemName(flip.getItemId()) : ("item " + flip.getItemId());
        statusLabel.setText("<html><center>Opening " + name + " on the website…</center></html>");
        openCurrent();
    }

    private void openCurrent() {
        if (currentFlip == null || currentFlip.getItemId() <= 0) {
            return;
        }
        String name = itemController != null ? itemController.getItemName(currentFlip.getItemId()) : "";
        PriceGraphWebsite.open(config, name, currentFlip.getItemId());
    }
}
