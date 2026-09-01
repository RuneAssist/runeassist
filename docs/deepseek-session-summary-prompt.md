# DeepSeek prompt — get_session_summary tool

Adds a "what did I get done this session" tool: per-skill XP gained since login, total,
and time played. Mechanical; no UI, no agent/provider changes.

Verify point (Claude): confirm the start snapshot is captured AFTER skills load (first
GameTick post-login), not at the stale LOGGED_IN instant.

---

```
You are adding a new read-only tool, get_session_summary, to a Java 11 RuneLite plugin
(package com.osrsmcp; Gson; RuneLite APIs). It reports what the player has done since
they logged in this session: XP gained per skill, total XP, levels gained, and minutes
played. NO UI, NO network, NO changes to the chat panel / agent / LLM providers.

WORKING DIRECTORY: C:\Users\ThomasHarrison\Documents\Claude\osrs-mcp-plugin

=== FILE 1: CREATE src/main/java/com/osrsmcp/SessionTracker.java ===
@javax.inject.Singleton, @Slf4j. Holds the per-session baseline.
Fields:
  private final java.util.Map<net.runelite.api.Skill, Integer> startXp = new java.util.EnumMap<>(net.runelite.api.Skill.class);
  private volatile long startTimeMs = 0;
  private volatile boolean captured = false;

Methods:
  // Called when a new session begins (login). Clears the baseline; the real snapshot is
  // taken on the next tick when skill data is guaranteed loaded.
  public void onLogin() { startXp.clear(); captured = false; startTimeMs = 0; }

  // Called on a game tick with the live Client. Captures the baseline exactly once per
  // session (skills are reliably loaded by the first tick after login).
  public void captureIfNeeded(net.runelite.api.Client client) {
      if (captured || client.getGameState() != net.runelite.api.GameState.LOGGED_IN) return;
      for (net.runelite.api.Skill s : net.runelite.api.Skill.values()) {
          if (s == net.runelite.api.Skill.OVERALL) continue;
          startXp.put(s, client.getSkillExperience(s));
      }
      startTimeMs = System.currentTimeMillis();
      captured = true;
  }

  public boolean isCaptured() { return captured; }
  public long startTimeMs()   { return startTimeMs; }
  public Integer startXp(net.runelite.api.Skill s) { return startXp.get(s); }

=== FILE 2: EDIT src/main/java/com/osrsmcp/PlayerDataService.java ===
@Inject SessionTracker sessionTracker; (add to existing @Inject fields)
Add a public builder that mirrors the style of the other build* methods (return a
LinkedHashMap; use errorMap("...") for failure — that helper already exists in this class;
use isLoggedIn() which also already exists):

  public Map<String, Object> buildSessionSummary() {
      if (!isLoggedIn()) return errorMap("Player is not logged in");
      if (!sessionTracker.isCaptured()) return errorMap("Session baseline not captured yet; try again in a moment");

      Map<String, Object> result = new LinkedHashMap<>();
      long minutes = (System.currentTimeMillis() - sessionTracker.startTimeMs()) / 60000;
      result.put("session_minutes", minutes);

      long totalGained = 0;
      java.util.List<Map<String,Object>> perSkill = new java.util.ArrayList<>();
      for (net.runelite.api.Skill s : net.runelite.api.Skill.values()) {
          if (s == net.runelite.api.Skill.OVERALL) continue;
          Integer start = sessionTracker.startXp(s);
          if (start == null) continue;
          int now = client.getSkillExperience(s);
          int gained = now - start;
          if (gained <= 0) continue;
          int startLvl = net.runelite.api.Experience.getLevelForXp(start);
          int nowLvl   = net.runelite.api.Experience.getLevelForXp(now);
          Map<String,Object> row = new LinkedHashMap<>();
          row.put("skill", s.getName());
          row.put("xp_gained", gained);
          row.put("levels_gained", nowLvl - startLvl);
          perSkill.add(row);
          totalGained += gained;
      }
      // sort perSkill by xp_gained desc
      perSkill.sort((a,b) -> Long.compare(((Number)b.get("xp_gained")).longValue(),
                                          ((Number)a.get("xp_gained")).longValue()));
      result.put("total_xp_gained", totalGained);
      result.put("skills", perSkill);
      if (perSkill.isEmpty()) result.put("note", "No XP gained yet this session.");
      return result;
  }
(Use the plugin's existing `client` field — this method runs on the client thread. If
Experience.getLevelForXp has a different name in this RuneLite version, use the existing
level-from-xp helper already used elsewhere in the codebase and note it in your output.)

=== FILE 3: EDIT src/main/java/com/osrsmcp/ToolRegistry.java ===
a) Register the tool in getToolsListResult() alongside the other tools.add(buildTool(...))
   lines (no arguments, so use the no-schema buildTool helper):
     tools.add(buildTool("get_session_summary",
        "What the player has done since logging in this session: XP gained per skill "
        + "(sorted), total XP gained, levels gained, and minutes played. Great for a "
        + "'what did I get done' recap. Returns an error until the session baseline is "
        + "captured (first tick after login)."));
b) Add a case in dispatchTool(...) (the client-thread dispatch switch):
     case "get_session_summary": return playerDataService.buildSessionSummary();

=== FILE 4: EDIT src/main/java/com/osrsmcp/OsrsMcpPlugin.java ===
@Inject private SessionTracker sessionTracker;

- In the existing @Subscribe onGameStateChanged(GameStateChanged event): when
  event.getGameState() == net.runelite.api.GameState.LOGGED_IN, call sessionTracker.onLogin().
  (Keep the existing panel.updateGameState(...) call.)

- Capture the baseline on a game tick. If an @Subscribe onGameTick(GameTick) method
  ALREADY EXISTS in this class (another change may have added one), ADD this line inside
  it rather than creating a duplicate method:
        sessionTracker.captureIfNeeded(client);
  If no onGameTick exists yet, add:
        @Subscribe
        public void onGameTick(net.runelite.api.events.GameTick event) {
            sessionTracker.captureIfNeeded(client);
        }

CONSTRAINTS
  - Java 11. Do NOT modify OsrsMcpChatPanel, CompanionAgent, McpServer's transport, the
    LLM providers, or any *.json resource.
  - Match the surrounding code style (LinkedHashMap, errorMap, isLoggedIn, buildTool).
  - Build must stay green (./gradlew jar).

OUTPUT
Full SessionTracker.java, plus diffs for PlayerDataService.java, ToolRegistry.java,
OsrsMcpPlugin.java. Note any RuneLite API name you were unsure of (getSkillExperience,
Experience.getLevelForXp, GameTick import path) so Claude can confirm on verify.
```
