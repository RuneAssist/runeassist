# Proactive nudges — design spec

The judgment layer (what to say, when, how not to annoy). Once this is settled, the
*plumbing* is a clean DeepSeek job. The whole risk here is spam — a companion that
interrupts is worse than no companion. Default to quiet.

## Principle
A nudge is a short, high-value, **passive** observation surfaced when something
noteworthy happens. It NEVER interrupts play (no game overlay, no popup, no sound). It
appears in the RuneAssist panel as a dim "system" line + a small unread dot on the nav
icon. The player notices on their own terms.

## Two tiers
- **Tier 1 — rule-based, local, zero-token** (default ON). Generated from events +
  planner/live data. Safe, cheap, the backbone. Examples below.
- **Tier 2 — LLM-authored, opt-in, rare** (default OFF). Occasionally RuneAssist composes
  a richer nudge via the agent (costs tokens). Behind a setting; hard-capped per day.

## Triggers (Tier 1) — only HIGH-signal events
| Event | Fire when… | Nudge copy (example) |
|---|---|---|
| **Level up** | the new level newly makes a quest/diary task/training method eligible, OR hits 99 | "92 Slayer — you can now use a cannon on X / start quest Y." |
| **Quest complete** | always | "Nice — {quest} done. Next on the optimal route you can start: {next}." |
| **New Slayer task** | always (if Slayer plugin active) | "New task: {n} {monster}. Want gear + location?" |
| **Daily available** | at login, from DailyTracker | "Battlestaves + herb boxes waiting." |
| **GE offer filled** | offer state -> BOUGHT/SOLD | "Your {item} sold: {qty} for {gp} (after 1% tax)." |

Explicitly do NOT fire on: every level (only unlock/milestone levels), routine XP,
inventory changes, or anything mid-combat.

## Suppression rules (the important half)
- **Rate limit:** at most **1 nudge / 10 min**, hard cap **~6/day**. Excess is dropped,
  not queued (stale nudges are noise).
- **Milestone-only level-ups:** debounce — a training session ticks many levels; only the
  ones that unlock something or hit 99 qualify.
- **Dedup:** never repeat the same nudge key in a session.
- **Idle-ish only:** don't fire while actively in combat / a boss fight (check
  interacting/region if cheap; otherwise just the rate limit).
- **Off switch:** config `proactiveNudges` (default ON, Tier 1 only). Tier 2 has its own
  `nudgeLlm` toggle (default OFF).
- **Login grace:** suppress non-daily nudges for ~30s after login (avoid a burst).

## Surfacing
- A **dim system bubble** in the panel (distinct from You/RuneAssist — e.g. grey, no
  avatar, italic), tappable to expand ("Tell me more" -> sends it as a question).
- A small **unread dot** on the RuneAssist nav icon, cleared when the panel is opened.
- Never steals focus.

## Build split
- **Claude (done here):** trigger list, copy, thresholds, tiering.
- **DeepSeek (future prompt):** a `NudgeService` that subscribes to the events, applies
  rate-limit + dedup + login-grace, and posts a nudge string to the panel; the panel's
  system-bubble + nav-dot rendering; the `proactiveNudges` config toggle. Pure plumbing
  against this spec.
- **Claude (later):** Tier 2 LLM-authored nudges + the unlock-detection glue (reusing the
  planner's project_plan-style "newly eligible" check on level-up).

## Open question for Tom
Default Tier-1 nudges ON or OFF out of the box? Recommendation: **ON but conservative**
(the rate limits make it gentle, and it's the feature that makes it feel like a
companion). Easy to flip.
