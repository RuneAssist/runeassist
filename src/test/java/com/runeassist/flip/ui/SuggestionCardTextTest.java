package com.runeassist.flip.ui;

import org.junit.jupiter.api.Test;
import java.awt.Font;
import javax.swing.JLabel;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import net.runelite.client.ui.FontManager;
import static org.junit.jupiter.api.Assertions.*;

class SuggestionCardTextTest {
    @Test void centeredInstructionEscapesNamesAndHighlightsTradeNumbers() {
        String html = SuggestionCardText.instruction("Buy and hold", "Rune <warhammer>",70,23283,true);
        assertTrue(html.contains("<center>Buy and hold"));
        assertTrue(html.contains("#FFFF00'>70"));
        assertTrue(html.contains("23,283"));
        assertTrue(html.contains("Rune &lt;warhammer&gt;"));
        assertFalse(SuggestionCardText.instruction("Abort offer","Item",1,100,false).contains("for "));
    }

    @Test void renderCenteredTradeBodyAtReferenceSize() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                JLabel text = new JLabel(SuggestionCardText.instruction("Buy and hold","Rune warhammer",70,23283,true));
                text.setFont(FontManager.getRunescapeFont());
                SuggestionCardText.styleText(text,false);
                JLabel profit = new JLabel("<html><font color='#00ff00'>59.7K</font> profit in ~9h 0m</html>");
                profit.setFont(text.getFont());
                profit.setForeground(RuneAssistColors.TEXT);
                JLabel icon = new JLabel();
                String iconPath = System.getenv("RUNEASSIST_PREVIEW_ICON");
                if (iconPath != null) icon.setIcon(new ImageIcon(ImageIO.read(new File(iconPath))));
                JPanel body = SuggestionCardText.tradeBody(icon,text,profit);
                JPanel card = new JPanel(new BorderLayout());
                card.setBackground(RuneAssistColors.CARD);
                card.setBorder(BorderFactory.createEmptyBorder(8,8,16,8));
                card.add(body,BorderLayout.CENTER);
                JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER,14,0));
                controls.setOpaque(false);
                controls.setBorder(BorderFactory.createEmptyBorder(4,0,0,0));
                for (String resource : new String[]{"graph","pie-chart","pause","block","skip"}) {
                    JLabel button = new JLabel(new ImageIcon(getClass().getResource("/"+resource+".png")));
                    button.setPreferredSize(new Dimension(22,22));
                    controls.add(button);
                }
                card.add(controls,BorderLayout.SOUTH);
                card.setSize(237,149);
                layoutTree(card);
                assertTrue(text.getWidth() > 0);
                assertTrue(text.getHeight() >= text.getPreferredSize().height,"instruction must not clip");
                assertTrue(profit.getHeight() >= profit.getPreferredSize().height,"profit must not clip");
                if (iconPath != null) {
                    BufferedImage capture = new BufferedImage(237,149,BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = capture.createGraphics();
                    card.printAll(graphics);
                    JLabel gear = UIUtilities.gearButton("Settings", () -> {});
                    gear.setSize(20,20);
                    graphics.translate(6,6);
                    gear.printAll(graphics);
                    graphics.dispose();
                    File destination = new File("build/visual-qa/centered-trade.png");
                    destination.getParentFile().mkdirs();
                    ImageIO.write(capture,"png",destination);
                }
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if(child instanceof Container) layoutTree((Container)child);
    }

    @Test void cardTextHasExplicitReadableSizesEvenWithATinyInheritedFont() {
        JLabel label = new JLabel("Waiting for a suitable flip");
        label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 8));
        SuggestionCardText.styleText(label, false);
        assertEquals(16, label.getFont().getSize());
        assertFalse(label.getFont().isBold());
        SuggestionCardText.styleText(label, true);
        assertEquals(16, label.getFont().getSize());
        assertTrue(label.getFont().isBold());
    }

    @Test void profitAndSizingReasonsStayOutOfTheMainCard() {
        assertEquals("Waiting for a suitable flip", SuggestionCardText.waitStatus(
                "Available safe batches fall below the 20,000 gp profit target."));
        assertEquals("Waiting for a suitable flip", SuggestionCardText.waitStatus(
                "No safely sized batch fits the available slot budget and liquidity."));
        assertEquals("Waiting for a suitable flip", SuggestionCardText.waitStatus(null));
    }

    @Test void actionableWaitStatesRemainDistinct() {
        assertEquals("Waiting for offers to fill", SuggestionCardText.waitStatus("All GE slots are full."));
        assertEquals("More coins needed", SuggestionCardText.waitStatus("Not enough coins for the next flip."));
        assertEquals("Waiting for buy limits", SuggestionCardText.waitStatus("Buy limits exhausted."));
        assertEquals("Connection unavailable", SuggestionCardText.waitStatus("Ares is unreachable — no flip candidates."));
        assertEquals("Waiting for fresh prices", SuggestionCardText.waitStatus("Market data is temporarily unavailable."));
    }

    @Test void detailsAreRetainedEscapedAndDeduplicated() {
        assertEquals("Reason<br>4/8 slots", SuggestionCardText.details("Reason", "4/8 slots"));
        assertEquals("Reason", SuggestionCardText.details("Reason", "Reason"));
        assertEquals("&lt;html&gt; &amp;", SuggestionCardText.details(null, "<html> &"));
        assertEquals("", SuggestionCardText.details(null, null));
    }
}
