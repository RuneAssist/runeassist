package com.osrsmcp;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;

import javax.inject.Singleton;
import java.util.EnumMap;
import java.util.Map;

/**
 * Holds the per-session baseline (starting XP per skill and the time the session
 * began) so the {@code get_session_summary} tool can report what the player has
 * done since logging in this session.
 */
@Slf4j
@Singleton
public class SessionTracker
{
    private final Map<Skill, Integer> startXp = new EnumMap<>(Skill.class);
    private volatile long startTimeMs = 0;
    private volatile boolean captured = false;

    /**
     * Called when a new session begins (login). Clears the baseline; the real
     * snapshot is taken on the next tick when skill data is guaranteed loaded.
     */
    public void onLogin()
    {
        startXp.clear();
        captured = false;
        startTimeMs = 0;
    }

    /**
     * Called on a game tick with the live Client. Captures the baseline exactly
     * once per session (skills are reliably loaded by the first tick after login).
     */
    public void captureIfNeeded(Client client)
    {
        if (captured || client.getGameState() != GameState.LOGGED_IN) return;
        for (Skill s : Skill.values())
        {
            if (s == Skill.OVERALL) continue;
            startXp.put(s, client.getSkillExperience(s));
        }
        startTimeMs = System.currentTimeMillis();
        captured = true;
    }

    public boolean isCaptured() { return captured; }
    public long startTimeMs()   { return startTimeMs; }
    public Integer startXp(Skill s) { return startXp.get(s); }
}
