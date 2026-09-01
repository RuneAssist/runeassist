package com.osrsmcp;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Proactive nudges (Tier 1): short, passive tips posted into the chat panel when
 * something noteworthy happens. Design + rules in docs/nudges-design.md.
 *
 * <p>The whole risk is spam, so this is conservative by construction: at most one nudge
 * per {@value #MIN_GAP_MS}ms, a daily cap, per-session dedup, and a post-login grace
 * window. Nudges NEVER interrupt play — they only append a dim line to the panel, seen
 * when the player next opens it. Gated on {@code proactiveNudges} (default ON).
 */
@Slf4j
@Singleton
public class NudgeService
{
    private static final long MIN_GAP_MS     = 10 * 60 * 1000L; // >= 10 min between nudges
    private static final int  DAILY_CAP      = 6;
    private static final long LOGIN_GRACE_MS = 30 * 1000L;      // suppress non-daily for 30s after login
    private static final long DAY_MS         = 24 * 60 * 60 * 1000L;

    @Inject private OsrsMcpConfig config;
    @Inject private OsrsMcpChatPanel panel;

    private final Map<Skill, Integer> lastLevel = new EnumMap<>(Skill.class);
    private final Set<String> firedKeys = new HashSet<>();
    private long loginMs     = 0;
    private long lastNudgeMs = 0;
    private long dayStartMs   = 0;
    private int  nudgesToday  = 0;

    private boolean enabled() { return config != null && config.proactiveNudges(); }

    /** New session: reset per-session state so nudges can fire again. */
    public void onLogin()
    {
        loginMs = System.currentTimeMillis();
        firedKeys.clear();
        lastLevel.clear();
    }

    /** From onStatChanged (client thread). Fires only on a genuine level-up to 99 (v1). */
    public void onLevelUp(Skill skill, int level)
    {
        if (!enabled() || skill == null) return;
        Integer prev = lastLevel.put(skill, level);
        if (prev != null && level > prev && level == 99)
            fire("lvl99:" + skill.getName(), "Grats — " + cap(skill.getName()) + " is now 99!", false);
    }

    /** From onChatMessage. Catches the login "waiting to be collected" dailies. */
    public void onChatMessage(String message)
    {
        if (!enabled() || message == null) return;
        if (message.toLowerCase().contains("waiting to be collected"))
            fire("daily-login",
                "You've got dailies waiting to collect. Ask me \"what are my dailies?\" for the list.",
                true); // dailies happen at login, so bypass the grace window
    }

    private void fire(String key, String text, boolean bypassGrace)
    {
        if (!enabled() || firedKeys.contains(key)) return;
        long now = System.currentTimeMillis();
        if (!bypassGrace && now - loginMs < LOGIN_GRACE_MS) return;
        if (now - lastNudgeMs < MIN_GAP_MS) return;
        if (dayStartMs == 0 || now - dayStartMs > DAY_MS) { dayStartMs = now; nudgesToday = 0; }
        if (nudgesToday >= DAILY_CAP) return;

        firedKeys.add(key);
        lastNudgeMs = now;
        nudgesToday++;
        try { panel.addNudge(text); } catch (Exception e) { log.warn("Nudge post failed", e); }
    }

    private static String cap(String s)
    {
        return (s == null || s.isEmpty()) ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
