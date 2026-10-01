package com.runeassist.flip.controller;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class GrandExchangeVisibilityTest {
    @Test void homeOrEditorCanKeepTheGeOpenButHiddenLeftoversCannot() {
        boolean[] visible = {true, false};
        Widget home = widget(visible, 0);
        Widget editor = widget(visible, 1);
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
                new Class<?>[]{Client.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getWidget")) return (int) args[1] == 7 ? home : editor;
                    throw new AssertionError("Unexpected client call: " + method.getName());
                });
        GrandExchange ge = new GrandExchange(client);
        assertTrue(ge.isOpen());
        visible[0] = false;
        visible[1] = true;
        assertTrue(ge.isOpen(), "Opening the offer editor must not enter Away");
        visible[1] = false;
        assertFalse(ge.isOpen());
    }

    private static Widget widget(boolean[] visible, int index) {
        return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(),
                new Class<?>[]{Widget.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isHidden")) return !visible[index];
                    throw new AssertionError("Unexpected widget call: " + method.getName());
                });
    }
}
