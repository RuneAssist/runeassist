package com.runeassist.flip.ui;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;

@Singleton
public class CopilotPanel extends JPanel {

    public final SuggestionPanel suggestionPanel;
    public final StatsPanelV2 statsPanel;
    public final ControlPanel controlPanel;

    @Inject
    public CopilotPanel(SuggestionPanel suggestionPanel,
                        StatsPanelV2 statsPanel,
                        ControlPanel controlPanel) {
        this.statsPanel = statsPanel;
        this.suggestionPanel = suggestionPanel;
        this.controlPanel = controlPanel;

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

        add(topPanel, BorderLayout.NORTH);
        add(statsPanel, BorderLayout.CENTER);
    }

    public void refresh() {
        if (!UIUtilities.ensureEdt(this::refresh)) return;
        suggestionPanel.refresh();
        controlPanel.refresh();
        statsPanel.refresh();
    }
}
