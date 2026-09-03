package com.runeassist.flip.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

@Singleton
@Slf4j
public class MainPanel extends PluginPanel {

    public static final int CONTENT_WIDTH = 242 - 12;

    public final LoginPanel loginPanel;
    public final CopilotPanel copilotPanel;

    private final CardLayout cardLayout = new CardLayout();

    @Inject
    public MainPanel(CopilotPanel copilotPanel,
                     LoginPanel loginPanel) {
        super(false);
        this.copilotPanel = copilotPanel;
        this.loginPanel = loginPanel;

        setLayout(cardLayout);
        setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
        setBackground(RuneAssistColors.SHELL);
        add(buildView(copilotPanel), "logged-in");
        cardLayout.show(this, "logged-in");
    }

    private JPanel buildView(JComponent content) {
        JPanel wrapper = new JPanel();
        wrapper.setOpaque(true);
        wrapper.setBackground(RuneAssistColors.SHELL);
        wrapper.setLayout(new BorderLayout());
        wrapper.add(constructTopBar(), BorderLayout.NORTH);
        wrapper.add(content, BorderLayout.CENTER);
        return wrapper;
    }

    public void refresh() {
        if (!UIUtilities.ensureEdt(this::refresh)) return;
        cardLayout.show(this, "logged-in");
        copilotPanel.refresh();
    }

    private JPanel constructTopBar() {
        JPanel container = new JPanel(new BorderLayout());
        container.setBackground(RuneAssistColors.SHELL);
        container.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 2, 0, RuneAssistColors.ACCENT),
                new EmptyBorder(2, 0, 8, 0)));

        JLabel title = new JLabel("RuneAssist Flipping");
        title.setForeground(RuneAssistColors.ACCENT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setToolTipText("BSD-2");

        JLabel attribution = new JLabel("BSD-2");
        attribution.setForeground(RuneAssistColors.MUTED);
        attribution.setFont(attribution.getFont().deriveFont(10f));
        attribution.setToolTipText("Based on Flipping Copilot (BSD-2)");
        attribution.setHorizontalAlignment(SwingConstants.RIGHT);

        container.add(title, BorderLayout.WEST);
        container.add(attribution, BorderLayout.EAST);
        return container;
    }
}
