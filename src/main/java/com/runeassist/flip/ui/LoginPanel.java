package com.runeassist.flip.ui;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JPanel;

/**
 * Stub kept for Guice wiring. Flipping Copilot email/Discord login UI is
 * disabled — RuneAssist uses local GE fills plus optional cloud pairing in
 * Preferences, not a legacy copilot account.
 */
@Singleton
public class LoginPanel extends JPanel {

    @Inject
    public LoginPanel() {
        setOpaque(false);
    }

    public void startLoading() {
    }

    public void endLoading() {
    }

    public void showLoginErrorMessage(String message) {
    }
}
