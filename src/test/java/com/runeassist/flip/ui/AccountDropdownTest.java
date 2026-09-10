package com.runeassist.flip.ui;

import com.runeassist.flip.ui.components.AccountDropdown;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountDropdownTest {
    @Test void newAccountsAppearWithoutChangingTheUsersSelection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String,Integer> accounts = new HashMap<>();
            accounts.put("Test Beta", 2);
            List<Integer> changes = new ArrayList<>();
            AccountDropdown dropdown = new AccountDropdown(() -> accounts, changes::add, "All accounts");
            dropdown.refresh();
            dropdown.setSelectedAccountId(2);
            accounts.put("Test Alpha", 1);
            dropdown.refresh();
            assertEquals(3, dropdown.getItemCount());
            assertEquals("Test Beta", dropdown.getSelectedItem());
            assertTrue(changes.isEmpty(), "Refresh must not change the stats account filter");
            dropdown.setSelectedItem("Test Alpha");
            assertEquals(Collections.singletonList(1), changes);
        });
    }
}
