package com.runeassist.flip;

import com.runeassist.flip.model.OsrsLoginManager;
import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class RuneAssistSaleInventoryTest {
    private final RuneAssistSuggestionSource source = new RuneAssistSuggestionSource();
    private final HeldCostTracker held = new HeldCostTracker();
    private Item[] items = {new Item(537, 10)};
    private boolean known = true;
    private boolean valid = true;
    private boolean loginBurst;

    @BeforeEach void setUp() throws Exception {
        ItemContainer container = (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(),
                new Class<?>[]{ItemContainer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getItems")) return items;
                    throw new AssertionError(method.getName());
                });
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
                new Class<?>[]{Client.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getItemContainer")) return known ? container : null;
                    if (method.getName().equals("getItemDefinition")) {
                        int id = (int) args[0];
                        return Proxy.newProxyInstance(ItemComposition.class.getClassLoader(),
                                new Class<?>[]{ItemComposition.class}, (definition, member, values) -> {
                                    if (member.getName().equals("getNote")) return id == 537 ? 799 : -1;
                                    if (member.getName().equals("getLinkedNoteId")) return 536;
                                    throw new AssertionError(member.getName());
                                });
                    }
                    throw new AssertionError(method.getName());
                });
        OsrsLoginManager login = new OsrsLoginManager(client) {
            @Override public boolean isValidLoginState() { return valid; }
            @Override public boolean hasJustLoggedIn() { return loginBurst; }
            @Override public String getPlayerDisplayName() { return "Account"; }
        };
        inject("client", client);
        inject("osrsLoginManager", login);
        inject("heldCostTracker", held);
        held.addManualLot("Account", 536, 10, 100);
    }

    @Test void notesAreNormalizedAndFreshPhysicalQuantityCapsTheSale() {
        assertTrue(source.hasInventoryForSale(sale(10)));
        items = new Item[]{new Item(537, 4), new Item(536, 1)};
        assertTrue(source.hasInventoryForSale(sale(5)));
        assertFalse(source.hasInventoryForSale(sale(6)));
        items = new Item[0];
        assertFalse(source.hasInventoryForSale(sale(1)), "Historical lots are not physical inventory");
    }

    @Test void unknownInventoryAndUnsettledLoginFailClosed() {
        known = false;
        assertFalse(source.hasInventoryForSale(sale(10)));
        known = true;
        loginBurst = true;
        assertFalse(source.hasInventoryForSale(sale(10)));
        loginBurst = false;
        valid = false;
        assertFalse(source.hasInventoryForSale(sale(10)));
    }

    @Test void removingTrackedStockDuringTheRequestInvalidatesTheSale() {
        assertTrue(source.hasInventoryForSale(sale(10)));
        held.removeLots("Account", 536, 6);
        assertFalse(source.hasInventoryForSale(sale(10)));
        assertTrue(source.hasInventoryForSale(sale(4)));
    }

    private Suggestion sale(int quantity) {
        Suggestion suggestion = new Suggestion();
        suggestion.setType(SuggestionType.SELL);
        suggestion.setItemId(536);
        suggestion.setQuantity(quantity);
        return suggestion;
    }

    private void inject(String name, Object value) throws Exception {
        Field field = RuneAssistSuggestionSource.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(source, value);
    }
}
