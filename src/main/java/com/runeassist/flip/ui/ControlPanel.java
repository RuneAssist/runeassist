package com.runeassist.flip.ui;

import com.runeassist.flip.model.*;
import com.runeassist.flip.rs.AccountSuggestionPreferencesRS;
import net.runelite.client.ui.ColorScheme;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.event.ChangeEvent;
import java.awt.*;
import java.awt.event.*;
import java.util.Locale;

@Singleton
public class ControlPanel extends JPanel {
    private static final int MIN_MINUTES = 1;
    private static final int MAX_MINUTES = 24 * 60;
    private static final int STEPS = 1000;
    private static final int PRESET_5M = 5;
    private static final int PRESET_30M = 30;
    private static final int PRESET_2H = 2 * 60;
    private static final int PRESET_8H = 8 * 60;
    private static final String[] TIMEFRAME_ITEMS = {"5m", "30m", "2h", "8h", "Custom"};
    private static final String VOLUME_TOOLTIP =
            "Sizes how much 5m/1h volume each offer covers. This is not a reprice timer.";
    private static final String RISK_LOW_LABEL = "Low";
    private static final String RISK_MEDIUM_LABEL = "Med";
    private static final String RISK_HIGH_LABEL = "High";
    private static final double SQRT_MIN = Math.sqrt(MIN_MINUTES);
    private static final double SQRT_MAX = Math.sqrt(MAX_MINUTES);
    private static final double SQRT_RANGE = SQRT_MAX - SQRT_MIN;

    private final SuggestionManager suggestionManager;
    private final SuggestionPreferencesManager preferencesManager;
    private final JPanel timeframePanel;
    private final JComboBox<String> timeframeCombo;
    private final JComboBox<String> riskCombo;
    private final JSlider timeframeSlider;
    private final JLabel valueLabel;
    private final JTextField valueEditor;
    private final JPanel customPanel;
    private final JPanel sliderRow;

    private boolean suppressTimeframeSliderEvents;
    private boolean suppressComboEvents;
    private boolean customExplicitlySelected;
    private boolean editingCustomValue;
    private int editingOriginalMinutes;

    @Inject
    public ControlPanel(
            SuggestionManager suggestionManager,
            SuggestionPreferencesManager preferencesManager,
            AccountSuggestionPreferencesRS accountSuggestionPreferencesRS) {
        this.suggestionManager = suggestionManager;
        this.preferencesManager = preferencesManager;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(RuneAssistColors.CARD);
        setBorder(RuneAssistColors.cardBorder());

        timeframePanel = new JPanel();
        timeframePanel.setLayout(new BoxLayout(timeframePanel, BoxLayout.Y_AXIS));
        timeframePanel.setOpaque(false);
        timeframePanel.setAlignmentX(LEFT_ALIGNMENT);

        JLabel settingsLabel = RuneAssistColors.kicker("WINDOW / RISK");
        settingsLabel.setHorizontalAlignment(SwingConstants.LEFT);
        settingsLabel.setToolTipText(VOLUME_TOOLTIP);
        settingsLabel.setAlignmentX(LEFT_ALIGNMENT);
        timeframePanel.add(ControlUi.headerRow(settingsLabel));
        UIUtilities.addVerticalGap(timeframePanel, 4);

        // FlowLayout keeps preferred combo widths so values aren't clipped in a narrow side panel.
        JPanel strip = ControlUi.leftStrip(28);
        strip.setToolTipText(VOLUME_TOOLTIP);

        int initMinutes = clampMinutes(preferencesManager.getTimeframe());
        customExplicitlySelected = !isPreset(initMinutes);

        timeframeCombo = new JComboBox<>(TIMEFRAME_ITEMS);
        timeframeCombo.setPrototypeDisplayValue("Custom");
        ControlUi.styleCompactCombo(timeframeCombo);
        timeframeCombo.setToolTipText(VOLUME_TOOLTIP);
        timeframeCombo.addActionListener(e -> {
            if (!suppressComboEvents) {
                onTimeframeComboChanged();
            }
        });

        RiskLevel initialRiskLevel = preferencesManager.getRiskLevel();
        if (initialRiskLevel == null) {
            initialRiskLevel = RiskLevel.MEDIUM;
            preferencesManager.setRiskLevel(initialRiskLevel);
        }
        riskCombo = new JComboBox<>(new String[]{RISK_LOW_LABEL, RISK_MEDIUM_LABEL, RISK_HIGH_LABEL});
        riskCombo.setPrototypeDisplayValue(RISK_HIGH_LABEL);
        ControlUi.styleCompactCombo(riskCombo);
        riskCombo.setToolTipText("Risk level for suggested flips");
        riskCombo.addActionListener(e -> {
            if (!suppressComboEvents) {
                applyRiskLevel(riskFromCombo());
            }
        });

        strip.add(timeframeCombo);
        strip.add(riskCombo);
        timeframePanel.add(strip);

        customPanel = new JPanel();
        customPanel.setLayout(new BoxLayout(customPanel, BoxLayout.Y_AXIS));
        customPanel.setOpaque(false);
        customPanel.setAlignmentX(LEFT_ALIGNMENT);
        UIUtilities.addVerticalGap(customPanel, 8);

        timeframeSlider = new JSlider(JSlider.HORIZONTAL, 0, STEPS, minutesToPos(initMinutes));
        timeframeSlider.setOpaque(false);
        timeframeSlider.setPaintTicks(false);
        timeframeSlider.setPaintLabels(false);
        timeframeSlider.setSnapToTicks(false);
        timeframeSlider.setPreferredSize(new Dimension(MainPanel.CONTENT_WIDTH - 100, 24));
        timeframeSlider.setMinimumSize(new Dimension(100, 24));
        timeframeSlider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

        valueLabel = new JLabel(formatMinutes(initMinutes), SwingConstants.RIGHT);
        valueLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        valueLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        valueLabel.setToolTipText("Click to enter a custom time interval");

        FontMetrics fm = valueLabel.getFontMetrics(valueLabel.getFont());
        Dimension fixed = new Dimension(fm.stringWidth("24h 00m"), fm.getHeight());
        valueLabel.setMinimumSize(fixed);
        valueLabel.setPreferredSize(fixed);
        valueLabel.setMaximumSize(fixed);

        valueEditor = new JTextField();
        valueEditor.setHorizontalAlignment(JTextField.RIGHT);
        valueEditor.setMinimumSize(fixed);
        valueEditor.setPreferredSize(fixed);
        valueEditor.setMaximumSize(fixed);
        valueEditor.setBackground(ColorScheme.DARK_GRAY_COLOR);
        valueEditor.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        valueEditor.setCaretColor(RuneAssistColors.ACCENT);
        valueEditor.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ColorScheme.DARKER_GRAY_COLOR),
                BorderFactory.createEmptyBorder(0, 4, 0, 4)));

        valueLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
                    beginCustomTimeEditing();
                }
            }
        });
        valueEditor.addActionListener(e -> commitCustomTime(true));
        valueEditor.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (editingCustomValue && !commitCustomTime(false)) {
                    cancelCustomTimeEditing();
                }
            }
        });
        valueEditor.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel-edit");
        valueEditor.getActionMap().put("cancel-edit", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cancelCustomTimeEditing();
            }
        });

        sliderRow = new JPanel(new BorderLayout(8, 0));
        sliderRow.setOpaque(false);
        sliderRow.add(timeframeSlider, BorderLayout.CENTER);
        sliderRow.add(valueLabel, BorderLayout.EAST);

        timeframeSlider.addChangeListener((ChangeEvent e) -> {
            int minutesPreview = posToMinutes(timeframeSlider.getValue());
            updateValueLabel(minutesPreview);
            if (suppressTimeframeSliderEvents) {
                return;
            }
            if (!timeframeSlider.getValueIsAdjusting()) {
                applyTimeframe(minutesPreview, false);
                if (!isPreset(minutesPreview)) {
                    customExplicitlySelected = true;
                    setTimeframeComboSelection("Custom");
                }
            }
        });

        customPanel.add(sliderRow);
        timeframePanel.add(customPanel);
        add(timeframePanel);

        refresh();
        accountSuggestionPreferencesRS.registerListener(ignored -> refresh());
    }

    private static int minutesToPos(int minutes) {
        double t = (Math.sqrt(clampMinutes(minutes)) - SQRT_MIN) / SQRT_RANGE;
        return Math.max(0, Math.min(STEPS, (int) Math.round(t * STEPS)));
    }

    private static int posToMinutes(int pos) {
        double t = (double) Math.max(0, Math.min(STEPS, pos)) / STEPS;
        return clampMinutes((int) Math.round(Math.pow(SQRT_MIN + t * SQRT_RANGE, 2)));
    }

    private static int clampMinutes(int m) {
        return Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, m));
    }

    private static boolean isPreset(int minutes) {
        return minutes == PRESET_5M || minutes == PRESET_30M || minutes == PRESET_2H || minutes == PRESET_8H;
    }

    private String formatMinutes(int m) {
        if (m < 60) {
            return m + "m";
        }
        if (m % 60 == 0) {
            return (m / 60) + "h";
        }
        int mins = m % 60;
        return (m / 60) + "h " + (mins < 10 ? "0" + mins : mins) + "m";
    }

    private void updateValueLabel(int minutes) {
        valueLabel.setText(formatMinutes(minutes));
    }

    private void updateCustomVisibility() {
        boolean show = customExplicitlySelected || "Custom".equals(timeframeCombo.getSelectedItem());
        customPanel.setVisible(show);
        customPanel.revalidate();
        customPanel.repaint();
        timeframePanel.revalidate();
        timeframePanel.repaint();
    }

    private void beginCustomTimeEditing() {
        if (editingCustomValue) {
            return;
        }
        editingCustomValue = true;
        editingOriginalMinutes = posToMinutes(timeframeSlider.getValue());
        sliderRow.remove(valueLabel);
        sliderRow.add(valueEditor, BorderLayout.EAST);
        sliderRow.revalidate();
        sliderRow.repaint();
        valueEditor.setText(formatMinutes(editingOriginalMinutes));
        valueEditor.selectAll();
        valueEditor.requestFocusInWindow();
    }

    private boolean commitCustomTime(boolean showError) {
        Integer minutes = CustomTimeParse.minutes(valueEditor.getText());
        if (minutes == null) {
            if (showError) {
                JOptionPane.showMessageDialog(this,
                        "Couldn't understand that time. Try 90m, 1h 30m, or 1:30.",
                        "Invalid custom time",
                        JOptionPane.ERROR_MESSAGE);
                valueEditor.requestFocusInWindow();
                valueEditor.selectAll();
            }
            return false;
        }
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
            if (showError) {
                JOptionPane.showMessageDialog(this,
                        String.format(Locale.ROOT, "Time must be between %dm and %dh 00m.", MIN_MINUTES, MAX_MINUTES / 60),
                        "Invalid custom time",
                        JOptionPane.ERROR_MESSAGE);
                valueEditor.requestFocusInWindow();
                valueEditor.selectAll();
            }
            return false;
        }
        applyTimeframe(minutes, true);
        restoreValueLabelComponent();
        return true;
    }

    private void cancelCustomTimeEditing() {
        if (!editingCustomValue) {
            return;
        }
        updateValueLabel(posToMinutes(timeframeSlider.getValue()));
        restoreValueLabelComponent();
    }

    private void restoreValueLabelComponent() {
        sliderRow.remove(valueEditor);
        sliderRow.add(valueLabel, BorderLayout.EAST);
        sliderRow.revalidate();
        sliderRow.repaint();
        editingCustomValue = false;
    }

    private void updateRiskCombo(RiskLevel level) {
        RiskLevel effective = level != null ? level : RiskLevel.MEDIUM;
        String label;
        Color accent;
        switch (effective) {
            case LOW:
                label = RISK_LOW_LABEL;
                accent = RuneAssistColors.RISK_LOW;
                break;
            case HIGH:
                label = RISK_HIGH_LABEL;
                accent = RuneAssistColors.RISK_HIGH;
                break;
            case MEDIUM:
            default:
                label = RISK_MEDIUM_LABEL;
                accent = RuneAssistColors.ACCENT;
                break;
        }
        suppressComboEvents = true;
        try {
            riskCombo.setSelectedItem(label);
        } finally {
            suppressComboEvents = false;
        }
        riskCombo.setForeground(accent);
    }

    private RiskLevel riskFromCombo() {
        Object selected = riskCombo.getSelectedItem();
        if (RISK_LOW_LABEL.equals(selected)) {
            return RiskLevel.LOW;
        }
        if (RISK_HIGH_LABEL.equals(selected)) {
            return RiskLevel.HIGH;
        }
        return RiskLevel.MEDIUM;
    }

    private void onTimeframeComboChanged() {
        Object selected = timeframeCombo.getSelectedItem();
        if ("Custom".equals(selected)) {
            customExplicitlySelected = true;
            int current = clampMinutes(preferencesManager.getTimeframe());
            suppressTimeframeSliderEvents = true;
            timeframeSlider.setValue(minutesToPos(current));
            suppressTimeframeSliderEvents = false;
            updateValueLabel(current);
            updateCustomVisibility();
            return;
        }
        customExplicitlySelected = false;
        int minutes = PRESET_5M;
        if ("30m".equals(selected)) {
            minutes = PRESET_30M;
        } else if ("2h".equals(selected)) {
            minutes = PRESET_2H;
        } else if ("8h".equals(selected)) {
            minutes = PRESET_8H;
        }
        applyTimeframe(minutes, true);
    }

    private void setTimeframeComboSelection(String item) {
        suppressComboEvents = true;
        try {
            timeframeCombo.setSelectedItem(item);
        } finally {
            suppressComboEvents = false;
        }
    }

    private void applyRiskLevel(RiskLevel level) {
        RiskLevel effective = level != null ? level : RiskLevel.MEDIUM;
        preferencesManager.setRiskLevel(effective);
        suggestionManager.setSuggestionNeeded(true);
        updateRiskCombo(effective);
    }

    private void applyTimeframe(int minutes, boolean updateSlider) {
        preferencesManager.setTimeframe(minutes);
        suggestionManager.setSuggestionNeeded(true);
        if (updateSlider) {
            try {
                suppressTimeframeSliderEvents = true;
                timeframeSlider.setValue(minutesToPos(minutes));
            } finally {
                suppressTimeframeSliderEvents = false;
            }
            updateValueLabel(minutes);
        }
        syncTimeframeCombo(minutes);
        updateCustomVisibility();
    }

    private void syncTimeframeCombo(int minutes) {
        if (!isPreset(minutes)) {
            customExplicitlySelected = true;
        }
        if (customExplicitlySelected) {
            setTimeframeComboSelection("Custom");
        } else if (minutes == PRESET_5M) {
            setTimeframeComboSelection("5m");
        } else if (minutes == PRESET_30M) {
            setTimeframeComboSelection("30m");
        } else if (minutes == PRESET_2H) {
            setTimeframeComboSelection("2h");
        } else if (minutes == PRESET_8H) {
            setTimeframeComboSelection("8h");
        } else {
            customExplicitlySelected = true;
            setTimeframeComboSelection("Custom");
        }
    }

    public void refresh() {
        if (!UIUtilities.ensureEdt(this::refresh)) return;
        int tf = clampMinutes(preferencesManager.getTimeframe());
        syncTimeframeCombo(tf);
        try {
            suppressTimeframeSliderEvents = true;
            timeframeSlider.setValue(minutesToPos(tf));
        } finally {
            suppressTimeframeSliderEvents = false;
        }
        updateValueLabel(tf);
        updateRiskCombo(preferencesManager.getRiskLevel());
        updateCustomVisibility();
    }
}
