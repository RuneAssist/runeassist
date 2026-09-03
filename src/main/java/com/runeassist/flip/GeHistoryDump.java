package com.runeassist.flip;

import com.runeassist.flip.model.GeHistoryRow;
import com.runeassist.flip.rs.GeHistoryStateRS;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One-shot / backfill of the in-game GE history UI into {@link TelemetryService}
 * ({@code ge_history} JSONL, uploaded with the rest of ingest when contribution is on).
 * Driven by RuneAssist Flipping when that plugin is enabled (so opt-in works with MCP
 * off); MCP still drives it as a fallback. Triggered when the history panel is open
 * (game tick / widget load) and on login if it is already visible. Never places or
 * cancels offers.
 */
@Slf4j
@Singleton
public class GeHistoryDump
{
    @Inject private Client client;
    @Inject private TelemetryService telemetry;

    private boolean lastSeenVisible = false;
    private int sessionOpenedAt = 0;
    private List<GeHistoryRow> lastDumped = null;

    public void onLogin()
    {
        lastSeenVisible = false;
        lastDumped = null;
        tryDump();
    }

    public void onHistoryWidgetLoaded()
    {
        tryDump();
    }

    public void onGameTick()
    {
        tryDump();
    }

    private void tryDump()
    {
        try
        {
            if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN) return;
            Widget container = client.getWidget(
                GeHistoryStateRS.GE_HISTORY_GROUP, GeHistoryStateRS.GE_HISTORY_LIST_CHILD);
            boolean visible = container != null;
            if (visible && !lastSeenVisible)
            {
                sessionOpenedAt = (int) Instant.now().getEpochSecond();
                lastDumped = null;
            }
            lastSeenVisible = visible;
            if (!visible) return;

            List<GeHistoryRow> rows = GeHistoryStateRS.parseRows(container);
            if (rows.isEmpty()) return;
            if (rows.equals(lastDumped)) return;
            lastDumped = rows;

            List<TelemetryService.GeHistoryFill> fills = new ArrayList<>(rows.size());
            for (GeHistoryRow row : rows)
            {
                String name = row.getName();
                if (name == null || name.isEmpty())
                {
                    try
                    {
                        name = client.getItemDefinition(row.getItemId()).getName();
                    }
                    catch (Exception ignored)
                    {
                        name = null;
                    }
                }
                fills.add(new TelemetryService.GeHistoryFill(
                    row.getItemId(), row.getQuantity(), row.getPrice(), row.isBuy(),
                    name, row.getFillTs()));
            }
            String rsn = rsn();
            telemetry.logGeHistory(rsn, fills, sessionOpenedAt);
        }
        catch (Exception e)
        {
            log.debug("GE history dump skipped: {}", e.getMessage());
        }
    }

    private String rsn()
    {
        net.runelite.api.Player p = client.getLocalPlayer();
        return p != null ? p.getName() : "anon";
    }
}
