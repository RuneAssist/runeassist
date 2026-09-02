package com.runeassist.flip.ui;

import net.runelite.client.ui.ColorScheme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;

/**
 * Sidecar / flips-dialog identity. Teal on RuneLite dark — not Flipping Copilot orange.
 */
public final class RuneAssistColors {
    /** Primary accent. #2EC4B6 */
    public static final Color ACCENT = new Color(0x2EC4B6);
    /** Hover / selected fill. #1F9E94 */
    public static final Color ACCENT_HOVER = new Color(0x1F9E94);
    /** Selected-tab and table-selection background. #164E4A */
    public static final Color ACCENT_MUTED = new Color(0x164E4A);
    /** Text/icons sitting on a filled accent chip. */
    public static final Color ON_ACCENT = new Color(0x0B1A19);
    public static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;
    public static final Color SHELL = ColorScheme.DARK_GRAY_COLOR;
    public static final Color TEXT = ColorScheme.LIGHT_GRAY_COLOR;
    public static final Color MUTED = ColorScheme.MEDIUM_GRAY_COLOR;
    /**
     * Unselected-chip text (Volume Window / Risk toggle buttons). MUTED (rgb 77,77,77) on
     * CARD (rgb 30,30,30) measures ~1.9:1 contrast -- well under the WCAG AA minimum of
     * 4.5:1 for normal text, and was reported hard to read. This measures ~5.5:1.
     */
    public static final Color CHIP_TEXT_UNSELECTED = new Color(150, 150, 150);
    public static final Color HAIRLINE = new Color(0x3A3A3A);
    public static final Color RISK_LOW = ColorScheme.GRAND_EXCHANGE_PRICE;
    public static final Color RISK_HIGH = new Color(0xE05252);

    private RuneAssistColors() {
    }

    public static String hex(Color color) {
        return String.format("#%06X", 0xFFFFFF & color.getRGB());
    }

    public static Border cardBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, ACCENT),
                BorderFactory.createEmptyBorder(8, 10, 8, 8));
    }

    public static Border hairlineBottom() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, HAIRLINE),
                BorderFactory.createEmptyBorder(0, 0, 8, 0));
    }

    public static JLabel kicker(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(ACCENT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 10f));
        return label;
    }

    public static JLabel caption(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        label.setFont(label.getFont().deriveFont(10f));
        return label;
    }

    public static void styleGhostButton(JButton button) {
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setBackground(SHELL);
        button.setForeground(ACCENT);
        button.setMargin(new Insets(2, 10, 2, 10));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
    }

    public static void stylePrimaryButton(JButton button) {
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setBackground(ACCENT);
        button.setForeground(ON_ACCENT);
        button.setMargin(new Insets(2, 10, 2, 10));
        button.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
    }

    public static void styleChip(JComponent button, boolean selected, Color selectedColor) {
        Color accent = selectedColor != null ? selectedColor : ACCENT;
        button.setOpaque(true);
        button.setBackground(CARD);
        button.setForeground(selected ? accent : CHIP_TEXT_UNSELECTED);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 2, 0, selected ? accent : HAIRLINE),
                BorderFactory.createEmptyBorder(4, 6, 3, 6)));
    }
}
