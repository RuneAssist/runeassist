# OSRS MCP — Feature Ideas & Roadmap

Living backlog for the plugin. Add freely; move items into **In progress** when started
and **Done** when shipped. Tiers: **Free** = runs client-side / BYOK (can't be gated
anyway, since the Hub plugin is open-source). **Paid** = needs a backend you run
(hosted LLM, server-side data) so it gates itself behind an account.

_Last updated: 2026-08-31_

---

## Product model (the frame)

- Open-source plugin on the RuneLite Plugin Hub, following the **Flipping Copilot** pattern:
  the plugin is free and open; the paid value lives on a backend behind a login.
- **You cannot paywall a client-side feature** (source is readable/removable). So ship all
  client features free (BYOK), and charge for the **hosted convenience tier** + server-side
  value-adds.
- **Companion, not a bot** — advice only, never automates input. Keeps it Hub- and Jagex-safe.
- Keep out of the open-source repo: secrets, proxy endpoints, and any proprietary
  model/data. Those live server-side only.

---

## In progress — in-panel chat build

Turning the plugin from "data server for an external Claude client" into a self-contained
AI companion with a chat in the RuneLite side panel.

- [x] **Step 1 — `ToolRegistry`**: extract tool catalogue + dispatch from `McpServer` so
      both the MCP server and the chat agent call one shared layer. _(DeepSeek; verified.)_
- [x] **Step 2 — LLM provider layer**: `LlmProvider` + `AnthropicProvider` +
      `OpenAiProvider` (serves OpenAI **and** DeepSeek) + factory + config keys +
      headless smoke test. _(DeepSeek; verified.)_
- [x] **Step 3 — `CompanionAgent`**: the tool-calling loop wiring providers to
      `ToolRegistry`; includes the **HostedProvider seam** (a one-enum switch for the
      future paid tier). _(DeepSeek; verified.)_
- [x] **Step 4 — Swing chat panel** ("RuneAssist"): chat-app UI (avatars, SansSerif,
      indigo accent), in-panel provider/key settings, light markdown, per-reply token
      footer, grounding guardrail. `shutdown()` wired; history lock fixed. Verified live
      end-to-end (DeepSeek). _(Built with Claude.)_

Later hardening: streaming (SSE) responses; per-turn cancel; distinct chat icon;
update default model IDs to current (Claude 5 family).

---

## Backlog

### Companion UX (Free — client-side)
| Idea | Notes | Priority |
|---|---|---|
| **Proactive nudges** | React to `onStatChanged`/quest events: "hit 70 Slayer → unlocks X". What makes it feel like a *companion*, not a chatbox. Hooks already exist. | ★ High |
| **Clue scroll meta-advice** | ~~Step solving~~ is already done well by RuneLite's built-in **Clue Scroll** plugin (map arrows, cryptic/anagram/emote hints, STASH, hot/cold) — don't reimplement. AI only adds a meta layer: "is this tier worth it for my account", clearing stacked clues efficiently, explaining a step in context. Nice extra, not a headline. Needs reward/wiki data. | Low |
| **Slayer task helper** | On new task: gear, location, cannon?, skip/keep? Fires exactly when needed. | Med |
| **"Closest completions"** | Collection log / combat achievements you're 1–2 away from. Turns vague goals into next actions. | Med |
| **Session summary** | "This session: +180k Slayer XP, 40 KC, 2.1M gp." Satisfying, shareable. Uses live + WOM. | Med |
| **Goal tracking in panel** | Set a goal (e.g. quest cape) → live % + next step. | Low |

### Paid / hosted (needs backend)
| Idea | Notes | Priority |
|---|---|---|
| **Hosted LLM tier** | No key needed — sign in, we proxy the model. THE core monetization (Flipping Copilot model). | ★ High |
| **Phone / Discord push** | "GE offer filled" / "farm run ready in 80 min." Strongest paid convenience hook — only a server can do it. | ★ High |
| **Vision / screenshot Q&A** | "What should I do here?" + screenshot to a multimodal model. Opt-in per-message (screenshots leak RSN/chat). Paid (multimodal = pricier). | Med |
| **Server-side long-term memory** | Remembers goals/preferences/past advice across sessions → subscription feels *sticky*. Cheap to build. | Med |
| **Multi-account / clan dashboard** | Alts and clan views. | Low |

### Data moats (need opt-in telemetry + a userbase first)
> All require an **opt-in, off-by-default, anonymised** "contribute rate data" toggle —
> extend the existing privacy section. A data scandal gets a plugin pulled + torched on
> Reddit, so trust is the whole game.

| Idea | Notes | Priority |
|---|---|---|
| **Crowd-sourced real rates** | Measured XP/hr and gp/hr by account segment (from users' WOM gains), vs the wiki's stale ballparks. Network-effect moat; compounds with users. | ★ (after userbase) |
| **Flip prediction model** | Predicted price + fill probability + time. Proven market (Flipping Copilot) but competitive. | Med |
| **Personalized "what next" recommender** | Trained on account trajectories; + goal-ETA prediction ("quest cape in ~6 weeks at your pace"). | Med |
| **Personalized XP-rate model** | Predict *your* rate from your stats/gear vs population. | Low |
| **Tuned + evaluated agent** | Orchestration + few-shot + eval suite kept stable across model updates. Invisible quality moat; lives server-side. | Med |

---

## Data & ML (start capturing NOW — data compounds)

The moats aren't the models, they're the datasets. Every day not logging is training
data lost forever. Two tracks, both startable before any ML exists.

### Track A — capture our own data (opt-in, local JSONL first, upload later)
No backend needed yet: write versioned JSONL to `~/.runelite/runeassist/telemetry/`,
opt-in + anonymised. Datasets in value order:

1. **Advice -> outcome loop** _(only we can build this)_ — per RuneAssist turn: question,
   tools fired, answer, tokens, + account snapshot before/after. Trains a recommender AND
   evaluates the agent. Nobody else has chat + live account + what-happened-next together.
2. **Measured XP/hr by activity** — log `onStatChanged` {skill, xpDelta, ts, location, gear};
   sessionise offline -> real rates per method/segment vs the wiki's stale ballparks.
3. **Real GE/flip performance** — GE offer lifecycle (item, prices, qty, placed/filled time)
   -> realised margins + fill times (the Flipping Copilot moat).
4. **Account trajectories** — periodic stats/quests/diaries snapshots -> "what players at
   this point did next".

> **Design the schema carefully up front** (version every record) — schema drift makes
> early data useless. **Opt-in, off by default, anonymised** (hash RSN, no chat/other-player
> PII). Trust is the whole game; a data scandal gets the plugin pulled.

### Track B — bootstrap ML from PUBLIC data (no userbase needed)
- **Wise Old Man** public player histories/gains (we already call the API) = thousands of
  real account trajectories + gain rates, free.
- **OSRS hiscores** + wiki = cross-sectional priors.
Prototype the "what next" recommender and rate models on public data now; personalise with
private telemetry later. De-risks the chicken-and-egg.

### Next build item
- [ ] **TelemetryService** (opt-in local logger): xp_gain + account_snapshot + advice
      (+ ge_offer) to versioned JSONL, gated by a new `shareTelemetry` toggle. Mechanical —
      good DeepSeek job (prompt drafted). Starts the compounding clock.

---

## Notes / decisions
- 2026-08-31: Decided **all features stay in the plugin, free, BYOK**; paid = hosted
  convenience + server-side data, not feature removal.
- 2026-09-01: Product renamed **RuneAssist** (player-facing); internal group stays `osrsmcp`.
- 2026-09-01: **Grounding guardrail** added after the model invented a "40-50m from 99 WC"
  GP figure. Rule: no money/time figures unless computed from get_item_prices /
  get_training_methods, with arithmetic shown. Models still slip, so watch for
  ungrounded numbers and tighten the prompt as needed.
- DeepSeek is being used for mechanical steps (1–3) to conserve Claude usage; Claude does
  design, verification, and the taste-heavy UI (step 4).
