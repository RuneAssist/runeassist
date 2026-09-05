package com.runeassist.flip.ui;

import com.runeassist.flip.model.AccountStatus;
import com.runeassist.flip.model.AccountStatusManager;
import com.runeassist.flip.model.FlipManager;
import com.runeassist.flip.model.Offer;
import com.runeassist.flip.model.OfferStatus;
import com.runeassist.flip.model.StatusOfferList;
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
        appendSlots(parts);
        appendAged(parts);
        appendDeployed(parts);
        if (parts.isEmpty()) {
            setVisible(false);
            return;
        }
        setVisible(true);
        setLineText(String.join(" · ", parts));
    }

    private void appendSlots(List<String> parts) {
        AccountStatus status = safeStatus();
        if (status == null || status.getOffers() == null) {
            return;
        }
        int max = status.isWorldMember() || status.isAccountMember()
                ? StatusOfferList.NUM_SLOTS : StatusOfferList.NUM_F2P_SLOTS;
        int used = 0;
        for (Offer offer : status.getOffers()) {
            if (offer != null && offer.getStatus() != OfferStatus.EMPTY) {
                used++;
            }
        }
        parts.add(used + "/" + max + " slots");
    }

    private void appendAged(List<String> parts) {
        if (flipManager == null) {
            return;
        }
        int aged = flipManager.countOpenOlderThan(FlipManager.AGED_OPEN_SECONDS);
        parts.add(aged + " positions > 4h");
    }

    private void appendDeployed(List<String> parts) {
        AccountStatus status = safeStatus();
        if (status == null || status.getOffers() == null || status.getInventory() == null) {
            return;
        }
        long cashStack = status.currentCashStack();
        if (cashStack <= 0) {
            return;
        }
        long onMarket = status.getOffers().getGpOnMarket();
        int pct = (int) Math.round(100.0d * onMarket / (double) cashStack);
        pct = Math.max(0, Math.min(100, pct));
        parts.add(pct + "% deployed");
    }

    private AccountStatus safeStatus() {
        if (accountStatusManager == null) {
            return null;
        }
        try {
            return accountStatusManager.getAccountStatus();
        } catch (Throwable ignored) {
            // Throwable, not RuntimeException: reading the inventory off the client thread
            // raises an AssertionError under -ea, which is an Error and slipped straight past,
            // taking the panel -- and with it plugin startup -- down with it. This strip is
            // decoration; it renders without a status rather than stopping anything else.
            return null;
        }
    }
}
