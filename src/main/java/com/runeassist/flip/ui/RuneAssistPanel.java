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
    public final StatusStrip statusStrip;

    private final PreferencesPanel preferencesPanel;
    private final JLayeredPane layeredPane = new JLayeredPane();
    private final JPanel mainContent = new JPanel(new BorderLayout());
    private final JLabel gearButton;
    private boolean settingsOpen;

    @Inject
    public RuneAssistPanel(SuggestionPanel suggestionPanel,
                        StatsPanelV2 statsPanel,
                        ControlPanel controlPanel,
                        StatusStrip statusStrip,
                        PreferencesPanel preferencesPanel) {
        this.statsPanel = statsPanel;
        this.suggestionPanel = suggestionPanel;
        this.controlPanel = controlPanel;
        this.statusStrip = statusStrip;
        this.preferencesPanel = preferencesPanel;

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
        topPanel.add(statusStrip);
        topPanel.add(Box.createRigidArea(new Dimension(MainPanel.CONTENT_WIDTH, 8)));

        mainContent.setOpaque(true);
        mainContent.setBackground(RuneAssistColors.SHELL);
        mainContent.add(topPanel, BorderLayout.NORTH);
        mainContent.add(statsPanel, BorderLayout.CENTER);

        preferencesPanel.setVisible(false);
        preferencesPanel.setOpaque(true);
        preferencesPanel.setBackground(RuneAssistColors.CARD);

        gearButton = UIUtilities.gearButton("Settings", this::toggleSettings);
        gearButton.setEnabled(true);
        gearButton.setFocusable(true);
        gearButton.setBackground(RuneAssistColors.CARD);
        gearButton.setOpaque(true);

        layeredPane.setOpaque(true);
        layeredPane.setBackground(RuneAssistColors.SHELL);
        layeredPane.setLayout(null);
        layeredPane.add(mainContent, JLayeredPane.DEFAULT_LAYER);
        layeredPane.add(preferencesPanel, JLayeredPane.PALETTE_LAYER);
        layeredPane.add(gearButton, JLayeredPane.DRAG_LAYER);

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
        gearButton.setBounds(Math.max(0, w - 24), 6, 20, 20);
        if (mainContent.isVisible()) {
            mainContent.validate();
        }
        if (preferencesPanel.isVisible()) {
            preferencesPanel.validate();
        }
    }

    private void toggleSettings() {
        setSettingsOpen(!settingsOpen);
    }

    /** Open Preferences (account / pairing). Used from the top-bar identity control. */
    public void openSettings() {
        setSettingsOpen(true);
    }

    private void setSettingsOpen(boolean open) {
        if (!UIUtilities.ensureEdt(() -> setSettingsOpen(open))) {
            return;
        }
        if (settingsOpen == open) {
            if (open) {
                preferencesPanel.refresh();
            }
            return;
        }
        settingsOpen = open;
        preferencesPanel.setVisible(settingsOpen);
        mainContent.setVisible(!settingsOpen);
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

    public void refresh() {
        if (!UIUtilities.ensureEdt(this::refresh)) return;
        if (settingsOpen) {
            preferencesPanel.refresh();
            return;
        }
        suggestionPanel.refresh();
        controlPanel.refresh();
        statusStrip.refresh();
        statsPanel.refresh();
    }
}
