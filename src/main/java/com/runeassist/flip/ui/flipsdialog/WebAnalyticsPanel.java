package com.runeassist.flip.ui.flipsdialog;

import com.runeassist.flip.ui.RuneAssistColors;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.util.function.Consumer;

/**
 * Thin Hub-safe stand-in for FC's Flips / Items / Profit / Missed tabs:
 * deep-links to the website dashboard instead of shipping Swing analytics.
 */
public class WebAnalyticsPanel extends JPanel {

    public WebAnalyticsPanel(Consumer<String> openSection) {
        setLayout(new BorderLayout());
        setBackground(RuneAssistColors.SHELL);

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(RuneAssistColors.CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(RuneAssistColors.HAIRLINE),
                BorderFactory.createEmptyBorder(24, 28, 24, 28)));
        card.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel title = new JLabel("Web analytics");
        title.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
        title.setForeground(RuneAssistColors.ACCENT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel blurb = new JLabel("<html><body style='width:420px'>"
                + "Profit graph, flip history, item breakdown, and idle positions live on the "
                + "RuneAssist dashboard. Open a section below — Portfolio, Price graph, and "
                + "Visualize stay in this dialog.</body></html>");
        blurb.setFont(FontManager.getRunescapeFont());
        blurb.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        blurb.setAlignmentX(Component.LEFT_ALIGNMENT);
        blurb.setBorder(BorderFactory.createEmptyBorder(8, 0, 16, 0));

        card.add(title);
        card.add(blurb);
        card.add(linkRow("Profit graph", "Cumulative / daily profit series",
                () -> openSection.accept(WebAnalyticsLinks.SECTION_PROFIT)));
        card.add(Box.createVerticalStrut(8));
        card.add(linkRow("Flip history", "Closed flips with filters and CSV export",
                () -> openSection.accept(WebAnalyticsLinks.SECTION_FLIPS)));
        card.add(Box.createVerticalStrut(8));
        card.add(linkRow("By item", "Profit and win rate per item",
                () -> openSection.accept(WebAnalyticsLinks.SECTION_ITEMS)));
        card.add(Box.createVerticalStrut(8));
        card.add(linkRow("Needs attention", "Open positions idle ≥ 48h",
                () -> openSection.accept(WebAnalyticsLinks.SECTION_ATTENTION)));
        card.add(Box.createVerticalStrut(8));
        card.add(linkRow("Accounts", "Linked OSRS accounts and pairing",
                () -> openSection.accept(WebAnalyticsLinks.SECTION_ACCOUNTS)));
        card.add(Box.createVerticalStrut(16));

        JButton openAll = new JButton("Open full dashboard");
        RuneAssistColors.stylePrimaryButton(openAll);
        openAll.setAlignmentX(Component.LEFT_ALIGNMENT);
        openAll.setFocusable(false);
        openAll.addActionListener(e -> openSection.accept(null));
        card.add(openAll);

        JPanel center = new JPanel(new GridBagLayout());
        center.setOpaque(false);
        center.add(card);
        add(center, BorderLayout.CENTER);
    }

    private static JPanel linkRow(String label, String hint, Runnable onClick) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        JButton btn = new JButton(label);
        RuneAssistColors.styleGhostButton(btn);
        btn.setFocusable(false);
        btn.addActionListener(e -> onClick.run());
        btn.setPreferredSize(new Dimension(160, 28));

        JLabel caption = RuneAssistColors.caption(hint);
        caption.setForeground(RuneAssistColors.MUTED);

        row.add(btn, BorderLayout.WEST);
        row.add(caption, BorderLayout.CENTER);
        return row;
    }
}
