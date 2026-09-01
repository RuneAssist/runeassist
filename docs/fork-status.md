# RuneAssist Flipping fork — status & remaining phases

Branch: `feature/fc-flipping-fork`. The flipping is now a **self-contained fork of Flipping
Copilot** (BSD-2), separate from the AI/MCP plugin and from the user's installed FC.

## Done

- ☑ FC codebase imported (175 files), builds in-repo, no new deps.
- ☑ Package renamed `com.flippingcopilot` → `com.runeassist.flip` (no collision with installed FC).
- ☑ Decoupled from FC's backend/login (`SuggestionController` no longer calls FC's `/suggestion`).
- ☑ Decoupled from the core BankTags plugin (was blocking sideloaded construction).
- ☑ Self-contained suggestion source: `FlipScorer` (own OSRS-wiki fetch + scoring) →
  `LocalSuggestionEngine` → FC `Suggestion`. Injects only core RuneLite; no cross-plugin DI.
- ☑ Own identity: RuneAssist icon, tooltip, config group `runeassistflip`, data dir
  `~/.runelite/runeassist-flip`. **Loads and appears in the plugin list.**
- ☑ Suggests BUYs, and SELLs of completed-but-uncollected buys (cost basis = the buy price).
- ☑ **Full sell-side (collected holdings).** `HeldCostTracker` (new, self-contained) watches
  the fork's GE offer events, keeps a FIFO cost basis per item persisted in RuneLite config
  (`runeassistflip`/`heldcost`), and feeds real held qty+avgBuy into the suggestion engine via
  `FlipScorer.sellQuote()`. Sells (incl. cut-loss) now rank ahead of buys.
- ☑ **Price graph → our backend.** `ApiRequestHandler.asyncGetRuneAssistGraph()` fetches
  `https://runeassist.ares-server.co.uk/v1/graph?id=N` (JSON matching FC's `Data` layout,
  parsed straight in via Gson) and `PriceGraphPanel` now loads from it instead of FC's server.
- ☑ **Forecast v2 live.** `ingest-server.mjs` calls the trained `v2-quantile-lgbm` service
  (800ms timeout) and overlays its prediction onto the v1-volcone baseline already computed;
  any failure keeps v1 silently. Verified live end-to-end via the public endpoint for two
  items, and verified the v1 fallback still returns valid data with the forecast service
  stopped. v2 beats v1 on the held-out set: pinball 302.3 vs 421.8 (~28% better), coverage
  0.493 vs 0.837 (v2 much closer to the ideal 0.5 for a 25–75% band). Deploy fixes along the
  way: the `host.docker.internal` gateway mapping was wrong (resolved to the default docker0
  bridge, not this container's `proxy` network gateway) and UFW had no rule for port 8791 —
  both fixed. Service now runs under `systemctl --user` (survives reboot via linger), not nohup.

## Remaining phases

1. **Cleanup.** Delete the now-dead home-grown flipping code in `com.osrsmcp` (action card,
   Ge* overlays, SharedFlipState, the Flips tab, Portfolio/History/Profit windows) — replaced
   by the fork.

## Notes

- The user runs the real Flipping Copilot too; they disable it to use this fork.
- Attribution: FC BSD-2 in `LICENSE.flipping-copilot` + `THIRD_PARTY_LICENSES.md`.
