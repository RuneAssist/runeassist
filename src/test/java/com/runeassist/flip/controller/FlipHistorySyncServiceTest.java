package com.runeassist.flip.controller;

import com.google.gson.JsonObject;
import com.runeassist.flip.model.FlipStatus;
import com.runeassist.flip.model.FlipV2;
import com.runeassist.flip.model.OfferStatus;
import com.runeassist.flip.model.Transaction;
import com.runeassist.flip.ui.FlipRepairMenus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FlipHistorySyncServiceTest {

    @Test
    public void flipFromJsonReadsDeletedAndPortfolioId() {
        JsonObject o = new JsonObject();
        o.addProperty("id", "11111111-1111-1111-1111-111111111111");
        o.addProperty("itemId", 4151);
        o.addProperty("openedTime", 100);
        o.addProperty("openedQuantity", 10);
        o.addProperty("spent", 1000);
        o.addProperty("status", "BUYING");
        o.addProperty("deleted", true);
        o.addProperty("portfolioId", -4);
        o.addProperty("seqNo", 3);
        o.addProperty("updatedTime", 200);
        FlipV2 f = FlipHistorySyncService.flipFromJson(o, 7);
        assertNotNull(f);
        assertTrue(f.isDeleted());
        assertEquals(-4, f.getPortfolioId());
        assertEquals(FlipStatus.BUYING, f.getStatus());
        assertEquals(7, f.getAccountId());
    }

    @Test
    public void txToJsonRoundTripsCoreFields() {
        Transaction t = new Transaction();
        t.setId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        t.setType(OfferStatus.BUY);
        t.setItemId(4151);
        t.setPrice(100);
        t.setQuantity(2);
        t.setBoxId(1);
        t.setAmountSpent(200);
        t.setTimestamp(Instant.parse("2024-01-01T00:00:00Z"));
        JsonObject o = FlipHistorySyncService.txToJson(t);
        assertEquals("BUY", o.get("type").getAsString());
        assertEquals(4151, o.get("itemId").getAsInt());
        assertEquals(200, o.get("amountSpent").getAsLong());
        assertFalse(o.get("id").getAsString().isEmpty());
    }

    @Test
    public void txFromJsonParsesSell() {
        JsonObject o = new JsonObject();
        o.addProperty("id", "33333333-3333-3333-3333-333333333333");
        o.addProperty("type", "SELL");
        o.addProperty("itemId", 4151);
        o.addProperty("price", 500);
        o.addProperty("quantity", 3);
        o.addProperty("boxId", 2);
        o.addProperty("amountSpent", 1500);
        o.addProperty("timestamp", "2024-02-01T00:00:00Z");
        Transaction t = FlipHistorySyncService.txFromJson(o);
        assertNotNull(t);
        assertEquals(OfferStatus.SELL, t.getType());
        assertEquals(4151, t.getItemId());
        assertEquals(1500, t.getAmountSpent());
        assertEquals(UUID.fromString("33333333-3333-3333-3333-333333333333"), t.getId());
    }

    @Test
    public void canMissedSaleRequiresOpenQty() {
        FlipV2 open = new FlipV2();
        open.setStatus(FlipStatus.SELLING);
        open.setOpenedQuantity(10);
        open.setClosedQuantity(4);
        assertTrue(FlipRepairMenus.canMissedSale(open));
        open.setClosedQuantity(10);
        open.setStatus(FlipStatus.FINISHED);
        assertFalse(FlipRepairMenus.canMissedSale(open));
    }
}
