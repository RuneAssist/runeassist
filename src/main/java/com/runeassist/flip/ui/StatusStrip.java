package com.runeassist.flip.ui;

import com.runeassist.flip.model.AccountStatusManager;
import com.runeassist.flip.model.FlipManager;
import net.runelite.client.ui.FontManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

@Singleton
public class StatusStrip extends JPanel {
    private final AccountStatusManager accountStatusManager;
    private final FlipManager flipManager;
    private final JLabel line = new JLabel(" ");

    @Inject
    public StatusStrip(AccountStatusManager accountStatusManager, FlipManager flipManager) {
        this.accountStatusManager = accountStatusManager;
        this.flipManager = flipManager;
        setLayout(new BorderLayout());
        setBackground(RuneAssistColors.CARD);
        setBorder(RuneAssistColors.cardBorder());
        line.setForeground(RuneAssistColors.TEXT);
        line.setFont(FontManager.getRunescapeSmallFont());
        line.setHorizontalAlignment(SwingConstants.LEFT);
        add(line, BorderLayout.CENTER);
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        setVisible(false);
    }

    public StatusStrip() {
        this(null, null);
    }

    public void setLineText(String text) {
        line.setText(text == null || text.isEmpty() ? " " : text);
    }

    public void refresh() {
        if (!UIUtilities.ensureEdt(this::refresh)) {
            return;
        }
        List<String> parts = new ArrayList<>();
        appendAged(parts);
        if (parts.isEmpty()) {
            setVisible(false);
            return;
        }
        setVisible(true);
        setLineText(String.join(" · ", parts));
    }

    private void appendAged(List<String> parts) {
        if (flipManager == null) {
            return;
        }
        int aged = flipManager.countOpenOlderThan(FlipManager.AGED_OPEN_SECONDS);
        if (aged > 0) parts.add(aged + (aged == 1 ? " position > 4h" : " positions > 4h"));
    }

}
