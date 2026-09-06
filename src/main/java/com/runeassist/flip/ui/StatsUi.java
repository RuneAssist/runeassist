package com.runeassist.flip.ui;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

import static com.runeassist.flip.ui.UIUtilities.BUTTON_HOVER_LUMINANCE;

/** Stats panel chrome: toolbar icons, metric cells, empty-state prompts. */
final class StatsUi {
    private StatsUi() {
    }

    static final class IconPair {
        final Icon normal;
        final Icon hover;

        IconPair(Icon normal, Icon hover) {
            this.normal = normal;
            this.hover = hover;
        }
    }

    static IconPair toolbarIcon(Class<?> owner, String path) {
        BufferedImage img = ImageUtil.recolorImage(
                ImageUtil.resizeImage(ImageUtil.loadImageResource(owner, path), 20, 20),
                ColorScheme.LIGHT_GRAY_COLOR);
        return new IconPair(
                new ImageIcon(img),
                new ImageIcon(ImageUtil.luminanceScale(img, BUTTON_HOVER_LUMINANCE)));
    }

    static void shellIconButton(JButton button, IconPair icons, String tip, int rightPad,
                                Consumer<MouseEvent> onClick) {
        button.setIcon(icons.normal);
        button.setOpaque(true);
        button.setEnabled(true);
        button.setFocusable(true);
        button.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, rightPad));
        button.setBackground(RuneAssistColors.SHELL);
        button.setToolTipText(tip);
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                onClick.accept(e);
            }
        });
        UIUtilities.addHoverIcons(button, () -> icons.normal, () -> icons.hover);
    }

    static JPanel metricCell(String caption, JLabel value, Color valueColor, Runnable onClick) {
        JPanel cell = UIUtilities.verticalPanel(RuneAssistColors.CARD);
        cell.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        value.setFont(FontManager.getRunescapeSmallFont());
        value.setForeground(valueColor);
        cell.add(RuneAssistColors.caption(caption));
        cell.add(value);
        cell.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        if (onClick == null) {
            return cell;
        }
        cell.setToolTipText("Open portfolio");
        cell.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                onClick.run();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                cell.setBackground(RuneAssistColors.CARD.brighter());
                cell.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                cell.setBackground(RuneAssistColors.CARD);
                cell.setCursor(Cursor.getDefaultCursor());
            }
        });
        return cell;
    }

    static JPanel historyLinkPrompt() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 4, 10, 4));
        JLabel label = new JLabel("<html><body style='width:180px'>"
                + "Link this client in Settings to enable Recent Flips history "
                + "(device register + OSRS character).</body></html>");
        label.setForeground(RuneAssistColors.MUTED);
        label.setFont(FontManager.getRunescapeSmallFont());
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    static String sessionClock(long durationMillis) {
        long seconds = Math.max(0L, durationMillis / 1000);
        return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }
}
