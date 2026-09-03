package com.runeassist.flip.ui;

import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/**
 * Filled selected tab, no orange underline. Used by the flips dialog only.
 */
public class RuneAssistTabbedPaneUI extends BasicTabbedPaneUI {
    @Override
    protected void installDefaults() {
        super.installDefaults();
        highlight = RuneAssistColors.SHELL;
        lightHighlight = RuneAssistColors.SHELL;
        shadow = RuneAssistColors.SHELL;
        darkShadow = RuneAssistColors.SHELL;
        focus = RuneAssistColors.ACCENT;
        tabInsets = new Insets(6, 12, 6, 12);
        selectedTabPadInsets = new Insets(0, 0, 0, 0);
        tabAreaInsets = new Insets(4, 6, 0, 6);
        contentBorderInsets = new Insets(1, 0, 0, 0);
    }

    @Override
    protected void paintTabBackground(Graphics g, int tabPlacement, int tabIndex,
                                      int x, int y, int w, int h, boolean isSelected) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (isSelected) {
            g2.setColor(RuneAssistColors.ACCENT_MUTED);
            g2.fillRoundRect(x + 1, y + 2, w - 2, h - 2, 6, 6);
        }
        g2.dispose();
    }

    @Override
    protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex,
                                  int x, int y, int w, int h, boolean isSelected) {
        // no underline
    }

    @Override
    protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) {
        int y = calculateTabAreaHeight(tabPlacement, runCount, maxTabHeight);
        g.setColor(RuneAssistColors.HAIRLINE);
        g.fillRect(0, y, tabPane.getWidth(), 1);
    }

    @Override
    protected void paintFocusIndicator(Graphics g, int tabPlacement, Rectangle[] rects, int tabIndex,
                                       Rectangle iconRect, Rectangle textRect, boolean isSelected) {
        // skip default dotted focus ring
    }

    @Override
    protected void paintText(Graphics g, int tabPlacement, Font font, FontMetrics metrics,
                             int tabIndex, String title, Rectangle textRect, boolean isSelected) {
        g.setFont(font.deriveFont(isSelected ? Font.BOLD : Font.PLAIN));
        g.setColor(isSelected ? RuneAssistColors.ACCENT : RuneAssistColors.MUTED);
        int textX = textRect.x;
        int textY = textRect.y + metrics.getAscent();
        g.drawString(title, textX, textY);
    }

    @Override
    protected int getTabLabelShiftY(int tabPlacement, int tabIndex, boolean isSelected) {
        return 0;
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        g.setColor(RuneAssistColors.SHELL);
        g.fillRect(0, 0, c.getWidth(), c.getHeight());
        super.paint(g, c);
    }
}
