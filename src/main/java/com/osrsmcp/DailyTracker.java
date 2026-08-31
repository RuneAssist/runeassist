package com.osrsmcp;

import javax.inject.Singleton;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Best-effort live tracking of daily/weekly availability from the game's login
 * chat messages ("You have battlestaves waiting to be collected from Zaff.",
 * "... herb boxes waiting to be collected at NMZ.", etc.). These fire at login
 * when a daily is unclaimed, so we record when each was last seen available and
 * report it relative to the last reset. It cannot detect a claim made after
 * login (the game sends no message for that), so status reflects login state.
 */
@Singleton
public class DailyTracker
{
    // track key -> epoch millis when last seen "waiting to be collected"
    private final Map<String, Long> availableAt = new ConcurrentHashMap<>();

    /** Feed every game chat message here (from the plugin's @Subscribe). */
    public void onChatMessage(String message)
    {
        if (message == null) return;
        String m = message.toLowerCase();
        if (!m.contains("collect") && !m.contains("waiting") && !m.contains("tears of guthix")) return;

        if (m.contains("battlestaves"))     mark("battlestaves");
        if (m.contains("herb box"))         mark("herb_boxes");
        if (m.contains("bucket") && m.contains("sand")) mark("buckets_of_sand");
        // "You can now play the Tears of Guthix minigame again."
        if (m.contains("tears of guthix"))  mark("tears_of_guthix");
    }

    private void mark(String key) { availableAt.put(key, System.currentTimeMillis()); }

    /**
     * Status for a tracked daily: "available" if a login message flagged it since
     * the last reset, else "unknown". Null trackKey -> null (not tracked).
     */
    public Map<String, Object> status(String trackKey, String reset)
    {
        if (trackKey == null) return null;
        Long at = availableAt.get(trackKey);
        Map<String, Object> s = new java.util.LinkedHashMap<>();
        if (at == null)
        {
            s.put("state", "unknown");
            s.put("detail", "not seen this session -- log in to detect (or you've already done it).");
            return s;
        }
        long boundary = "weekly".equals(reset) ? System.currentTimeMillis() - 7L * 24 * 3600 * 1000 : lastDailyResetMs();
        long ageMin = (System.currentTimeMillis() - at) / 60000;
        if (at >= boundary)
        {
            s.put("state", "available");
            s.put("detail", "was waiting at login " + ageMin + " min ago (may already be claimed if you've done it since).");
        }
        else
        {
            s.put("state", "unknown");
            s.put("detail", "last seen available " + ageMin + " min ago, before the daily reset -- log in to refresh.");
        }
        return s;
    }

    /** Millis of the most recent 00:00 UTC (OSRS daily reset). */
    private static long lastDailyResetMs()
    {
        long dayMs = 24L * 3600 * 1000;
        return (System.currentTimeMillis() / dayMs) * dayMs;
    }
}
