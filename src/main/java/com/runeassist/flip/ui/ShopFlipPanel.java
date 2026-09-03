package com.runeassist.flip.ui;

import com.runeassist.flip.ShopFlipService;
import com.runeassist.flip.ShopLiveTracker;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ColorScheme;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

/**
 * NPC shop-flip candidates. Two sections: a static reference list (v1 -- {@link
 * ShopFlipService}, "worth checking sometime", refreshed every ~30 min from wiki data), and a
 * live-confirmed list (v2 -- {@link ShopLiveTracker}, "worth buying right now", only populated
 * while a shop interface is actually open and near its documented base stock).
 */
@Slf4j
@Singleton
public class ShopFlipPanel extends JPanel {
    private static final NumberFormat GP = NumberFormat.getIntegerInstance(Locale.UK);

    private final ShopFlipService shopFlipService;
    private final ShopLiveTracker shopLiveTracker;
    private final ClientThread clientThread;
    private final ScheduledExecutorService executorService;

    private final JLabel statusLabel = new JLabel();
    private final JLabel liveStatusLabel = new JLabel();
    private final JPanel liveList = new JPanel();
    private final JPanel staticList = new JPanel();
    private final Timer liveRefreshTimer;

    @Inject
    public ShopFlipPanel(ShopFlipService shopFlipService,
                          ShopLiveTracker shopLiveTracker,
                          ClientThread clientThread,
                          @Named("runeAssistExecutor") ScheduledExecutorService executorService) {
        this.shopFlipService = shopFlipService;
        this.shopLiveTracker = shopLiveTracker;
        this.clientThread = clientThread;
        this.executorService = executorService;

        setLayout(new BorderLayout());
        setBackground(RuneAssistColors.SHELL);
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Shop flips");
        title.setForeground(RuneAssistColors.ACCENT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(title);
        UIUtilities.addVerticalGap(content, 4);

        JLabel liveHeader = RuneAssistColors.caption("Live (current shop)");
        liveHeader.setForeground(RuneAssistColors.TEXT);
        liveHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(liveHeader);
        liveStatusLabel.setForeground(RuneAssistColors.MUTED);
        liveStatusLabel.setFont(liveStatusLabel.getFont().deriveFont(11f));
        liveStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        liveStatusLabel.setText("Open a shop to check it live.");
        content.add(liveStatusLabel);
        liveList.setOpaque(false);
        liveList.setLayout(new BoxLayout(liveList, BoxLayout.Y_AXIS));
        liveList.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(liveList);
        UIUtilities.addVerticalGap(content, 10);

        JPanel staticHeaderRow = new JPanel(new BorderLayout());
        staticHeaderRow.setOpaque(false);
        staticHeaderRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel staticHeader = RuneAssistColors.caption("Worth checking (base-stock estimate)");
        staticHeader.setForeground(RuneAssistColors.TEXT);
        staticHeaderRow.add(staticHeader, BorderLayout.WEST);
        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> refreshStatic(true));
        staticHeaderRow.add(refreshButton, BorderLayout.EAST);
        content.add(staticHeaderRow);
        statusLabel.setForeground(RuneAssistColors.MUTED);
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(statusLabel);
        staticList.setOpaque(false);
        staticList.setLayout(new BoxLayout(staticList, BoxLayout.Y_AXIS));
        staticList.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(staticList);
        content.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        add(scroll, BorderLayout.CENTER);

        liveRefreshTimer = new Timer(2000, e -> refreshLive());
        liveRefreshTimer.setRepeats(true);
    }

    public void refresh() {
        if (!isShowing()) {
            return;
        }
        if (!liveRefreshTimer.isRunning()) {
            liveRefreshTimer.start();
        }
        refreshLive();
        if (staticList.getComponentCount() == 0) {
            refreshStatic(false);
        }
    }

    public void onHidden() {
        liveRefreshTimer.stop();
    }

    private void refreshLive() {
        clientThread.invokeLater(() -> {
            boolean open = shopLiveTracker.isShopOpen();
            List<Map<String, Object>> confirmed = shopLiveTracker.liveConfirmedCandidates(10);
            SwingUtilities.invokeLater(() -> renderLive(open, confirmed));
        });
    }

    private void refreshStatic(boolean force) {
        if (force) {
            statusLabel.setText("Refreshing…");
        }
        executorService.execute(() -> {
            List<Map<String, Object>> rows = shopFlipService.topShopFlips(40);
            long ageMs = shopFlipService.cacheAgeMs();
            String error = shopFlipService.lastError();
            SwingUtilities.invokeLater(() -> renderStatic(rows, ageMs, error));
        });
    }

    private void renderLive(boolean shopOpen, List<Map<String, Object>> confirmed) {
        liveList.removeAll();
        if (!shopOpen) {
            liveStatusLabel.setText("Open a shop to check it live.");
        } else if (confirmed.isEmpty()) {
            liveStatusLabel.setText("Shop open — nothing here is at/near base stock right now.");
        } else {
            liveStatusLabel.setText(confirmed.size() + " confirmed — buy now:");
            for (Map<String, Object> c : confirmed) {
                liveList.add(row(c, true));
                UIUtilities.addVerticalGap(liveList, 4);
            }
        }
        liveList.revalidate();
        liveList.repaint();
    }

    private void renderStatic(List<Map<String, Object>> rows, long ageMs, String error) {
        staticList.removeAll();
        if (error != null) {
            statusLabel.setText("Error: " + error);
        } else if (rows.isEmpty()) {
            statusLabel.setText("No candidates found yet.");
        } else {
            String age = ageMs < 0 ? "loading" : (ageMs / 60000) + "m ago";
            statusLabel.setText(rows.size() + " candidates — updated " + age);
            for (Map<String, Object> c : rows) {
                staticList.add(row(c, false));
                UIUtilities.addVerticalGap(staticList, 4);
            }
        }
        staticList.revalidate();
        staticList.repaint();
    }

    private JPanel row(Map<String, Object> c, boolean live) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(true);
        panel.setBackground(live ? RuneAssistColors.ACCENT_MUTED : RuneAssistColors.CARD);
        panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JLabel nameLine = new JLabel(String.valueOf(c.get("itemName")) + "  —  " + c.get("direction"));
        nameLine.setForeground(RuneAssistColors.TEXT);
        nameLine.setFont(nameLine.getFont().deriveFont(Font.BOLD, 12f));

        long pay = ((Number) c.get("payPrice")).longValue();
        long receive = ((Number) c.get("receivePrice")).longValue();
        long marginEach = ((Number) c.get("marginEach")).longValue();
        long capQty = ((Number) c.get("capQty")).longValue();
        long estProfit = ((Number) c.get("estProfit")).longValue();
        Object liveQty = c.get("liveQty");

        StringBuilder detail = new StringBuilder();
        detail.append(c.get("shop"))
                .append(" — pay ").append(GP.format(pay))
                .append(", get ").append(GP.format(receive))
                .append(" (+").append(GP.format(marginEach)).append("/ea)");
        JLabel detailLine = RuneAssistColors.caption(detail.toString());
        detailLine.setForeground(RuneAssistColors.MUTED);

        String qtyText = live
                ? "live stock " + liveQty + " — est. +" + GP.format(estProfit) + " gp this run"
                : "base stock " + c.get("stock") + " — up to +" + GP.format(estProfit) + " gp/run (" + capQty + "x)";
        JLabel qtyLine = RuneAssistColors.caption(qtyText);
        qtyLine.setForeground(live ? RuneAssistColors.ACCENT : RuneAssistColors.MUTED);

        panel.add(nameLine);
        panel.add(detailLine);
        panel.add(qtyLine);
        return panel;
    }
}
