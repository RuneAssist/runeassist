package com.runeassist.flip.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;

import static org.junit.jupiter.api.Assertions.*;

class ControlUiLayoutTest {
    @Test void windowRiskCardSpansTheStackAndBothControlsFit() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int width : new int[]{MainPanel.CONTENT_WIDTH, 300}) {
                JPanel stack = new JPanel();
                stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
                JPanel suggestion = new JPanel(new BorderLayout());
                suggestion.setPreferredSize(new Dimension(MainPanel.CONTENT_WIDTH, 148));
                stack.add(suggestion);
                stack.add(Box.createRigidArea(new Dimension(MainPanel.CONTENT_WIDTH, 8)));

                JPanel card = new JPanel();
                ControlUi.configureCardLayout(card);
                card.setBorder(RuneAssistColors.cardBorder());
                JPanel contents = new JPanel();
                contents.setLayout(new BoxLayout(contents, BoxLayout.Y_AXIS));
                contents.setAlignmentX(Component.LEFT_ALIGNMENT);
                contents.add(ControlUi.headerRow(RuneAssistColors.kicker("CHECK-IN INTERVAL / RISK")));
                JPanel strip = ControlUi.leftStrip(28);
                JComboBox<String> window = new JComboBox<>(new String[]{"8h", "Custom"});
                window.setPrototypeDisplayValue("Custom");
                JComboBox<String> risk = new JComboBox<>(new String[]{"Low", "Med", "High"});
                risk.setPrototypeDisplayValue("High");
                ControlUi.styleCompactCombo(window);
                ControlUi.styleCompactCombo(risk);
                strip.add(window);
                strip.add(risk);
                contents.add(strip);
                card.add(contents);
                stack.add(card);
                stack.add(Box.createRigidArea(new Dimension(MainPanel.CONTENT_WIDTH, 8)));
                stack.setSize(width, stack.getPreferredSize().height);
                layoutTree(stack);

                assertEquals(0, card.getX(), "Card must not start halfway across the sidebar");
                assertEquals(width, card.getWidth());
                assertEquals(suggestion.getWidth(), card.getWidth());
                assertEquals(window.getY(), risk.getY(), "Both dropdowns stay on the same row");
                assertTrue(risk.getX() + risk.getWidth() <= strip.getWidth());
                assertTrue(risk.getY() + risk.getHeight() <= strip.getHeight());
            }
        });
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) layoutTree((Container) child);
        }
    }
}
