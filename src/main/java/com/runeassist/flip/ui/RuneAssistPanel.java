package com.runeassist.flip.ui;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;

@Singleton
public class RuneAssistPanel extends JPanel {

    public final SuggestionPanel suggestionPanel;
    public final StatsPanelV2 statsPanel;
    public final ControlPanel controlPanel;

    private final PreferencesPanel preferencesPanel;
    private final ShopFlipPanel shopFlipPanel;
    private final JLayeredPane layeredPane = new JLayeredPane();
    private final JPanel mainContent = new JPanel(new BorderLayout());
    private final JLabel gearButton;
    private final JLabel shopButton;
    private boolean settingsOpen;
    private boolean shopFlipOpen;

    @Inject
    public RuneAssistPanel(SuggestionPanel suggestionPanel,
                        StatsPanelV2 statsPanel,
                        ControlPanel controlPanel,
                        PreferencesPanel preferencesPanel,
                        ShopFlipPanel shopFlipPanel) {
        this.statsPanel = statsPanel;
        this.suggestionPanel = suggestionPanel;
        this.controlPanel = controlPanel;
        this.preferencesPanel = preferencesPanel;
        this.shopFlipPanel = shopFlipPanel;

        setLayout(new BorderLayout());
        setBackground(RuneAssistColors.SHELL);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        JPanel topPanel = new JPanel();
        topPanel.setOpaque(false);
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.add(suggestionPanel);
        topPanel.add(Box.createRigidArea(new Dimension(MainPanel.CONTENT_WIDTH, 8)));
        topPanel.add(controlPanel);
        topPanel.add(Box.createRigidArea(new Dimension(MainPanel.CONTENT_WIDTH, 8)));

        mainContent.setOpaque(true);
        mainContent.setBackground(RuneAssistColors.SHELL);
        mainContent.add(topPanel, BorderLayout.NORTH);
        mainContent.add(statsPanel, BorderLayout.CENTER);

        preferencesPanel.setVisible(false);
        preferencesPanel.setOpaque(true);
        preferencesPanel.setBackground(RuneAssistColors.CARD);

        shopFlipPanel.setVisible(false);
        shopFlipPanel.setOpaque(true);
        shopFlipPanel.setBackground(RuneAssistColors.CARD);

        gearButton = UIUtilities.gearButton("Settings", this::toggleSettings);
        gearButton.setEnabled(true);
        gearButton.setFocusable(true);
        gearButton.setBackground(RuneAssistColors.CARD);
        gearButton.setOpaque(true);

        shopButton = new JLabel("🏪"); // shop emoji
        shopButton.setToolTipText("Shop flips");
        shopButton.setHorizontalAlignment(SwingConstants.CENTER);
        shopButton.setForeground(RuneAssistColors.TEXT);
        shopButton.setBackground(RuneAssistColors.CARD);
        shopButton.setOpaque(true);
        shopButton.setFont(shopButton.getFont().deriveFont(12f));
        shopButton.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                toggleShopFlip();
            }

            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                shopButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
                shopButton.setForeground(RuneAssistColors.ACCENT);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                shopButton.setForeground(RuneAssistColors.TEXT);
            }
        });

        layeredPane.setOpaque(true);
        layeredPane.setBackground(RuneAssistColors.SHELL);
        layeredPane.setLayout(null);
        layeredPane.add(mainContent, JLayeredPane.DEFAULT_LAYER);
        layeredPane.add(preferencesPanel, JLayeredPane.PALETTE_LAYER);
        layeredPane.add(shopFlipPanel, JLayeredPane.PALETTE_LAYER);
        layeredPane.add(gearButton, JLayeredPane.DRAG_LAYER);
        layeredPane.add(shopButton, JLayeredPane.DRAG_LAYER);

        add(layeredPane, BorderLayout.CENTER);
    }

    @Override
    public void doLayout() {
        super.doLayout();
        layoutLayers();
    }

    private void layoutLayers() {
        int w = layeredPane.getWidth();
        int h = layeredPane.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        mainContent.setBounds(0, 0, w, h);
        preferencesPanel.setBounds(0, 0, w, h);
        shopFlipPanel.setBounds(0, 0, w, h);
        gearButton.setBounds(Math.max(0, w - 24), 6, 20, 20);
        shopButton.setBounds(Math.max(0, w - 46), 6, 20, 20);
        if (mainContent.isVisible()) {
            mainContent.validate();
        }
        if (preferencesPanel.isVisible()) {
            preferencesPanel.validate();
        }
        if (shopFlipPanel.isVisible()) {
            shopFlipPanel.validate();
        }
    }

    private void toggleSettings() {
        settingsOpen = !settingsOpen;
        if (settingsOpen && shopFlipOpen) {
            shopFlipOpen = false;
            shopFlipPanel.setVisible(false);
            shopFlipPanel.onHidden();
        }
        preferencesPanel.setVisible(settingsOpen);
        mainContent.setVisible(!settingsOpen && !shopFlipOpen);
        gearButton.setToolTipText(settingsOpen ? "Close settings" : "Settings");
        if (settingsOpen) {
            preferencesPanel.refresh();
        }
        layoutLayers();
        revalidate();
        repaint();
        if (!settingsOpen) {
            refresh();
        }
    }

    private void toggleShopFlip() {
        shopFlipOpen = !shopFlipOpen;
        if (shopFlipOpen && settingsOpen) {
            settingsOpen = false;
            preferencesPanel.setVisible(false);
        }
        shopFlipPanel.setVisible(shopFlipOpen);
        mainContent.setVisible(!settingsOpen && !shopFlipOpen);
        shopButton.setToolTipText(shopFlipOpen ? "Close shop flips" : "Shop flips");
        layoutLayers();
        revalidate();
        repaint();
        if (shopFlipOpen) {
            shopFlipPanel.refresh();
        } else {
            shopFlipPanel.onHidden();
            refresh();
        }
    }

    public void refresh() {
        if (!UIUtilities.ensureEdt(this::refresh)) return;
        if (settingsOpen) {
            preferencesPanel.refresh();
            return;
        }
        if (shopFlipOpen) {
            shopFlipPanel.refresh();
            return;
        }
        suggestionPanel.refresh();
        controlPanel.refresh();
        statsPanel.refresh();
    }
}
