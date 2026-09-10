package com.runeassist.flip.ui;

import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.model.*;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class CompactSidebarTest {
    @Test void collapsedByDefaultTogglePersistsAndRefreshDoesNotExpandIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RuneAssistConfig config = new RuneAssistConfig() {};
            assertTrue(config.sessionStatsCollapsed());
            AtomicReference<Boolean> saved = new AtomicReference<>();
            JPanel details = new JPanel();
            details.setPreferredSize(new Dimension(230, 240));
            CollapsibleStatsCard card = new CollapsibleStatsCard(details, config.sessionStatsCollapsed(), saved::set);
            assertFalse(details.isVisible());
            assertTrue(card.getPreferredSize().height < 50);
            JButton button = (JButton) card.getComponent(0);
            button.doClick();
            assertTrue(details.isVisible());
            assertEquals(Boolean.FALSE, saved.get());
            assertTrue(card.getPreferredSize().height > 240);
            button.doClick();
            card.setSummary("Session · 12.5K gp", Color.GREEN);
            assertFalse(details.isVisible());
            assertEquals(Boolean.TRUE, saved.get());
            assertTrue(button.getText().contains("12.5K gp"));
            JPanel restoredDetails = new JPanel();
            new CollapsibleStatsCard(restoredDetails, saved.get(), saved::set);
            assertFalse(restoredDetails.isVisible());
        });
    }

    @Test void warningStripHidesWhenNoPositionsNeedAttention() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int[] aged = {0};
            FlipManager manager = new FlipManager(null) {
                @Override public synchronized int countOpenOlderThan(int seconds) { return aged[0]; }
            };
            StatusStrip strip = new StatusStrip(null, manager);
            strip.refresh();
            assertFalse(strip.isVisible());
            aged[0] = 1;
            strip.refresh();
            assertTrue(strip.isVisible());
            assertEquals("1 position > 4h", ((JLabel) strip.getComponent(0)).getText());
            aged[0] = 0;
            strip.refresh();
            assertFalse(strip.isVisible());
        });
    }

    @Test void recentRowsShowSaleTimeAndKeepDetailsClick() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FlipV2 flip = flip("Rune warhammer", 59_700);
            assertTrue(FlipPanel.statusLine(flip).startsWith("Sold 70 · "));
            assertFalse(FlipPanel.statusLine(flip).contains("Time unknown"));
            int[] clicks = {0};
            FlipPanel row = new FlipPanel(flip, new RuneAssistConfig() {}, () -> clicks[0]++);
            row.dispatchEvent(new java.awt.event.MouseEvent(row, java.awt.event.MouseEvent.MOUSE_CLICKED,
                    0, 0, 5, 5, 1, false, java.awt.event.MouseEvent.BUTTON1));
            assertEquals(1, clicks[0]);
            flip.setStatus(FlipStatus.SELLING);
            assertTrue(FlipPanel.statusLine(flip).startsWith("Part sold"));
        });
    }

    @Test void renderCompactRecentFlipsLayout() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                JPanel root = new JPanel(new BorderLayout(0, 8));
                root.setBackground(RuneAssistColors.SHELL);
                JPanel details = new JPanel();
                details.setPreferredSize(new Dimension(230, 240));
                CollapsibleStatsCard card = new CollapsibleStatsCard(details, true, ignored -> {});
                card.setSummary("Session · 72.4K gp", Color.GREEN);
                root.add(card, BorderLayout.NORTH);
                JPanel recent = UIUtilities.verticalPanel(RuneAssistColors.CARD);
                recent.add(RuneAssistColors.kicker("RECENT FLIPS"));
                recent.add(new FlipPanel(flip("Rune warhammer", 59_700), new RuneAssistConfig() {}, () -> {}));
                recent.add(new FlipPanel(flip("Lava dragon bones", -1_800), new RuneAssistConfig() {}, () -> {}));
                root.add(recent, BorderLayout.CENTER);
                root.setSize(230, 160);
                layoutTree(root);
                assertEquals(230, card.getWidth());
                assertTrue(recent.getHeight() > 100);
                BufferedImage image = new BufferedImage(230, 160, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                root.printAll(g);
                g.dispose();
                File output = new File("build/ui-preview/compact-recent-flips.png");
                output.getParentFile().mkdirs();
                ImageIO.write(image, "png", output);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private static FlipV2 flip(String name, long profit) {
        FlipV2 flip = new FlipV2();
        flip.setCachedItemName(name);
        flip.setOpenedTime(1_789_050_000);
        flip.setClosedTime(1_789_053_600);
        flip.setOpenedQuantity(70);
        flip.setClosedQuantity(70);
        flip.setProfit(profit);
        flip.setStatus(FlipStatus.FINISHED);
        return flip;
    }

    private static void layoutTree(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) if (child instanceof Container) layoutTree((Container) child);
    }
}
