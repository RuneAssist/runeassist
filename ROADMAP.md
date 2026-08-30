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

**Reuse community data instead of reinventing it.** The OSRS Wiki already
maintains the dependency data we need, machine-readably. We consume it; we do not
hand-curate a parallel copy.

Reliability rule (memory `osrs-mcp-verify-before-claiming`): community data is
maintained and current (lower drift than a hand table) but still versioned —
snapshot + refresh, and verify representative values against the live wiki.

---

## Community data sources (verified fetchable)

| Source | URL | Gives | Format |
|--------|-----|-------|--------|
| **Module:Questreq/data** | `oldschool.runescape.wiki/w/Module:Questreq/data?action=raw` | **All ~205 quests'** prerequisite quests + skill requirements (with `ironman`/`boostable` modifiers) | Lua table, regular shape |
| **Quests/Experience rewards** | `oldschool.runescape.wiki/w/Quests/Experience_rewards` | Fixed XP rewards per quest per skill, **plus** a "Skill choice" section for lamps (e.g. DT2 100k×3, RFD 20k to any >50) | Wiki tables by skill |
| **Optimal Quest Guide** (optional) | `oldschool.runescape.wiki/w/Optimal_quest_guide` | Canonical community quest *ordering* — a strong planning prior | Ordered wiki list |

**Not used — WikiSync.** WikiSync *uploads* a character's data to the wiki so
wiki pages can personalise themselves. We already read the live account locally
via the plugin, so we don't need to round-trip through the wiki. (WikiSync would
only matter if we wanted the wiki to render a personalised page for us.)

**Genuinely still hand-curated:** quest **unlocks** (barrows gloves, area/method
access) — these are prose on each quest page with no clean structured source.
Keep this list small and focused on the marquee unlocks that drive decisions.

## Data delivery: bake, don't live-fetch on the game thread

A build-time generator fetches the sources once and emits a bundled JSON
resource; the plugin loads that resource at startup. This avoids Lua-in-Java
parsing, avoids per-request HTTP on the client thread, and works offline.
Refreshed on demand (wired into `Update OSRS MCP.bat`), so data currency is a
deliberate, visible step — not a silent runtime dependency.

- Generator: `tools/gen-quest-data.mjs` (Node is available on this PC).
  Fetches Questreq/data + Experience rewards, normalises to JSON, writes
  `src/main/resources/com/osrsmcp/quest_data.json` (requirements + XP rewards),
  and merges a small hand-maintained `quest_unlocks.json`.
- Plugin loads the resource once; all planning tools read from it in memory.

---

## Layer 1 — Quest data tool  `get_quest_rewards`  *(build first)*

Now mostly a **consumer of community data**, not a curation effort.

**Tool:** `get_quest_rewards` (no args) → one entry per quest:

```json
{
  "quest": "Dragon Slayer II",
  "state": "not_started",                 // live, Quest.getState(client)
  "requirements": {
    "quests": ["Legends' Quest", "..."],  // from Questreq/data
    "skills": { "magic": 75, "smithing": 70, "...": 0 }
  },
  "xp_rewards": { "..." : 0 },            // fixed rewards, Experience rewards page
  "xp_choice": [{ "amount": 25000, "constraint": "any skill 50+", "count": 2 }],
  "unlocks": ["Myths' Guild", "Ava's assembler path"],  // small hand list
  "meets_requirements": true              // live-checked: skills via
                                          // getRealSkillLevel, quests via getState
}
```

**Coverage:** requirements + XP for **all** quests (from the wiki data), unlocks
for the marquee set. The `meets_requirements` flag is computed live against the
account so it is always account-accurate.

## Layer 2 — Deterministic simulator  `project_plan`  *(build second)*

Makes a plan **trustworthy** by doing the multi-step arithmetic exactly.

**Tool:** `project_plan` args `{ "complete_quests": [...], "train": { "agility": 70 } }`
→ computed from live stats + Layer 1 data:

```json
{
  "resulting_levels": { "agility": 70, "cooking": 71 },
  "xp_gained_from_quests": { "cooking": 0 },
  "xp_still_to_train": { "agility": 812345 },
  "newly_eligible_quests": ["Monkey Madness II"],
  "newly_completable_diary_tiers": ["ardougne_hard"]
}
```

Deterministic: apply quest XP onto current XP, recompute levels via `XP_TABLE`,
re-evaluate the Questreq requirement graph + diary requirements. The LLM proposes
an ordering, calls this to verify each step, and iterates. Requirement
re-evaluation reuses the full Questreq graph loaded in Layer 1, so "newly
eligible" is exact.

## Layer 3 — Training method XP/hr  `get_training_methods`  *(build last)*

Lets the model reason about **time**, not just XP, and about unlocks that raise
XP/hr. Driftiest layer; no clean community machine-readable source, so this stays
a small curated table, clearly marked approximate + dated.

```json
{ "skill": "agility", "method": "Hallowed Sepulchre", "xp_per_hour": 90000,
  "requirements": { "skills": { "agility": 72 } }, "notes": "floors 1-5 at 92+" }
```

## Optional — `get_optimal_quest_route`

Expose the wiki's Optimal Quest Guide ordering as a **prior** so the LLM anchors
on the community-accepted route rather than inventing one, then adapts it to the
player's live state and goal.

---

## Build order & tracking

0. **Generator + bundled data** (`tools/gen-quest-data.mjs` →
   `quest_data.json`), verify a few values against the live wiki.
1. Layer 1 `get_quest_rewards` — load the resource, add live `meets_requirements`.
2. Layer 2 `project_plan` simulator on top of Layer 1's graph.
3. Layer 3 `get_training_methods` (approximate, dated).
4. Optional `get_optimal_quest_route`.

After each layer: register in `McpServer` (`dispatchTool` + `buildToolsList`),
extend the `INSTRUCTIONS` planning rubric, rebuild (`gradlew jar`), copy to
`~/.runelite/sideloaded-plugins`, restart the dev client, and verify the tool
returns correct live data before claiming done.

Branch: `feature/mcp-enhancements`.
