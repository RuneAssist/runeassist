package com.runeassist.flip.model;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Drift guard: Java FIFO replay must match {@code server/flip-ledger-vectors.json},
 * which the JS port also runs.
 */
public class FlipLedgerEngineTest {

    @Test
    public void matchesSharedVectors() throws Exception {
        Path path = Paths.get("server/flip-ledger-vectors.json");
        if (!Files.exists(path)) {
            path = Paths.get("../server/flip-ledger-vectors.json");
        }
        String json = Files.readString(path, StandardCharsets.UTF_8);
        JsonObject root = new Gson().fromJson(json, JsonObject.class);
        JsonArray cases = root.getAsJsonArray("cases");
        assertNotNull(cases);
        for (JsonElement el : cases) {
            JsonObject c = el.getAsJsonObject();
            String name = c.get("name").getAsString();
            List<Transaction> txs = parseTxs(c.getAsJsonArray("transactions"));
            FlipLedgerEngine.Book book = FlipLedgerEngine.replay(txs);
            JsonObject expect = c.getAsJsonObject("expect");
            assertStats(name, expect.getAsJsonObject("stats"), FlipLedgerEngine.statsOf(book));
            assertEquals(expect.get("portfolioValue").getAsLong(), FlipLedgerEngine.portfolioValue(book),
                    name + " portfolioValue");
            assertFlips(name + " closed", expect.getAsJsonArray("closedFlips"),
                    FlipLedgerEngine.closedNewestFirst(book));
            assertFlips(name + " open", expect.getAsJsonArray("openPositions"),
                    FlipLedgerEngine.openPositions(book));
        }
    }

    private static List<Transaction> parseTxs(JsonArray arr) {
        List<Transaction> out = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            Transaction t = new Transaction();
            t.setId(UUID.fromString(o.get("id").getAsString()));
            t.setType(OfferStatus.valueOf(o.get("type").getAsString().toUpperCase()));
            t.setItemId(o.get("itemId").getAsInt());
            t.setPrice(o.get("price").getAsLong());
            t.setQuantity(o.get("quantity").getAsInt());
            t.setBoxId(o.get("boxId").getAsInt());
            t.setAmountSpent(o.get("amountSpent").getAsLong());
            t.setTimestamp(Instant.parse(o.get("timestamp").getAsString()));
            out.add(t);
        }
        return out;
    }

    private static void assertStats(String name, JsonObject expect, Stats actual) {
        assertEquals(expect.get("profit").getAsLong(), actual.profit, name + " profit");
        assertEquals(expect.get("gross").getAsLong(), actual.gross, name + " gross");
        assertEquals(expect.get("taxPaid").getAsLong(), actual.taxPaid, name + " taxPaid");
        assertEquals(expect.get("flipsMade").getAsInt(), actual.flipsMade, name + " flipsMade");
    }

    private static void assertFlips(String name, JsonArray expect, List<FlipV2> actual) {
        if (expect.size() != actual.size()) {
            fail(name + " size expected " + expect.size() + " got " + actual.size());
        }
        for (int i = 0; i < expect.size(); i++) {
            JsonObject e = expect.get(i).getAsJsonObject();
            FlipV2 a = actual.get(i);
            String prefix = name + "[" + i + "]";
            assertEquals(e.get("itemId").getAsInt(), a.getItemId(), prefix + " itemId");
            assertEquals(e.get("openedQuantity").getAsInt(), a.getOpenedQuantity(), prefix + " openedQuantity");
            assertEquals(e.get("closedQuantity").getAsInt(), a.getClosedQuantity(), prefix + " closedQuantity");
            assertEquals(e.get("spent").getAsLong(), a.getSpent(), prefix + " spent");
            assertEquals(e.get("profit").getAsLong(), a.getProfit(), prefix + " profit");
            assertEquals(e.get("taxPaid").getAsLong(), a.getTaxPaid(), prefix + " taxPaid");
            assertEquals(e.get("status").getAsString(), a.getStatus().name(), prefix + " status");
        }
    }
}
