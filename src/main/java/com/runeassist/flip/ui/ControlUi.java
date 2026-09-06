package com.runeassist.flip.ui;

import javax.swing.*;
import java.awt.*;

/** Compact combo styling shared by ControlPanel chrome. */
final class ControlUi {
    private ControlUi() {
    }

    static void styleCompactCombo(JComboBox<String> combo) {
        combo.setBackground(RuneAssistColors.CARD);
        combo.setForeground(RuneAssistColors.TEXT);
        combo.setFocusable(false);
        combo.setMaximumRowCount(5);
        combo.setMinimumSize(new Dimension(56, 22));
        combo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        combo.setBorder(BorderFactory.createLineBorder(RuneAssistColors.HAIRLINE));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                c.setBackground(isSelected ? RuneAssistColors.ACCENT_MUTED : RuneAssistColors.CARD);
                c.setForeground(isSelected ? RuneAssistColors.ACCENT : RuneAssistColors.TEXT);
                if (c instanceof JComponent) {
                    ((JComponent) c).setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
                }
                return c;
            }
        });
    }

    static JPanel leftStrip(int maxHeight) {
        JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        strip.setOpaque(false);
        strip.setAlignmentX(Component.LEFT_ALIGNMENT);
        strip.setMaximumSize(new Dimension(Integer.MAX_VALUE, maxHeight));
        return strip;
    }

    static JPanel headerRow(JLabel label) {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(label);
        return header;
    }
}
