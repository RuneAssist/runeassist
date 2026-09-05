package com.runeassist.flip.ui;

import com.runeassist.flip.controller.FlipHistorySyncService;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

@Singleton
@Slf4j
public class MainPanel extends PluginPanel {

    public static final int CONTENT_WIDTH = 242 - 12;

    public final RuneAssistPanel runeAssistPanel;

    private final FlipHistorySyncService flipHistorySyncService;
    private final CardLayout cardLayout = new CardLayout();
    private final JLabel identityLabel = new JLabel("Not linked");

    @Inject
    public MainPanel(RuneAssistPanel runeAssistPanel, FlipHistorySyncService flipHistorySyncService) {
        super(false);
        this.runeAssistPanel = runeAssistPanel;
        this.flipHistorySyncService = flipHistorySyncService;

        setLayout(cardLayout);
        setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
        setBackground(RuneAssistColors.SHELL);
        add(buildView(runeAssistPanel), "logged-in");
        cardLayout.show(this, "logged-in");

        flipHistorySyncService.addStatusListener(this::refreshIdentity);
        refreshIdentity();
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
        refreshIdentity();
        runeAssistPanel.refresh();
    }

    private void refreshIdentity() {
        if (!UIUtilities.ensureEdt(this::refreshIdentity)) {
            return;
        }
        boolean linked = flipHistorySyncService.isLinked();
        identityLabel.setText(flipHistorySyncService.identityLabel());
        identityLabel.setForeground(linked ? RuneAssistColors.ACCENT : RuneAssistColors.MUTED);
        identityLabel.setToolTipText(flipHistorySyncService.identityDetail()
                + " (BSD-2 based on Flipping Copilot)");
    }

    private JPanel constructTopBar() {
        JPanel container = new JPanel(new BorderLayout());
        container.setBackground(RuneAssistColors.SHELL);
        container.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 2, 0, RuneAssistColors.ACCENT),
                new EmptyBorder(2, 0, 8, 0)));

        JLabel title = new JLabel("RuneAssist");
        title.setForeground(RuneAssistColors.ACCENT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setToolTipText("RuneAssist Flipping · BSD-2");

        identityLabel.setFont(identityLabel.getFont().deriveFont(10f));
        identityLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        identityLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        identityLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                runeAssistPanel.openSettings();
            }
        });

        container.add(title, BorderLayout.WEST);
        container.add(identityLabel, BorderLayout.EAST);
        return container;
    }
}
