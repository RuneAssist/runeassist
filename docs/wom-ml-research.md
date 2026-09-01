# Wise Old Man as an ML bootstrap source — research findings

_Researched 2026-09-01 by hitting the live WOM v2 API. This is Track B from IDEAS.md:
prototype the rate/recommender models on PUBLIC data before we have our own userbase._

## Access (confirmed live)
- **Base:** `https://api.wiseoldman.net/v2`
- **User-Agent REQUIRED** — no UA returns **403**. A descriptive UA (as the plugin
  already sends) works.
- **Rate limit:** **20 requests / 60s** unauthenticated (from `ratelimit-limit: 20`,
  `ratelimit-reset: 60` headers). => ~28.8k req/day if paced. An **API key** (WOM
  Patreon) raises this — confirm the exact ceiling before planning a large crawl.
- Open source (github.com/wise-old-man/wise-old-man): **self-hosting the stack or asking
  about a DB dump is the scalable path** if we need millions of rows — avoids crawling.

## Data available per player (confirmed shapes)
`GET /players/{username}` returns:
- **Identity/segmentation:** `type` (regular/ironman/hardcore/ultimate/…), `build`
  (main/lvl3/f2p/1def/…), `country`, `patron`, `combatLevel`, `status`.
- **Aggregates (great ML features):** `exp` (total), `ehp` (efficient hours played),
  `ehb` (efficient hours bossing), `ttm` (time-to-max), `tt200m`.
- **Timestamps:** `registeredAt`, `updatedAt`, `lastChangedAt`, `lastImportedAt`.
- **`latestSnapshot.data`:**
  - `skills` — **25 skills**, each `{metric, experience, rank, level, ehp}`
  - `bosses` — **71 bosses**, each `{metric, kills, rank, ehb}`  (−1 = untracked)
  - `activities` — each `{metric, score, rank}`  (clues, LMS, etc.)
  - `computed` — `{ehp, ehb}`

`GET /players/{username}/gained?period={day|week|month|year}` returns per-metric
`{gained, start, end}` for experience/level/rank/ehp — i.e. **real deltas over a window**.
(Full timelines also available via the snapshots endpoint.)

## Bulk access to many players (for a dataset)
- `GET /groups?limit=&offset=` lists groups with `memberCount` (sample: group 82
  "KnightSlayer", **492 members**). Enumerate groups → `GET /groups/{id}` for member
  lists → fetch each player + gained.
- Also: competitions (participants), and efficiency/records leaderboards.
- So thousands of real accounts + their trajectories are reachable within the rate limit.

## What we can bootstrap (no userbase needed)
1. **Real XP/hr rate model** — aggregate `/gained` across many players, segment by
   `build`/`type` and current level-band, → empirical XP/hr distributions per skill.
   Directly replaces the wiki's stale ballpark rates. **Pure public data.**
2. **"What next" recommender** — from many players' snapshot trajectories, learn common
   progression (which skills/bosses advance next at a given account state) →
   collaborative-filtering / sequence model, personalised later with our telemetry.
3. **Goal ETA** — combine measured rates + the player's gap → "≈N weeks to X at your pace"
   / population pace.
4. **Peer benchmarking** — "accounts at your combat/total have ~X EHB here" straight from
   snapshots. Cheap, high-trust, shippable early.

## Limitations / cautions
- **No quests, diaries, or clue-step detail** in WOM — only skills/bosses/activities. Our
  quest/diary trajectories must come from our OWN telemetry (`account_snapshot`). WOM
  covers the *skilling/bossing* half of the recommender, not the quest half.
- Ranks are global hiscore ranks; EHP/EHB are WOM's own efficiency model — useful as
  features, but they're a model, not ground truth.
- **Confirm WOM's terms** for bulk collection + derived-data use, and prefer
  self-host/dump over hammering the API. Respect 20/min; identify with a real UA + contact.

## Concrete next steps (when we build it)
1. Confirm API-key rate ceiling + skim the TOS/GitHub for a data dump.
2. Seed crawl: pick ~10–20 active groups → collect members → `players/{name}` +
   `gained?period=month`, paced at ≤20/min, store as JSONL (mirror our telemetry schema
   so public + private data merge later).
3. First model: population XP/hr tables per skill × build × level-band; compare against
   `get_training_methods` wiki rates → this alone is a shippable "real rates" feature.
4. Then trajectory sequences for the recommender; join with our own `account_snapshot` +
   `advice` telemetry once it accumulates.

## Endpoints touched during this research
- `GET /players/Lynx%20Titan` → 200 (full snapshot shape)
- `GET /players/Lynx%20Titan/gained?period=month` → 200 (delta shape)
- `GET /groups?limit=3` → 200 (bulk-listing shape, memberCount)
