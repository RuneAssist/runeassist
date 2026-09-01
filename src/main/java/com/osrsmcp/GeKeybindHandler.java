package com.osrsmcp;

import net.runelite.api.Client;
import net.runelite.api.VarClientStr;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.Keybind;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.event.KeyEvent;

/**
 * A user-pressed keybind that fills RuneAssist's suggested price or quantity into the open
 * GE input box. The USER presses the key and still presses Enter to confirm — nothing is
 * sent or submitted automatically. Mechanism (set the input widget text + INPUT_TEXT
 * varclient, with a trailing '*') is adapted from Flipping Copilot's {@code OfferHandler} /
 * {@code KeybindHandler} (BSD 2-Clause, Copyright (c) 2024 Cillian Brewitt; see
 * THIRD_PARTY_LICENSES.md).
 */
@Singleton
public class GeKeybindHandler
{
    private final KeyManager keyManager;
    private final Client client;
    private final ClientThread clientThread;
    private final OsrsMcpConfig config;
    private final SharedFlipState flip;
    private final GeWidgets ge;
    private final KeyListener listener;

    @Inject
    GeKeybindHandler(KeyManager keyManager, Client client, ClientThread clientThread,
                     OsrsMcpConfig config, SharedFlipState flip)
    {
        this.keyManager = keyManager;
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.flip = flip;
        this.ge = new GeWidgets(client);
        this.listener = createListener();
    }

    public void register()   { keyManager.registerKeyListener(listener); }
    public void unregister() { keyManager.unregisterKeyListener(listener); }

    private KeyListener createListener()
    {
        return new KeyListener()
        {
            @Override public void keyTyped(KeyEvent e) {}
            @Override public void keyReleased(KeyEvent e) {}
            @Override public void keyPressed(KeyEvent e)
            {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) return; // Enter submits; don't hijack it
                Keybind kb = config.quickSetKeybind();
                if (kb == null || Keybind.NOT_SET.equals(kb) || !kb.matches(e)) return;
                clientThread.invokeLater(GeKeybindHandler.this::quickSet);
            }
        };
    }

    /** Fill the suggested value into whichever GE input (price/quantity) is open. */
    private void quickSet()
    {
        if (!flip.valid) return;
        Widget title = client.getWidget(ComponentID.CHATBOX_TITLE);
        if (title == null || title.isHidden() || title.getText() == null) return;
        String t = title.getText();
        if (ge.setupItemId() != flip.itemId) return; // only for the suggested item

        long value;
        if (t.equals("How many do you wish to buy?") || t.equals("How many do you wish to sell?"))
        {
            if (flip.qty <= 0) return;
            value = flip.qty;
        }
        else if (t.equals("Set a price for each item:"))
        {
            value = ge.setupIsBuy() ? flip.buyAt : flip.sellAt;
            if (value <= 0) return;
        }
        else return;

        Widget input = client.getWidget(ComponentID.CHATBOX_FULL_INPUT);
        if (input == null) return;
        input.setText(value + "*");                 // '*' is the game's input caret marker
        client.setVarcStrValue(VarClientStr.INPUT_TEXT, String.valueOf(value));
    }
}
