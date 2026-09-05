package com.runeassist.flip.ui.flipsdialog;

import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import java.awt.*;

final class DialogUi {
    private DialogUi() {}

    static JPanel centeredMessage(String message, Color background, boolean opaque, float fontSize) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(opaque);
        if (background != null) {
            panel.setBackground(background);
        }
        JLabel label = new JLabel(message);
        label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        label.setFont(label.getFont().deriveFont(fontSize));
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setMinimumSize(label.getPreferredSize());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(label, gbc);
        return panel;
    }
}
