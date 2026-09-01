package com.osrsmcp;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RuneAssist goals / tasks. A persisted checklist the player or the AI can manage. Each
 * task is either MANUAL (ticked by hand) or AUTO (a live metric target — skill level,
 * total level, combat level — that ticks itself when reached). Persisted in RuneLite
 * config so it survives restarts.
 *
 * <p>Metric-reading methods use the RuneLite Client and must be called on the client
 * thread (the MCP task tools and onStatChanged both are).
 */
@Slf4j
@Singleton
public class TaskService
{
    @Inject private Client client;
    @Inject private ConfigManager configManager;
    @Inject private Gson gson;

    private final List<Map<String, Object>> tasks = Collections.synchronizedList(new ArrayList<>());
    private long nextId = 1;
    private boolean loaded = false;

    private synchronized void ensureLoaded()
    {
        if (loaded) return;
        loaded = true;
        try
        {
            String json = configManager.getConfiguration("osrsmcp", "tasks");
            if (json != null && !json.isEmpty())
            {
                List<Map<String, Object>> saved = gson.fromJson(json,
                    new TypeToken<List<Map<String, Object>>>(){}.getType());
                if (saved != null)
                {
                    tasks.addAll(saved);
                    for (Map<String, Object> t : tasks)
                        nextId = Math.max(nextId, ((Number) t.get("id")).longValue() + 1);
                }
            }
        }
        catch (Exception e) { log.warn("Task load failed: {}", e.getMessage()); }
    }

    private void save()
    {
        try { configManager.setConfiguration("osrsmcp", "tasks", gson.toJson(tasks)); }
        catch (Exception e) { log.warn("Task save failed: {}", e.getMessage()); }
    }

    // ── mutations ─────────────────────────────────────────────────────────────

    /** Add a task. metric null/blank => manual; else an AUTO task ticked when metric >= target. */
    public Map<String, Object> add(String text, String metric, int target)
    {
        ensureLoaded();
        if (text == null || text.isBlank())
        {
            Map<String, Object> e = new LinkedHashMap<>(); e.put("error", "Provide task text."); return e;
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", nextId++);
        t.put("text", text.trim());
        boolean auto = metric != null && !metric.isBlank();
        t.put("auto", auto);
        if (auto) { t.put("metric", metric.toLowerCase().trim()); t.put("target", target); }
        t.put("done", false);
        t.put("createdAt", System.currentTimeMillis());
        tasks.add(t);
        evaluate();
        save();
        return list();
    }

    public Map<String, Object> complete(long id)
    {
        ensureLoaded();
        synchronized (tasks)
        {
            for (Map<String, Object> t : tasks)
                if (((Number) t.get("id")).longValue() == id)
                { t.put("done", true); t.put("doneAt", System.currentTimeMillis()); }
        }
        save();
        return list();
    }

    public Map<String, Object> remove(long id)
    {
        ensureLoaded();
        tasks.removeIf(t -> ((Number) t.get("id")).longValue() == id);
        save();
        return list();
    }

    // ── auto-completion ─────────────────────────────────────────────────────────

    /** Tick any AUTO task whose live metric has reached its target. Client thread only. */
    public void evaluate()
    {
        ensureLoaded();
        if (client.getGameState() != GameState.LOGGED_IN) return;
        boolean changed = false;
        synchronized (tasks)
        {
            for (Map<String, Object> t : tasks)
            {
                if (Boolean.TRUE.equals(t.get("done")) || !Boolean.TRUE.equals(t.get("auto"))) continue;
                int cur = liveMetric((String) t.get("metric"));
                int target = ((Number) t.get("target")).intValue();
                if (cur >= 0 && cur >= target)
                { t.put("done", true); t.put("doneAt", System.currentTimeMillis()); changed = true; }
            }
        }
        if (changed) save();
    }

    private int liveMetric(String m)
    {
        if (m == null) return -1;
        switch (m)
        {
            case "total_level":  return client.getTotalLevel();
            case "combat_level": { net.runelite.api.Player p = client.getLocalPlayer(); return p != null ? p.getCombatLevel() : -1; }
            default:
                try { return client.getRealSkillLevel(Skill.valueOf(m.toUpperCase())); }
                catch (Exception e) { return -1; }
        }
    }

    // ── reads ─────────────────────────────────────────────────────────────────

    public Map<String, Object> list()
    {
        ensureLoaded();
        evaluate();
        List<Map<String, Object>> active = new ArrayList<>();
        List<Map<String, Object>> done   = new ArrayList<>();
        synchronized (tasks)
        {
            for (Map<String, Object> t : tasks)
                (Boolean.TRUE.equals(t.get("done")) ? done : active).add(new LinkedHashMap<>(t));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", active);
        out.put("done", done);
        out.put("active_count", active.size());
        out.put("note", "AUTO tasks tick themselves when their metric target is reached; MANUAL "
            + "tasks are completed with complete_task. Metrics: a skill name, total_level, combat_level.");
        return out;
    }

    /** Active task texts for the on-screen/panel widgets. */
    public List<String> activeTexts(int max)
    {
        ensureLoaded();
        List<String> r = new ArrayList<>();
        synchronized (tasks)
        {
            for (Map<String, Object> t : tasks)
                if (!Boolean.TRUE.equals(t.get("done")))
                { r.add((String) t.get("text")); if (r.size() >= max) break; }
        }
        return r;
    }
}
