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

### Bucket API — the primary structured-data channel

The wiki exposes a **public SQL-like structured data API** (Weird Gloop's Bucket
extension), no scraping needed:

```
GET https://oldschool.runescape.wiki/api.php?action=bucket&format=json&query=<lua>
  e.g. query=bucket('combat_achievement').select('name','monster','tier','type','task').limit(5000).run()
```

Returns clean JSON rows. **50 bucket tables exist** (`?action=query&list=allpages&apnamespace=9592`).
Verified-useful ones for planning:

| Bucket | Fields (partial) | Use | Verified |
|--------|------------------|-----|----------|
| `combat_achievement` | name, monster, tier, type, task | **655 CA tasks** — resurrects the Combat Achievements feature from a clean source | ✅ 655 rows pulled |
| `money_making_guide` | value, recurring, json | GP/hr methods → **Layer 3** (json holds inputs/outputs; needs parsing) | ✅ exists |
| `recommended_equipment` | json | BiS gear per activity — strong for gear advice | ✅ exists |
| `quest` | requirements, items_required, enemies_to_defeat, official_length/difficulty, json | quest metadata (requirements field is HTML — prefer Questreq/data for clean reqs) | ✅ fields read |
| `infobox_monster` | (monster stats) | could replace `get_npc_info` wiki scraping | listed |
| `infobox_item`, `infobox_bonuses` | (item + equip stats) | could replace `EquipmentStatsService` scraping | listed |
| `dropsline`, `drop_table_sources` | (drops) | could replace `DropTableService` scraping | listed |

### Design stance: a self-describing Bucket *gateway*, not just wrappers

Rather than only hand-writing narrow tools, expose Bucket **generically** so the
AI can query the wiki's structured data for things we never anticipated. Three
tools, all self-describing so the model can discover schema unaided:

1. **`wiki_list_buckets`** → the ~50 table names (live via
   `?action=query&list=allpages&apnamespace=9592`, cached).
2. **`wiki_bucket_schema`** (arg `bucket`) → that table's fields + types, from
   `Bucket:<Name>?action=raw` (clean JSON, verified `{field:{type}}`).
3. **`wiki_bucket_query`** (args `bucket`, `select[]`, `where{}`, `limit`,
   optional `order`) → the plugin builds the validated Lua string, runs it
   against `action=bucket`, returns rows. Optional `raw` Lua escape hatch for
   advanced queries (operators/joins), same guardrails.

Workflow the AI follows: `list_buckets` → `bucket_schema(X)` → `bucket_query(X, …)`.
The `INSTRUCTIONS` field teaches this discovery loop and points at the common
tables (combat_achievement, money_making_guide, recommended_equipment,
infobox_monster/item/bonuses, dropsline).

**Guardrails (the gateway is the trust boundary):**
- Read-only — Bucket is a GET/query API; no writes exist.
- `limit` capped (default ~50, max ~500) and a response **byte cap** so a wide
  query can't blow the context window.
- Values are escaped/encoded when building the Lua string (avoid breakage).
- Cache with a TTL (reuse the OkHttp pattern in `WikiPriceService`).
- **Runs OFF the game thread.** Current `dispatchTool` routes every call through
  `clientThread.invokeLater` with a 5s budget; network calls must not. Add a
  separate dispatch path for network tools (no game state needed anyway).
- Bucket rows are **untrusted third-party data** (esp. free-text buckets like
  transcript/update): treated as data, never instructions; tools have no side
  effects. Account data still comes from the live in-game tools, not the wiki.

Thin convenience wrappers (e.g. `get_combat_achievements` filtered by tier) stay
worthwhile for hot, size-sensitive paths, but they become *shortcuts over the
same gateway* rather than the only way in.

### AI guidance — routing cheat-sheet (so it doesn't discovery-loop every time)

Bake a compact **intent → tool/bucket** map into the `INSTRUCTIONS` field, plus a
fuller version as an on-demand MCP resource (`osrs://guide`). The discovery loop
(`wiki_list_buckets` → `wiki_bucket_schema` → `wiki_bucket_query`) is the
**fallback for the unknown**, not the default path.

**Golden rule:** the player's own state → **live in-game tools**; general game
knowledge → **Bucket**. Never query the wiki for the player's character data.

**Routing table (intent → where):**

| The AI wants… | Use |
|---------------|-----|
| Player stats / gear / inventory / bank / quests / diaries / slayer | live tools (`get_all`, `get_player_stats`, `get_bank_*`, `get_quest_states`, `get_diary_states/requirements`) |
| "What next?" | `get_next_goals`, then `get_diary_requirements` |
| Combat achievements (all/by tier/by boss) | `get_combat_achievements` wrapper → else `bucket('combat_achievement')` |
| Monster stats / weakness / max hit / slayer level | `bucket('infobox_monster')` (fields incl. `combat_level, hitpoints, max_hit, slayer_level, elemental_weakness, *_defence_bonus, attack_style, attack_speed`) |
| Item info / GE buy limit / alch value / weight | `bucket('infobox_item')` (`item_id, buy_limit, high_alchemy_value, value, weight, tradeable`) |
| Equipment bonuses by slot | `bucket('infobox_bonuses')` (`equipment_slot, *_attack_bonus, *_defence_bonus, strength_bonus, ranged_strength_bonus, magic_damage_bonus, prayer_bonus, weapon_attack_speed`) — keyed by `page_name` = item name |
| BiS gear for content | `bucket('recommended_equipment')` (detail in `json`) |
| Drop tables | `bucket('dropsline')` (`item_name, drop_json, rare_drop_table`) — or existing `get_drop_table` |
| Money-making methods / GP-hr | `bucket('money_making_guide')` (`value, recurring, json`) |
| Item creation / materials | `bucket('recipe')` (`uses_material, uses_skill, production_json`) |
| Live GE prices / trends / flips | `get_item_prices`, `get_price_trends`, `get_flip_suggestions` |
| Quest requirements + XP rewards (planning) | `get_quest_rewards` (baked Questreq + Experience rewards) |

**Query hygiene the guidance must state:** always pass `select` (never fetch all
fields) and a small `limit`; filter with `where('page_name', '<Name>')` (the
implicit per-row key) or a specific field; some buckets put the real detail in a
`json`/`*_json` field that must be parsed. Prefer a thin wrapper when one exists.

### Other sources (cleaner than the equivalent bucket)

| Source | URL | Gives | Format |
|--------|-----|-------|--------|
| **Module:Questreq/data** | `.../Module:Questreq/data?action=raw` | **All ~205 quests'** prereq quests + skill requirements (`ironman`/`boostable` modifiers) — cleaner than the `quest` bucket's HTML `requirements` field | Lua table |
| **Quests/Experience rewards** | `.../Quests/Experience_rewards` | Fixed XP rewards per quest/skill + a "Skill choice"/lamp section (DT2 100k×3, RFD 20k to any 50+) | Wiki tables |
| **Optimal Quest Guide** (optional) | `.../Optimal_quest_guide` | Canonical quest *ordering* — a planning prior | Ordered list |

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

## Reinstated — `get_combat_achievements`

Previously dropped for lack of a verifiable source; the `combat_achievement`
bucket (655 tasks with tier/monster/type/task) is that source. Tool returns tasks
grouped by tier and boss; combine with the player's live tier progress. Note the
bucket gives task *definitions*, not per-task completion — pair with in-game
completion varbits if per-task done-state is needed later.

## Optional — `get_optimal_quest_route`

Expose the wiki's Optimal Quest Guide ordering as a **prior** so the LLM anchors
on the community-accepted route rather than inventing one, then adapts it to the
player's live state and goal.

---

## Pre-build blockers (settle these first)

1. **Verify the existing base live.** `get_diary_requirements`, `get_next_goals`,
   the `instructions`/`prompts` — all committed but **never run**. In particular
   `get_diary_requirements`/`get_next_goals` instantiate RuneLite *internal*
   classes (`...plugins.achievementdiary.diaries.*`) and reflect on a
   package-private `DiaryRequirement`. It compiled, but sideloaded-plugin
   classloader visibility + `setAccessible` on another package could fail at
   runtime — and the code swallows exceptions, so failure looks like empty data.
   Restart the dev client and confirm real rows before building further.
2. **Threading refactor (architectural).** `handleToolCall` runs *every* tool on
   the client thread with a 5s budget; existing network tools (`get_drop_table`,
   `get_npc_info`, `get_bis_comparison`) do blocking `.execute()`, so a cold wiki
   fetch already freezes the game thread up to 5s. Split dispatch: game-state
   tools on the client thread; network tools (incl. the Bucket gateway) on the
   HTTP pool thread. Hybrids (read game state → then fetch, e.g.
   `get_bis_comparison`, `get_flip_suggestions`) read state on the client thread,
   release it, then fetch off-thread. Do this before adding the gateway.
3. **Reuse, confirmed available:** inject the existing `OkHttpClient` + `Gson` and
   the `USER_AGENT` (wiki 403s an empty UA — verified). No new HTTP client.
4. **Decided (Tom): personal build**, not an upstream PR. Build directly for this
   account; no need to keep diffs minimal or match the author's style/config
   conventions. Can still cherry-pick to a PR later if desired.

## Build order & tracking

0. ✅ **Bucket gateway** (`wiki_list_buckets`, `wiki_bucket_schema`,
   `wiki_bucket_query`) with guardrails + off-game-thread dispatch, and the
   `INSTRUCTIONS` discovery loop. Foundational — later tools reuse it.
   `get_combat_achievements` as the first thin wrapper. **Verified live.**
1. ✅ **Generator + bundled quest data** (`tools/gen-quest-data.mjs` →
   `quest_data.json`, 196 quests) for requirements (Questreq) + XP rewards;
   values spot-checked vs wiki. Refresh wired into `Update OSRS MCP.bat`.
2. ✅ Layer 1 `get_quest_rewards` — loads the resource, live `meets_requirements`
   (skills/quests/QP, ironman-aware) + `blocked_by`. **Needs live verification.**
3. ✅ Layer 2 `project_plan` simulator on top of Layer 1's graph. **Needs live
   verification.**
4. ✅ Layer 3 `get_training_methods` — curated dated XP/hr table (no clean
   machine-readable source), live-annotated. **Needs live verification.**
5. Optional `get_optimal_quest_route` — not built.

**Live verification still owed** (require a logged-in dev client): `get_quest_rewards`,
`project_plan`, `get_training_methods`. The 4 gateway tools + tools/list registration
are already verified live. Restart the dev client logged in, then confirm each returns
correct account-accurate data.

After each layer: register in `McpServer` (`dispatchTool` + `buildToolsList`),
extend the `INSTRUCTIONS` planning rubric, rebuild (`gradlew jar`), copy to
`~/.runelite/sideloaded-plugins`, restart the dev client, and verify the tool
returns correct live data before claiming done.

Branch: `feature/mcp-enhancements`.
