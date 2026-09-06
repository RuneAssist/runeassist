package com.runeassist.flip.ui;

import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;
import java.util.function.Predicate;

/** Shared Preferences chrome builders (section headers, action rows, option combos). */
final class PrefsUi {
    private PrefsUi() {
    }

    static JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setHorizontalAlignment(SwingConstants.LEFT);
        return label;
    }

    static JButton ghostAction(String text, String tip) {
        JButton button = new JButton(text);
        button.setToolTipText(tip);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        button.setPreferredSize(new Dimension(MainPanel.CONTENT_WIDTH - 40, 28));
        RuneAssistColors.styleGhostButton(button);
        return button;
    }

    static JPanel addRow(JPanel parent, String label, Component control, int gapAfter) {
        JPanel row = UIUtilities.formRow(label, control);
        parent.add(row);
        if (gapAfter > 0) {
            UIUtilities.addVerticalGap(parent, gapAfter);
        }
        return row;
    }

    static void stretchWidth(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        int height = Math.max(component.getPreferredSize().height, 16);
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    }

    static String wrapHtml(String body, int widthPx) {
        return "<html><body style='width:" + widthPx + "px'>" + body + "</body></html>";
    }

    static JScrollPane verticalScroll(JPanel content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(true);
        scroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(12);
        return scroll;
    }

    static JPanel scrollBody() {
        return new ScrollBody();
    }

    static Option option(String label, Number value) {
        return new Option(label, value);
    }

    static Option find(JComboBox<Option> box, Predicate<Option> match, int fallbackIndex) {
        for (int i = 0; i < box.getItemCount(); i++) {
            Option option = box.getItemAt(i);
            if (match.test(option)) {
                return option;
            }
        }
        return box.getItemAt(fallbackIndex);
    }

    static Option findByValue(JComboBox<Option> box, Number value, int fallbackIndex) {
        return find(box, o -> Objects.equals(o.value, value), fallbackIndex);
    }

    static Option findByLong(JComboBox<Option> box, long value, long defaultValue, int fallbackIndex) {
        Option fallback = null;
        for (int i = 0; i < box.getItemCount(); i++) {
            Option option = box.getItemAt(i);
            if (option.value == null) {
                continue;
            }
            if (option.value.longValue() == value) {
                return option;
            }
            if (option.value.longValue() == defaultValue) {
                fallback = option;
            }
        }
        return fallback != null ? fallback : box.getItemAt(fallbackIndex);
    }

    static final class Option {
        final String label;
        final Number value;

        Option(String label, Number value) {
            this.label = label;
            this.value = value;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class ScrollBody extends JPanel implements Scrollable {
        ScrollBody() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(true);
            setBackground(ColorScheme.DARKER_GRAY_COLOR);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 12;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(12, visible.height - 12);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
