package com.runeassist.flip.ui;

import net.runelite.client.util.LinkBrowser;

import javax.swing.*;
import java.awt.*;

/**
 * One-time card above the first suggestion: what the check-in interval means, how
 * cards work, where help lives. "Got it" hides it for good (config onboardingSeen).
 */
public class OnboardingCard extends JPanel {
    private static final String TEXT = "<html>"
            + "<b>Welcome to RuneAssist</b><br>"
            + "1. Set the <b>check-in interval</b> below to how often you come back to your offers. "
            + "It decides which flips you get; it is not a time limit.<br>"
            + "2. You get <b>one card at a time</b>: buy, sell, or abort. Skip a card you don't like; "
            + "the next one is different.<br>"
            + "3. Pair the website in <b>Settings</b> for graphs and open positions, and join the Discord for help."
            + "</html>";

    public OnboardingCard(Runnable onDismiss, Runnable onOpenSettings) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(true);
        setBackground(RuneAssistColors.CARD);
        setBorder(BorderFactory.createCompoundBorder(RuneAssistColors.cardBorder(),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        setAlignmentX(LEFT_ALIGNMENT);

        JLabel text = new JLabel(TEXT);
        text.setForeground(RuneAssistColors.TEXT);
        SuggestionCardText.styleText(text, false);
        text.setAlignmentX(LEFT_ALIGNMENT);
        text.setMaximumSize(new Dimension(MainPanel.CONTENT_WIDTH - 20, Integer.MAX_VALUE));
        add(text);
        add(Box.createRigidArea(new Dimension(0, 8)));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        JButton discord = PrefsUi.ghostAction("Discord", "Help, flip chat, dump alerts and profit-tier roles");
        discord.addActionListener(e -> LinkBrowser.browse(PreferencesPanel.DISCORD_INVITE_URL));
        JButton settings = PrefsUi.ghostAction("Settings", "Pair the website, dump alerts, privacy");
        settings.addActionListener(e -> onOpenSettings.run());
        JButton gotIt = PrefsUi.ghostAction("Got it", "Hide this card");
        RuneAssistColors.stylePrimaryButton(gotIt);
        gotIt.addActionListener(e -> onDismiss.run());
        buttons.add(discord);
        buttons.add(settings);
        buttons.add(gotIt);
        add(buttons);
        setMaximumSize(new Dimension(MainPanel.CONTENT_WIDTH, getPreferredSize().height + 16));
    }
}
