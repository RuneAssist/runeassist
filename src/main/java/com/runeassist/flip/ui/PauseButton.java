package com.runeassist.flip.ui;

import com.runeassist.flip.controller.SuggestionController;
import com.runeassist.flip.model.PausedManager;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.ImageIcon;
import javax.swing.JButton;

import static com.runeassist.flip.ui.UIUtilities.BUTTON_HOVER_LUMINANCE;

@Singleton
public class PauseButton extends JButton {

    private final PausedManager pausedManager;

    private static final ImageIcon PLAY_ICON;
    private static final ImageIcon PAUSE_ICON;
    private static final ImageIcon PLAY_ICON_HOVER;
    private static final ImageIcon PAUSE_ICON_HOVER;

    static {
        var play = ImageUtil.loadImageResource(PauseButton.class, "/play.png");
        var pause = ImageUtil.loadImageResource(PauseButton.class, "/pause.png");
        PLAY_ICON = new ImageIcon(play);
        PAUSE_ICON = new ImageIcon(pause);
        PLAY_ICON_HOVER =  new ImageIcon(ImageUtil.luminanceScale(play, BUTTON_HOVER_LUMINANCE));
        PAUSE_ICON_HOVER = new ImageIcon(ImageUtil.luminanceScale(pause, BUTTON_HOVER_LUMINANCE));
    }

    @Inject
    public PauseButton(PausedManager pausedManager,
                       SuggestionController suggestionController) {
        super(PAUSE_ICON);
        this.pausedManager = pausedManager;
        setToolTipText("Pause suggestions");
        addActionListener(e -> {
            suggestionController.togglePause();
            update();
        });

        UIUtilities.addHoverIcons(this,
                () -> pausedManager.isPaused() ? PLAY_ICON : PAUSE_ICON,
                () -> pausedManager.isPaused() ? PLAY_ICON_HOVER : PAUSE_ICON_HOVER);

        setFocusPainted(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
    }

    private void update() {
        boolean isPaused = pausedManager.isPaused();
        setIcon(isPaused ? PLAY_ICON : PAUSE_ICON);
        setToolTipText(isPaused ? "Unpause suggestions" :  "Pause suggestions");
    }
}
