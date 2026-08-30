# OSRS MCP — Multi-step planning roadmap

Goal: let an AI client plan **several steps ahead** toward a stated objective
("max the account", "get my fire cape", "finish the hard diaries") — reasoning
across dependencies like *"do quest X → it grants this XP and unlocks this
training method → XP/hr rises → quest Y becomes eligible"* — rather than the
current single-step, greedy `get_next_goals`.

## Design principle

The plugin is a **truthful sensor + exact calculator**. The **LLM is the
planner**. We do **not** build a hardcoded optimal solver:

- "Optimally max an account" is a hard combinatorial scheduling problem.
- A hardcoded solver is rigid (can't absorb "I hate Agility" / "2 hrs a night"),
  large to build, and silently wrong after a game update.
- Community tools already solve the optimal-route problem.

The LLM + a dependency graph + a deterministic simulator gets ~90% of an
optimizer **and** explains its reasoning and adapts to soft goals.

Reliability rule (see memory `osrs-mcp-verify-before-claiming`): every reward /
requirement value must be verified against the OSRS Wiki as it is added. Prefer
live-checked data (`Requirement.satisfiesRequirement(client)`) over static
tables where possible. Flag static tables and wiki scraping as drift risks.

---

## Layer 1 — Quest graph tool  `get_quest_rewards`  *(build first)*

The enabling dataset. Turns the model from single-step to multi-step by making
every dependency edge an explicit fact instead of a memory.

**Tool:** `get_quest_rewards` (no args) → list, one entry per covered quest:

```json
{
  "quest": "Recipe for Disaster",
  "state": "not_started",              // live, from Quest.getState(client)
  "requirements": {
    "quests": ["Cook's Assistant", "..."],
    "skills": { "cooking": 70, "..." : 0 }
  },
  "xp_rewards": { "cooking": 0 },       // per-skill XP granted on completion
  "unlocks": ["Barrows gloves", "access to ..."],
  "meets_requirements": true            // live-checked where possible
}
```

**Scope:** start with a curated ~40 highest-impact quests (DT2, Song of the
Elves, Recipe for Disaster + subquests, Monkey Madness I/II, the elite-diary
gating quests, big XP-lamp quests). Not all 160 — value is concentrated.

**Data source options (pick per reliability):**
- Curated static table in-plugin — most reliable, needs maintenance. **Preferred
  for v1.**
- Wiki `Special:Export` of each quest page's `{{Quest details}}` / `{{Quest
  rewards}}` templates — reuses the pattern in `DropTableService` /
  `EquipmentStatsService`; brittle, cache 24h. Consider for v2 breadth.

**Live cross-checks already available:** `Quest.getState(client)` for state;
`client.getRealSkillLevel(skill)` for skill checks. Quest *prerequisite* data is
NOT in the RuneLite API — must come from the curated table / wiki.

## Layer 2 — Deterministic simulator  `project_plan`  *(build second)*

The piece that makes the plan **trustworthy** — removes the LLM's multi-step
arithmetic weakness. Small and low-risk once Layer 1 exists.

**Tool:** `project_plan` with args:

```json
{
  "complete_quests": ["Monkey Madness II", "..."],
  "train": { "agility": 70, "herblore": 78 }   // target levels
}
```

Returns, computed exactly by the plugin from current live stats + Layer 1 data:

```json
{
  "resulting_levels": { "agility": 70, "herblore": 78, "cooking": 71 },
  "xp_gained_from_quests": { "cooking": 0, "..." : 0 },
  "xp_still_to_train": { "agility": 812345 },
  "newly_eligible_quests": ["Dragon Slayer II"],
  "newly_completable_diary_tiers": ["ardougne_hard"]
}
```

Deterministic: applies quest XP rewards onto current XP, recomputes levels via
`XP_TABLE`, then re-evaluates quest/diary requirements. The LLM proposes a plan,
calls this to verify each step's real consequences, and iterates.

## Layer 3 — Training method XP/hr  `get_training_methods`  *(build last)*

Lets the model reason about *time*, not just XP, and about unlocks that raise
XP/hr. **Driftiest / least reliable layer** — community-sourced numbers change.

**Tool:** `get_training_methods` (optional `skill` arg) → per method:

```json
{
  "skill": "agility", "method": "Hallowed Sepulchre",
  "xp_per_hour": 90000,
  "requirements": { "skills": { "agility": 72 }, "quests": [], "items": [] },
  "notes": "floors 1-5 at 92+"
}
```

Static curated table; mark clearly as approximate and dated. Only add after 1+2
are solid.

---

## Build order & tracking

1. Layer 1 `get_quest_rewards` (curated ~40 quests, wiki-verified).
2. Layer 2 `project_plan` simulator on top of Layer 1.
3. Layer 3 `get_training_methods` (approximate, dated).

After each layer: register the tool in `McpServer` (`dispatchTool` +
`buildToolsList`), extend the `INSTRUCTIONS` planning rubric, rebuild
(`gradlew jar`), copy to `~/.runelite/sideloaded-plugins`, restart the dev
client, and verify the new tool returns correct live data before claiming done.

Branch: `feature/mcp-enhancements`.
