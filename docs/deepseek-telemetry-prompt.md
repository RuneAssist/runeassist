# DeepSeek prompt — TelemetryService (opt-in local logger)

Scope: the data-capture plumbing only. Advice→outcome logging touches the chat panel
and will be wired separately (by Claude), so it is intentionally OUT of scope here.

Paste everything in the block below into DeepSeek.

---

```
You are adding an OPT-IN, anonymised local telemetry logger to a Java 11 RuneLite
plugin (package com.osrsmcp; Gson available; RuneLite APIs). It writes versioned JSONL
to disk so account data can be analysed later (real XP/hr, GE performance, account
trajectories). NO backend, NO network — local files only. It must be a no-op unless the
user has explicitly opted in.

WORKING DIRECTORY: C:\Users\ThomasHarrison\Documents\Claude\osrs-mcp-plugin

=== FILE 1: EDIT src/main/java/com/osrsmcp/OsrsMcpConfig.java ===
In the existing "Privacy" ConfigSection, append one item (match the existing style):
  - keyName "shareTelemetry", name "Contribute anonymous data (opt-in)",
    default false,
    description "Off by default. When on, RuneAssist logs anonymised gameplay data
    (XP gains, account snapshots, GE activity) to local files on your PC to improve
    future suggestions. Your RSN is hashed; nothing is uploaded."

=== FILE 2: CREATE src/main/java/com/osrsmcp/TelemetryService.java ===
@javax.inject.Singleton, @Slf4j. @Inject fields: OsrsMcpConfig config, com.google.gson.Gson gson.

Storage:
  - Base dir: new java.io.File(net.runelite.RuneLite.RUNELITE_DIR, "runeassist/telemetry").
    Create it lazily (mkdirs) on first write.
  - One file per record type per day: "<type>-yyyy-MM-dd.jsonl" (UTC date). Append mode,
    UTF-8, one compact JSON object per line.

Anonymisation:
  - private String acctHash(String rsn): SHA-256 hex of rsn.toLowerCase(Locale.ROOT).trim();
    return "anon" if rsn is null/blank. Cache the last {rsn -> hash} to avoid rehashing.
  - Never write the raw RSN or any other player's name anywhere.

Threading / IO:
  - All disk writes happen on a single daemon executor
    (Executors.newSingleThreadExecutor, thread name "runeassist-telemetry"), so callers
    (client thread / EDT) never block on IO. Public capture methods build a plain
    Map<String,Object> record on the calling thread, then submit the write.
  - Provide public void shutdown() { executor.shutdown(); } for plugin shutDown().

Gating: EVERY public capture method returns immediately if !config.shareTelemetry().

Common envelope on every record (a LinkedHashMap in this order):
  "v": 1                         // schema version — bump if fields change
  "type": <type string>
  "ts": System.currentTimeMillis()
  "acct": <acctHash or "anon">
...then the type-specific fields below.

Public capture methods (each takes ALREADY-EXTRACTED primitives — do NOT touch the
RuneLite Client inside TelemetryService; the plugin gathers live values on the client
thread and passes them in):

  // type "xp_gain"
  void logXpGain(String rsn, String skill, long xp, long delta, int level,
                 int x, int y, int plane)

  // type "account_snapshot"; skills is skillName -> [level, xp]
  void logAccountSnapshot(String rsn, int combatLevel, int totalLevel, int questPoints,
                          String accountType, int x, int y, int plane,
                          java.util.Map<String,long[]> skills)

  // type "ge_offer"
  void logGeOffer(String rsn, int slot, String state, int itemId, int price,
                  int totalQuantity, int quantitySold, int spent)

Write helper: private void write(String type, Map<String,Object> record) submits a task
that opens the day/type file in append mode and writes gson.toJson(record) + "\n".
Wrap IO in try/catch and log.warn on failure — telemetry must NEVER throw into callers.

=== FILE 3: EDIT src/main/java/com/osrsmcp/OsrsMcpPlugin.java ===
@Inject private TelemetryService telemetry;

Wire capture at the existing event hooks (gather live values on the client thread here,
then pass primitives to telemetry):

  a) XP gains — there is already an @Subscribe onStatChanged(StatChanged). Track the last
     seen XP per Skill in a Map<Skill,Long> field on the plugin. On each event:
        long prev = lastXp.getOrDefault(skill, -1L);
        long now  = client.getSkillExperience(skill);  // or event.getXp()
        lastXp.put(skill, now);
        if (prev >= 0 && now > prev && telemetry-enabled path) {
            WorldPoint wp = local player location (may be null -> use 0,0,0);
            telemetry.logXpGain(rsn(), skill.getName(), now, now - prev,
                event.getLevel(), x, y, plane);
        }
     (rsn() = config.shareUsername() ? player name : player name anyway — telemetry hashes
      it regardless; if you prefer, always pass the real name since it's hashed. Use the
      local player's name; "anon" if not logged in.)

  b) Account snapshot — add an @Subscribe onGameStateChanged already exists via the panel;
     add a snapshot on transition to LOGGED_IN, and a periodic one. For the periodic case
     add an @Subscribe onGameTick(GameTick) that increments a counter and every ~3000 ticks
     (~30 min) calls snapshot. Snapshot gathers, on the client thread:
        combat level, total level (client.getTotalLevel()), quest points
        (client.getVarpValue(VarPlayer.QUEST_POINTS) if available else skip -> pass 0),
        account type, location, and a Map<String,long[]> of every Skill except OVERALL to
        {level, xp}. Then telemetry.logAccountSnapshot(...).

  c) GE offers — add @Subscribe onGrandExchangeOfferChanged(GrandExchangeOfferChanged e):
        GrandExchangeOffer o = e.getOffer();
        telemetry.logGeOffer(rsn(), e.getSlot(), o.getState().name(), o.getItemId(),
            o.getPrice(), o.getTotalQuantity(), o.getQuantitySold(), o.getSpent());

  In shutDown(): telemetry.shutdown();

CONSTRAINTS
  - Java 11. Do NOT touch OsrsMcpChatPanel, CompanionAgent, McpServer, ToolRegistry, the
    LLM providers, or any *.json resource. (Advice-outcome logging is handled separately.)
  - TelemetryService must never call the RuneLite Client directly and must never throw.
  - Everything gated on config.shareTelemetry(); default OFF.
  - Keep records small and flat; compact JSON (no pretty printing).
  - Build must stay green (./gradlew jar).

OUTPUT
Full TelemetryService.java, plus diffs for OsrsMcpConfig.java and OsrsMcpPlugin.java.
Note anything where a RuneLite API name (e.g. quest-points varbit, getSkillExperience)
differs in this version so Claude can confirm it on verify.
```

---

## Claude's follow-up (not for DeepSeek)
- Wire **advice→outcome** logging into `OsrsMcpChatPanel` (one call in `onComplete`:
  question, `turnTools`, provider, tokens, answer length) — kept out of the DeepSeek
  scope to avoid UI edits.
- Verify: opt-in OFF writes nothing; opt-in ON produces well-formed JSONL; RSN never
  appears raw; no client-thread IO stalls.
- Consider a salt on the RSN hash before any upload feature exists.
