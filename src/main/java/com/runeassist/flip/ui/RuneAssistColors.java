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
 * Sidecar / flips-dialog identity. "Ledger" direction: brass on RuneLite dark, paired with
 * the gate-chevron coin mark — not Flipping Copilot orange, not the earlier teal.
 */
public final class RuneAssistColors {
    /** Primary accent. #D1A537 */
    public static final Color ACCENT = new Color(0xD1A537);
    /** Hover / selected fill. #B08A2E */
    public static final Color ACCENT_HOVER = new Color(0xB08A2E);
    /** Selected-tab and table-selection background. #4A3A1C */
    public static final Color ACCENT_MUTED = new Color(0x4A3A1C);
    /** Text/icons sitting on a filled accent chip. */
    public static final Color ON_ACCENT = new Color(0x1A1408);
    public static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;
    public static final Color SHELL = ColorScheme.DARK_GRAY_COLOR;
    public static final Color TEXT = ColorScheme.LIGHT_GRAY_COLOR;
    // Not MEDIUM_GRAY: against CARD that is about 2.3:1, well under the 4.5:1 body text needs,
    // and the why-line and limit text were hard to read. This clears 4.5:1 while staying visibly
    // secondary to TEXT.
    public static final Color MUTED = new Color(0x8E8E8E);
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
        return BorderFactory.createEmptyBorder(8, 8, 8, 8);
    }

    public static Border sectionHeaderBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, ACCENT),
                BorderFactory.createEmptyBorder(0, 0, 4, 0));
    }

    public static JLabel kicker(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(ACCENT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 10f));
        label.setBorder(sectionHeaderBorder());
        return label;
    }

    public static JLabel flagChip(String text) {
        JLabel chip = new JLabel(text);
        chip.setOpaque(true);
        chip.setBackground(ACCENT_MUTED);
        chip.setForeground(ACCENT);
        chip.setFont(chip.getFont().deriveFont(Font.PLAIN, 9f));
        chip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT_HOVER),
                BorderFactory.createEmptyBorder(1, 5, 1, 5)));
        return chip;
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
