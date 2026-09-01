package com.osrsmcp;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OPT-IN, anonymised, LOCAL-ONLY telemetry logger.
 *
 * Writes versioned JSONL to a per-day file under ~/.runelite/runeassist/telemetry/
 * so account data (real XP/hr, GE performance, account trajectories) can be analysed
 * later. There is NO backend and NO network -- every file stays on this PC. It is a
 * no-op unless the user explicitly enables {@code shareTelemetry} (default OFF).
 *
 * Callers (client thread / EDT) gather live values and pass primitives in; this class
 * never touches the RuneLite Client and never throws. All disk IO runs on a single
 * daemon executor so callers never block.
 */
@Slf4j
@Singleton
public class TelemetryService
{
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int SCHEMA_VERSION = 1;

    private final File baseDir;

    @Inject private OsrsMcpConfig config;
    @Inject private Gson gson;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "runeassist-telemetry");
        t.setDaemon(true);
        return t;
    });

    // Cache the last (rsn -> hash) pair to avoid rehashing every record.
    private String lastRsn;
    private String lastHash;

    public TelemetryService()
    {
        baseDir = new File(RuneLite.RUNELITE_DIR, "runeassist/telemetry");
    }

    private boolean enabled()
    {
        return config != null && config.shareTelemetry();
    }

    // ── PUBLIC CAPTURE METHODS ────────────────────────────────────────────────

    public void logXpGain(String rsn, String skill, long xp, long delta, int level,
                          int x, int y, int plane)
    {
        if (!enabled()) return;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("v", SCHEMA_VERSION);
        r.put("type", "xp_gain");
        r.put("ts", System.currentTimeMillis());
        r.put("acct", acctHash(rsn));
        r.put("skill", skill);
        r.put("xp", xp);
        r.put("delta", delta);
        r.put("level", level);
        r.put("x", x);
        r.put("y", y);
        r.put("plane", plane);
        write("xp_gain", r);
    }

    public void logAccountSnapshot(String rsn, int combatLevel, int totalLevel, int questPoints,
                                   String accountType, int x, int y, int plane,
                                   Map<String, long[]> skills)
    {
        if (!enabled()) return;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("v", SCHEMA_VERSION);
        r.put("type", "account_snapshot");
        r.put("ts", System.currentTimeMillis());
        r.put("acct", acctHash(rsn));
        r.put("combat_level", combatLevel);
        r.put("total_level", totalLevel);
        r.put("quest_points", questPoints);
        r.put("account_type", accountType);
        r.put("x", x);
        r.put("y", y);
        r.put("plane", plane);

        // skills: skillName -> [level, xp]
        Map<String, Object> skillMap = new LinkedHashMap<>();
        if (skills != null)
        {
            for (Map.Entry<String, long[]> e : skills.entrySet())
            {
                long[] v = e.getValue();
                if (v == null || v.length < 2) continue;
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("level", v[0]);
                s.put("xp", v[1]);
                skillMap.put(e.getKey(), s);
            }
        }
        r.put("skills", skillMap);
        write("account_snapshot", r);
    }

    public void logGeOffer(String rsn, int slot, String state, int itemId, int price,
                           int totalQuantity, int quantitySold, int spent)
    {
        if (!enabled()) return;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("v", SCHEMA_VERSION);
        r.put("type", "ge_offer");
        r.put("ts", System.currentTimeMillis());
        r.put("acct", acctHash(rsn));
        r.put("slot", slot);
        r.put("state", state);
        r.put("item_id", itemId);
        r.put("price", price);
        r.put("total_quantity", totalQuantity);
        r.put("quantity_sold", quantitySold);
        r.put("spent", spent);
        write("ge_offer", r);
    }

    public void shutdown()
    {
        executor.shutdown();
    }

    // ── ANONYMISATION ─────────────────────────────────────────────────────────

    private synchronized String acctHash(String rsn)
    {
        if (rsn == null || rsn.trim().isEmpty()) return "anon";
        String norm = rsn.toLowerCase(Locale.ROOT).trim();
        if (norm.equals(lastRsn) && lastHash != null) return lastHash;
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(norm.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest)
                sb.append(Character.forDigit((b >> 4) & 0xf, 16))
                  .append(Character.forDigit(b & 0xf, 16));
            lastRsn = norm;
            lastHash = sb.toString();
            return lastHash;
        }
        catch (NoSuchAlgorithmException e)
        {
            log.warn("Telemetry: SHA-256 unavailable; logging as 'anon'", e);
            return "anon";
        }
    }

    // ── IO ────────────────────────────────────────────────────────────────────

    private void write(String type, Map<String, Object> record)
    {
        executor.submit(() -> {
            try
            {
                if (!baseDir.exists() && !baseDir.mkdirs())
                {
                    log.warn("Telemetry: could not create directory: {}", baseDir);
                    return;
                }
                String day = LocalDate.now(ZoneOffset.UTC).format(DAY_FMT);
                File file = new File(baseDir, type + "-" + day + ".jsonl");
                try (BufferedWriter w = new BufferedWriter(
                         new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)))
                {
                    w.write(gson.toJson(record));
                    w.write("\n");
                }
            }
            catch (IOException | RuntimeException e)
            {
                // Telemetry must NEVER throw into a caller.
                log.warn("Telemetry: failed to write {} record: {}", type, e.getMessage());
            }
        });
    }
}
