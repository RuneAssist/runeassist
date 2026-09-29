package com.runeassist.flip.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnboardingCardLayoutTest {
    @Test
    void cardAndItsButtonsFitTheSidePanelWidth() {
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        OnboardingCard card = new OnboardingCard(() -> { }, () -> { });
        JPanel below = new JPanel();
        below.setPreferredSize(new Dimension(MainPanel.CONTENT_WIDTH, 40));
        column.add(card);
        column.add(below);
        column.setSize(MainPanel.CONTENT_WIDTH, column.getPreferredSize().height);
        layoutTree(column);
        column.paint(new BufferedImage(MainPanel.CONTENT_WIDTH, column.getHeight(), BufferedImage.TYPE_INT_ARGB).getGraphics());

        assertEquals(0, card.getX(), "card starts at the left edge of the panel");
        assertEquals(MainPanel.CONTENT_WIDTH, card.getWidth());
        int buttons = 0;
        for (Component child : card.getComponents()) {
            assertTrue(child.getX() + child.getWidth() <= card.getWidth(), "child fits: " + child);
            for (Component c : child instanceof JButton ? new Component[]{child} : ((Container) child).getComponents()) {
                if (!(c instanceof JButton)) {
                    continue;
                }
                buttons++;
                JButton button = (JButton) c;
                int left = child == c ? c.getX() : child.getX() + c.getX();
                assertTrue(left >= 0 && left + c.getWidth() <= card.getWidth() - 8, "inside the card: " + button.getText());
                Insets in = button.getInsets();
                int label = button.getFontMetrics(button.getFont()).stringWidth(button.getText());
                assertTrue(label + in.left + in.right <= c.getWidth(), "label is not cut short: " + button.getText());
            }
        }
        assertEquals(3, buttons);
        assertTrue(card.getHeight() < 360, "text wraps to the panel, not to a narrow column: " + card.getHeight());
    }

    private static void layoutTree(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) {
            if (child instanceof Container) {
                layoutTree((Container) child);
            }
        }
    }
}
