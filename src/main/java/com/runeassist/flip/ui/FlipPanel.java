package com.runeassist.flip.ui;

import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.model.FlipV2;
import net.runelite.client.ui.FontManager;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import static com.runeassist.flip.util.DateUtil.formatEpoch;

public class FlipPanel extends JPanel {
    private static final Color HOVER_BACKGROUND = RuneAssistColors.CARD.brighter();

    public FlipPanel(FlipV2 flip, RuneAssistConfig config, Runnable onClick) {
        setLayout(new BorderLayout());
        setBackground(RuneAssistColors.CARD);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, RuneAssistColors.HAIRLINE),
                BorderFactory.createEmptyBorder(4, 2, 4, 2)));

        JLabel itemNameLabel = new JLabel(UIUtilities.truncateString(flip.getCachedItemName(), 22));
        itemNameLabel.setForeground(Color.WHITE);
        itemNameLabel.setFont(FontManager.getRunescapeSmallFont());

        JLabel qtyLabel = new JLabel(flip.getClosedQuantity() + " closed");
        qtyLabel.setForeground(RuneAssistColors.MUTED);
        qtyLabel.setFont(FontManager.getRunescapeSmallFont());

        JPanel leftPanel = UIUtilities.verticalPanel(RuneAssistColors.CARD);
        leftPanel.add(itemNameLabel);
        leftPanel.add(qtyLabel);

        JLabel profitLabel = new JLabel(UIUtilities.formatProfitWithoutGp(flip.getProfit()));
        profitLabel.setForeground(UIUtilities.getProfitColor(flip.getProfit(), config));
        profitLabel.setFont(FontManager.getRunescapeSmallFont());
        profitLabel.setVerticalAlignment(SwingConstants.TOP);

        add(leftPanel, BorderLayout.CENTER);
        add(profitLabel, BorderLayout.EAST);
        setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));

        String closeLabel = flip.getClosedQuantity() == flip.getOpenedQuantity() ? "Close time" : "Partial close time";
        long closedCostBasis = flip.getOpenedQuantity() <= 0
                ? 0
                : (flip.getSpent() * flip.getClosedQuantity()) / flip.getOpenedQuantity();
        String roiText = closedCostBasis > 0
                ? String.format("%.2f%%", ((double) flip.getProfit() / (double) closedCostBasis) * 100.0d)
                : "Unknown";
        Color profitColor = UIUtilities.getProfitColor(flip.getProfit(), config);
        String profitColorHex = UIUtilities.colorHex(profitColor);

        String tooltipText = String.format("<html>Profit: <font color='%s'>%s</font><br>ROI: <font color='%s'>%s</font><br>Avg buy price: <font color='#32A0FA'>%s</font><br>Avg sell price: <font color='#F0CF7B'>%s</font><br>Tax paid: <font color='#FFFFFF'>%s</font><br>Opened time: %s<br>%s: %s</html>",
                profitColorHex,
                UIUtilities.formatProfit(flip.getProfit()),
                profitColorHex,
                roiText,
                UIUtilities.formatProfit(flip.getAvgBuyPrice()),
                UIUtilities.formatProfit(flip.getAvgSellPrice()),
                UIUtilities.formatProfit(flip.getTaxPaid()),
                formatEpoch(flip.getOpenedTime()),
                closeLabel,
                formatEpoch(flip.getClosedTime()));
        setToolTipText(tooltipText);
        leftPanel.setToolTipText(tooltipText);
        qtyLabel.setToolTipText(tooltipText);
        itemNameLabel.setToolTipText(tooltipText);
        profitLabel.setToolTipText(tooltipText);

        if (onClick != null) {
            Component[] clickableComponents = {this, leftPanel, qtyLabel, itemNameLabel, profitLabel};
            MouseAdapter clickListener = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    onClick.run();
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    setBackground(HOVER_BACKGROUND);
                    leftPanel.setBackground(HOVER_BACKGROUND);
                    for (Component component : clickableComponents) {
                        component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    setBackground(RuneAssistColors.CARD);
                    leftPanel.setBackground(RuneAssistColors.CARD);
                    for (Component component : clickableComponents) {
                        component.setCursor(Cursor.getDefaultCursor());
                    }
                }
            };

            for (Component component : clickableComponents) {
                component.addMouseListener(clickListener);
            }
        }
    }
}
