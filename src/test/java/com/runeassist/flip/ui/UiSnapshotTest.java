package com.runeassist.flip.ui;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders panel pieces at the real side-panel width into build/ui-snapshots so a
 * person (or the reviewer of a pull request) can look at them. CI uploads the
 * folder. Add every new card or header here when it is created.
 */
class UiSnapshotTest {
    private static final File OUT = new File("build/ui-snapshots");

    @Test
    void welcomeCard() throws Exception {
        render("welcome-card", new OnboardingCard(() -> { }, () -> { }));
    }

    @Test
    void settingsTitleIsClearOfTheGear() throws Exception {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(RuneAssistColors.CARD);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 28));
        JLabel title = PrefsUi.sectionTitle("Suggestion Settings");
        title.setBorder(BorderFactory.createEmptyBorder(0, PrefsUi.GEAR_CLEARANCE, 0, 0));
        panel.add(title);
        render("settings-title", panel);
        int gearRight = 6 + 20;
        int textLeft = title.getX() + title.getInsets().left;
        assertTrue(textLeft >= gearRight + 4, "title text starts at " + textLeft + ", gear ends at " + gearRight);
        int textWidth = title.getFontMetrics(title.getFont()).stringWidth(title.getText());
        assertTrue(textLeft + textWidth <= MainPanel.CONTENT_WIDTH - 28, "title fits beside the gear");
    }

    static BufferedImage render(String name, JComponent component) throws Exception {
        JPanel column = new JPanel();
        column.setBackground(RuneAssistColors.SHELL);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.add(component);
        column.setSize(MainPanel.CONTENT_WIDTH, Math.max(24, column.getPreferredSize().height));
        layoutTree(column);
        BufferedImage image = new BufferedImage(column.getWidth(), column.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics g = image.getGraphics();
        column.paint(g);
        g.dispose();
        assertTrue(OUT.isDirectory() || OUT.mkdirs());
        ImageIO.write(image, "png", new File(OUT, name + ".png"));
        return image;
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
