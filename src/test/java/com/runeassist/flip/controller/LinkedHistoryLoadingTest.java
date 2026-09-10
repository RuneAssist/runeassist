package com.runeassist.flip.controller;

import com.google.gson.*;
import com.runeassist.flip.HeldCostTracker;
import com.runeassist.flip.controller.history.AccountHttp;
import com.runeassist.flip.model.*;
import com.runeassist.flip.rs.AccountLoginRS;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LinkedHistoryLoadingTest {
    static class Api extends AccountHttp {
        List<String> requests = new ArrayList<>();
        boolean failSecond;
        String user = "owner-one";
        Api() { super(null, null, null); }
        @Override public String userId() { return user; }
        @Override public JsonObject get(String path, boolean authed) {
            assertTrue(authed);
            requests.add(path);
            if (path.equals("/v1/account/me")) return JsonParser.parseString(
                    "{\"osrsAccounts\":[{\"id\":\"one\",\"displayName\":\"Test Alpha\"},{\"id\":\"two\",\"displayName\":\"Test Beta\"}]}").getAsJsonObject();
            boolean second = path.contains("osrsAccountId=two");
            if (second && failSecond) return null;
            JsonObject body = new JsonObject();
            body.addProperty("time", 200);
            JsonArray flips = new JsonArray();
            if (!path.contains("sinceUpdatedTime")) {
                JsonObject flip = JsonParser.parseString("{\"id\":\"00000000-0000-0000-0000-00000000000" + (second ? "2" : "1")
                        + "\",\"itemId\":4151,\"openedTime\":100,\"closedTime\":200,\"openedQuantity\":1,\"closedQuantity\":1,\"spent\":100,\"profit\":20,\"status\":\"FINISHED\",\"portfolioId\":1,\"updatedTime\":200}").getAsJsonObject();
                flips.add(flip);
            }
            body.add("flips", flips);
            return body;
        }
    }

    private FlipHistorySyncService service(Api api, FlipManager manager, AccountLoginRS accounts) throws Exception {
        FlipHistorySyncService service = new FlipHistorySyncService(api, null, manager,
                new OsrsLoginManager(null) { @Override public String getPlayerDisplayName() { return null; } },
                new HeldCostTracker(), new SuggestionManager(), null, null) {
            @Override public List<Transaction> listUnacked(String name) { return Collections.emptyList(); }
        };
        java.lang.reflect.Field field = FlipHistorySyncService.class.getDeclaredField("accountLoginRS");
        field.setAccessible(true);
        field.set(service, accounts);
        return service;
    }

    @Test void startupLoadsEveryAccountThenUsesDeltasAndRestartReloadsHistory() throws Exception {
        Api api = new Api();
        FlipManager manager = new FlipManager(null);
        AccountLoginRS accounts = new AccountLoginRS();
        FlipHistorySyncService service = service(api, manager, accounts);
        service.syncLinkedAccounts();
        assertEquals(2, accounts.get().displayNameToAccountId.size());
        assertEquals(2, manager.getRealizedIntervalStats().flipsMade);
        assertTrue(service.isHistoryReady(null));
        assertEquals("Test Beta", service.displayNameForAccount(FlipHistorySyncService.accountIdFor("Test Beta")));
        assertFalse(api.requests.stream().anyMatch(p -> p.contains("sinceUpdatedTime")));
        api.requests.clear();
        service.syncLinkedAccounts();
        assertEquals(2, api.requests.size());
        assertTrue(api.requests.stream().allMatch(p -> p.contains("sinceUpdatedTime=200")));
        assertEquals(2, manager.getRealizedIntervalStats().flipsMade, "Deltas must not duplicate history");
        api.requests.clear();
        FlipManager restarted = new FlipManager(null);
        service(api, restarted, new AccountLoginRS()).syncLinkedAccounts();
        assertFalse(api.requests.stream().anyMatch(p -> p.contains("sinceUpdatedTime")));
        assertEquals(2, restarted.getRealizedIntervalStats().flipsMade);
    }

    @Test void failedAccountDoesNotBecomeAFalseZeroOrAdvanceItsCursor() throws Exception {
        Api api = new Api();
        api.failSecond = true;
        FlipManager manager = new FlipManager(null);
        FlipHistorySyncService service = service(api, manager, new AccountLoginRS());
        service.syncLinkedAccounts();
        assertFalse(service.isHistoryReady(null));
        assertTrue(service.isHistoryReady(FlipHistorySyncService.accountIdFor("Test Alpha")));
        assertFalse(service.isHistoryReady(FlipHistorySyncService.accountIdFor("Test Beta")));
        api.failSecond = false;
        api.requests.clear();
        service.syncLinkedAccounts();
        assertTrue(api.requests.contains("/v1/account/client-flips-delta?osrsAccountId=two"));
        assertTrue(service.isHistoryReady(null));
    }

    @Test void pairingToAnotherUserDiscardsOldCursorsAndHistory() throws Exception {
        Api api = new Api();
        FlipManager manager = new FlipManager(null);
        FlipHistorySyncService service = service(api, manager, new AccountLoginRS());
        service.syncLinkedAccounts();
        api.user = "owner-two";
        api.requests.clear();
        service.syncLinkedAccounts();
        assertFalse(api.requests.stream().anyMatch(p -> p.contains("sinceUpdatedTime")));
        assertEquals(2, manager.getRealizedIntervalStats().flipsMade);
    }
}
