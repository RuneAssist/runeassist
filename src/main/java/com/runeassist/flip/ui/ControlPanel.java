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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Singleton
public class ControlPanel extends JPanel
{
    private static final int MIN_MINUTES = 1;
    private static final int MAX_MINUTES = 24 * 60;     // 1440

    private static final int STEPS = 1000;              // internal slider resolution

    // Presets
    private static final int PRESET_5M  = 5;
    private static final int PRESET_30M = 30;
    private static final int PRESET_2H  = 2 * 60;
    private static final int PRESET_8H  = 8 * 60;

    private static final String[] TIMEFRAME_ITEMS = {"5m", "30m", "2h", "8h", "Custom"};
    private static final String VOLUME_TOOLTIP =
            "Sizes how much 5m/1h volume each offer covers. This is not a reprice timer.";

    private final SuggestionManager suggestionManager;
    private final SuggestionPreferencesManager preferencesManager;
    private final JPanel timeframePanel;
    private final JComboBox<String> timeframeCombo;
    private final JComboBox<String> riskCombo;

    private boolean suppressTimeframeSliderEvents;
    private boolean suppressComboEvents;
    private boolean customExplicitlySelected;

    private static final Color RISK_LOW_SELECTED_COLOR = RuneAssistColors.RISK_LOW;
    private static final Color RISK_HIGH_SELECTED_COLOR = RuneAssistColors.RISK_HIGH;
    private static final String RISK_LOW_LABEL = "Low";
    private static final String RISK_MEDIUM_LABEL = "Med";
    private static final String RISK_HIGH_LABEL = "High";

    private final JSlider timeframeSlider;
    private final JLabel valueLabel; // fixed-size text showing selected time
    private final JTextField valueEditor; // temporary editor shown during inline edits
    private final JPanel customPanel; // contains only the slider row (no label)
    private final JPanel sliderRow;
    private boolean editingCustomValue;
    private int editingOriginalMinutes;

    // Sqrt domain precomputed
    private static final double SQRT_MIN = Math.sqrt(MIN_MINUTES);
    private static final double SQRT_MAX = Math.sqrt(MAX_MINUTES);
    private static final double SQRT_RANGE = SQRT_MAX - SQRT_MIN;
    private static final Pattern TIME_TOKEN_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(h|hr|hrs|hour|hours|m|min|mins|minute|minutes)?", Pattern.CASE_INSENSITIVE);

    @Inject
    public ControlPanel(
            SuggestionManager suggestionManager,
            SuggestionPreferencesManager preferencesManager,
            AccountSuggestionPreferencesRS accountSuggestionPreferencesRS)
    {
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
        JPanel settingsHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        settingsHeader.setOpaque(false);
        settingsHeader.setAlignmentX(LEFT_ALIGNMENT);
        settingsHeader.add(settingsLabel);
        timeframePanel.add(settingsHeader);
        UIUtilities.addVerticalGap(timeframePanel, 4);

        // FlowLayout, not GridLayout: equal halves are narrower than the longest label needs, so
        // the risk value truncated to "M..." while the shorter window value fitted. FlowLayout
        // gives each combo its preferred width -- set from a prototype below -- and wraps rather
        // than clipping if the panel is ever too narrow for both.
        JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        strip.setOpaque(false);
        strip.setAlignmentX(LEFT_ALIGNMENT);
        strip.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        strip.setToolTipText(VOLUME_TOOLTIP);

        int initMinutes = clampMinutes(preferencesManager.getTimeframe());
        customExplicitlySelected = !isPreset(initMinutes);

        timeframeCombo = new JComboBox<>(TIMEFRAME_ITEMS);
        timeframeCombo.setPrototypeDisplayValue("Custom");
        styleCompactCombo(timeframeCombo);
        timeframeCombo.setToolTipText(VOLUME_TOOLTIP);
        timeframeCombo.addActionListener(e -> {
            if (suppressComboEvents)
            {
                return;
            }
            onTimeframeComboChanged();
        });

        RiskLevel initialRiskLevel = preferencesManager.getRiskLevel();
        if (initialRiskLevel == null)
        {
            initialRiskLevel = RiskLevel.MEDIUM;
            preferencesManager.setRiskLevel(initialRiskLevel);
        }
        riskCombo = new JComboBox<>(new String[]{RISK_LOW_LABEL, RISK_MEDIUM_LABEL, RISK_HIGH_LABEL});
        riskCombo.setPrototypeDisplayValue(RISK_HIGH_LABEL);
        styleCompactCombo(riskCombo);
        riskCombo.setToolTipText("Risk level for suggested flips");
        riskCombo.addActionListener(e -> {
            if (suppressComboEvents)
            {
                return;
            }
            applyRiskLevel(riskFromCombo());
        });

        // No inline "Window"/"Risk" captions: the section is already headed WINDOW / RISK, and in
        // a ~225px side panel split into two cells the caption took most of the width, leaving the
        // combo too narrow to show its own value -- both rendered as an ellipsis rather than the
        // selected "30m" and "Med". The order matches the heading, and both carry tooltips.
        timeframeCombo.setToolTipText(VOLUME_TOOLTIP);
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

        String widest = "24h 00m";
        FontMetrics fm = valueLabel.getFontMetrics(valueLabel.getFont());
        int labelWidth = fm.stringWidth(widest);
        int labelHeight = fm.getHeight();
        Dimension fixed = new Dimension(labelWidth, labelHeight);
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

        valueLabel.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() != 1)
                {
                    return;
                }
                beginCustomTimeEditing();
            }
        });

        valueEditor.addActionListener(e -> commitCustomTime(true));
        valueEditor.addFocusListener(new FocusAdapter()
        {
            @Override
            public void focusLost(FocusEvent e)
            {
                if (!editingCustomValue)
                {
                    return;
                }
                if (!commitCustomTime(false))
                {
                    cancelCustomTimeEditing();
                }
            }
        });

        InputMap inputMap = valueEditor.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = valueEditor.getActionMap();
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel-edit");
        actionMap.put("cancel-edit", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
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
            if (suppressTimeframeSliderEvents)
            {
                return;
            }
            if (!timeframeSlider.getValueIsAdjusting())
            {
                applyTimeframe(minutesPreview, /*updateSlider*/ false);
                if (!isPreset(minutesPreview)) {
                    customExplicitlySelected = true;
                    setTimeframeComboSelection("Custom");
                }
            }
        });

        customPanel.add(sliderRow);
        timeframePanel.add(customPanel);

        add(timeframePanel);

        // Initial sync & visibility
        refresh();

        accountSuggestionPreferencesRS.registerListener(ignored -> refresh());
    }

    // ---------- Mapping between slider position (0..STEPS) and minutes (1..1440) using √t ----------
    private static int minutesToPos(int minutes)
    {
        int m = clampMinutes(minutes);
        double root = Math.sqrt(m);
        double t = (root - SQRT_MIN) / SQRT_RANGE; // 0..1 uniform in sqrt space
        int pos = (int) Math.round(t * STEPS);
        return Math.max(0, Math.min(STEPS, pos));
    }

    private static int posToMinutes(int pos)
    {
        int p = Math.max(0, Math.min(STEPS, pos));
        double t = (double) p / (double) STEPS;   // 0..1
        double root = SQRT_MIN + t * SQRT_RANGE;
        int m = (int) Math.round(root * root);
        return clampMinutes(m);
    }

    private static int clampMinutes(int m)
    {
        return Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, m));
    }

    private static boolean isPreset(int minutes)
    {
        return minutes == PRESET_5M || minutes == PRESET_30M || minutes == PRESET_2H || minutes == PRESET_8H;
    }

    private String formatMinutes(int m)
    {
        if (m < 60) return m + "m";
        if (m % 60 == 0) return (m / 60) + "h";
        int mins = m % 60;
        String mm = mins < 10 ? ("0" + mins) : String.valueOf(mins);
        return (m / 60) + "h " + mm + "m";
    }

    private void updateValueLabel(int minutes)
    {
        valueLabel.setText(formatMinutes(minutes));
    }

    private void updateCustomVisibility()
    {
        boolean show = customExplicitlySelected || "Custom".equals(timeframeCombo.getSelectedItem());
        customPanel.setVisible(show);
        customPanel.revalidate();
        customPanel.repaint();
        timeframePanel.revalidate();
        timeframePanel.repaint();
    }

    private void beginCustomTimeEditing()
    {
        if (editingCustomValue)
        {
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

    private boolean commitCustomTime(boolean showError)
    {
        Integer minutes = parseCustomTimeMinutes(valueEditor.getText());
        if (minutes == null)
        {
            if (showError)
            {
                JOptionPane.showMessageDialog(this,
                        "Couldn't understand that time. Try 90m, 1h 30m, or 1:30.",
                        "Invalid custom time",
                        JOptionPane.ERROR_MESSAGE);
                valueEditor.requestFocusInWindow();
                valueEditor.selectAll();
            }
            return false;
        }

        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES)
        {
            if (showError)
            {
                JOptionPane.showMessageDialog(this,
                        String.format(Locale.ROOT, "Time must be between %dm and %dh 00m.", MIN_MINUTES, MAX_MINUTES / 60),
                        "Invalid custom time",
                        JOptionPane.ERROR_MESSAGE);
                valueEditor.requestFocusInWindow();
                valueEditor.selectAll();
            }
            return false;
        }

        applyTimeframe(minutes, /*updateSlider*/ true);
        restoreValueLabelComponent();
        return true;
    }

    private void cancelCustomTimeEditing()
    {
        if (!editingCustomValue)
        {
            return;
        }

        updateValueLabel(posToMinutes(timeframeSlider.getValue()));
        restoreValueLabelComponent();
    }

    private void restoreValueLabelComponent()
    {
        sliderRow.remove(valueEditor);
        sliderRow.add(valueLabel, BorderLayout.EAST);
        sliderRow.revalidate();
        sliderRow.repaint();
        editingCustomValue = false;
    }

    private Integer parseCustomTimeMinutes(String input)
    {
        if (input == null)
        {
            return null;
        }

        String trimmed = input.trim();
        if (trimmed.isEmpty())
        {
            return null;
        }

        String normalized = trimmed.toLowerCase(Locale.ROOT);

        if (normalized.contains(":"))
        {
            String[] parts = normalized.split(":");
            if (parts.length != 2)
            {
                return null;
            }

            try
            {
                int hours = Integer.parseInt(parts[0].trim());
                int minutes = Integer.parseInt(parts[1].trim());
                if (hours < 0 || minutes < 0 || minutes >= 60)
                {
                    return null;
                }
                return hours * 60 + minutes;
            }
            catch (NumberFormatException ex)
            {
                return null;
            }
        }

        Matcher matcher = TIME_TOKEN_PATTERN.matcher(normalized);
        int total = 0;
        int lastEnd = 0;
        boolean matched = false;

        while (matcher.find())
        {
            String between = normalized.substring(lastEnd, matcher.start());
            if (!between.trim().isEmpty())
            {
                return null;
            }

            String numberPart = matcher.group(1);
            String unit = matcher.group(2);

            double value;
            try
            {
                value = Double.parseDouble(numberPart);
            }
            catch (NumberFormatException ex)
            {
                return null;
            }

            if (unit == null || unit.toLowerCase(Locale.ROOT).startsWith("m"))
            {
                if (numberPart.contains("."))
                {
                    return null;
                }
                total += (int) value;
            }
            else
            {
                total += (int) Math.round(value * 60.0);
            }

            matched = true;
            lastEnd = matcher.end();
        }

        if (matched)
        {
            String trailing = normalized.substring(lastEnd).trim();
            if (!trailing.isEmpty())
            {
                return null;
            }
            return total > 0 ? total : null;
        }

        try
        {
            int minutes = Integer.parseInt(normalized);
            return minutes > 0 ? minutes : null;
        }
        catch (NumberFormatException ex)
        {
            return null;
        }
    }

    private void updateRiskCombo(RiskLevel level)
    {
        RiskLevel effective = level != null ? level : RiskLevel.MEDIUM;
        String label;
        Color accent;
        switch (effective)
        {
            case LOW:
                label = RISK_LOW_LABEL;
                accent = RISK_LOW_SELECTED_COLOR;
                break;
            case HIGH:
                label = RISK_HIGH_LABEL;
                accent = RISK_HIGH_SELECTED_COLOR;
                break;
            case MEDIUM:
            default:
                label = RISK_MEDIUM_LABEL;
                accent = RuneAssistColors.ACCENT;
                break;
        }
        suppressComboEvents = true;
        try
        {
            riskCombo.setSelectedItem(label);
        }
        finally
        {
            suppressComboEvents = false;
        }
        riskCombo.setForeground(accent);
    }

    private RiskLevel riskFromCombo()
    {
        Object selected = riskCombo.getSelectedItem();
        if (RISK_LOW_LABEL.equals(selected))
        {
            return RiskLevel.LOW;
        }
        if (RISK_HIGH_LABEL.equals(selected))
        {
            return RiskLevel.HIGH;
        }
        return RiskLevel.MEDIUM;
    }

    private void onTimeframeComboChanged()
    {
        Object selected = timeframeCombo.getSelectedItem();
        if ("Custom".equals(selected))
        {
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
        if ("30m".equals(selected))
        {
            minutes = PRESET_30M;
        }
        else if ("2h".equals(selected))
        {
            minutes = PRESET_2H;
        }
        else if ("8h".equals(selected))
        {
            minutes = PRESET_8H;
        }
        applyTimeframe(minutes, /*updateSlider*/ true);
    }

    private void setTimeframeComboSelection(String item)
    {
        suppressComboEvents = true;
        try
        {
            timeframeCombo.setSelectedItem(item);
        }
        finally
        {
            suppressComboEvents = false;
        }
    }

    // ---------- UI wiring ----------
    private void applyRiskLevel(RiskLevel level)
    {
        RiskLevel effective = level != null ? level : RiskLevel.MEDIUM;
        preferencesManager.setRiskLevel(effective);
        suggestionManager.setSuggestionNeeded(true);
        updateRiskCombo(effective);
    }

    private void applyTimeframe(int minutes, boolean updateSlider)
    {
        preferencesManager.setTimeframe(minutes);
        suggestionManager.setSuggestionNeeded(true);

        if (updateSlider)
        {
            try
            {
                suppressTimeframeSliderEvents = true;
                timeframeSlider.setValue(minutesToPos(minutes));
            }
            finally
            {
                suppressTimeframeSliderEvents = false;
            }
            updateValueLabel(minutes);
        }
        syncTimeframeCombo(minutes);
        updateCustomVisibility();
    }

    private void syncTimeframeCombo(int minutes)
    {
        if (!isPreset(minutes))
        {
            customExplicitlySelected = true;
        }

        if (customExplicitlySelected)
        {
            setTimeframeComboSelection("Custom");
        }
        else if (minutes == PRESET_5M)
        {
            setTimeframeComboSelection("5m");
        }
        else if (minutes == PRESET_30M)
        {
            setTimeframeComboSelection("30m");
        }
        else if (minutes == PRESET_2H)
        {
            setTimeframeComboSelection("2h");
        }
        else if (minutes == PRESET_8H)
        {
            setTimeframeComboSelection("8h");
        }
        else
        {
            customExplicitlySelected = true;
            setTimeframeComboSelection("Custom");
        }
    }

    private static void styleCompactCombo(JComboBox<String> combo)
    {
        combo.setBackground(RuneAssistColors.CARD);
        combo.setForeground(RuneAssistColors.TEXT);
        combo.setFocusable(false);
        combo.setMaximumRowCount(5);
        // Width comes from the longest option via the prototype the caller sets, so a value
        // can never be wider than the box showing it.
        combo.setMinimumSize(new Dimension(56, 22));
        combo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        combo.setBorder(BorderFactory.createLineBorder(RuneAssistColors.HAIRLINE));
        combo.setRenderer(new DefaultListCellRenderer()
        {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus)
            {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                c.setBackground(isSelected ? RuneAssistColors.ACCENT_MUTED : RuneAssistColors.CARD);
                c.setForeground(isSelected ? RuneAssistColors.ACCENT : RuneAssistColors.TEXT);
                if (c instanceof JComponent)
                {
                    ((JComponent) c).setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
                }
                return c;
            }
        });
    }

    public void refresh()
    {
        if (!UIUtilities.ensureEdt(this::refresh)) return;

        int tf = clampMinutes(preferencesManager.getTimeframe());
        syncTimeframeCombo(tf);

        try
        {
            suppressTimeframeSliderEvents = true;
            timeframeSlider.setValue(minutesToPos(tf));
        }
        finally
        {
            suppressTimeframeSliderEvents = false;
        }
        updateValueLabel(tf);

        updateRiskCombo(preferencesManager.getRiskLevel());

        updateCustomVisibility();
    }
}
