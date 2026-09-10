package com.runeassist.flip.ui;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/** The summary remains visible while detailed statistics are folded away. */
final class CollapsibleStatsCard extends JPanel {
    private final JButton toggle = new JButton();
    private final JComponent details;
    private final Consumer<Boolean> saveCollapsed;
    private boolean collapsed;
    private String summary = "Session · 0 gp";

    CollapsibleStatsCard(JComponent details, boolean collapsed, Consumer<Boolean> saveCollapsed) {
        super(new BorderLayout());
        this.details = details;
        this.collapsed = collapsed;
        this.saveCollapsed = saveCollapsed;
        setBackground(RuneAssistColors.CARD);
        toggle.setHorizontalAlignment(SwingConstants.LEFT);
        toggle.setForeground(RuneAssistColors.TEXT);
        toggle.setBackground(RuneAssistColors.CARD);
        toggle.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        toggle.addActionListener(e -> {
            this.collapsed = !this.collapsed;
            update();
            saveCollapsed.accept(this.collapsed);
        });
        add(toggle, BorderLayout.NORTH);
        add(details, BorderLayout.CENTER);
        update();
    }

    void setSummary(String summary, Color color) {
        this.summary = summary;
        toggle.setForeground(color);
        update();
    }

    private void update() {
        details.setVisible(!collapsed);
        toggle.setText(summary + (collapsed ? "  ▾" : "  ▴"));
        toggle.setToolTipText(collapsed ? "Expand statistics" : "Collapse statistics");
        toggle.getAccessibleContext().setAccessibleName(toggle.getToolTipText() + ": " + summary);
        revalidate();
        repaint();
    }
}
