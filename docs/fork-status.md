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

## Remaining phases

1. **Full sell-side (collected holdings).** Needs a real cost basis for stock already in the
   bank/inventory. FC keeps this in its portfolio/FlipManager transaction history (was
   backend-synced). Options: (a) integrate FC's local portfolio/FlipManager, or (b) add a
   small self-contained FIFO cost tracker in the fork fed by GE offer events. Substantial.
2. **Price graph → our backend.** FC fed its graph from its server (we cut that). Point FC's
   `PriceGraphPanel`/`DataManager` at our `https://runeassist.ares-server.co.uk/v1/graph`
   (which already serves history + a forecast band).
3. **Forecast serving.** The DeepSeek-trained `v2-quantile-lgbm` model is deployed on Ares but
   `/v1/graph` still returns `v1-volcone` — finish the wiring + verify it beats the baseline.
4. **Cleanup.** Delete the now-dead home-grown flipping code in `com.osrsmcp` (action card,
   Ge* overlays, SharedFlipState, the Flips tab, Portfolio/History/Profit windows) — replaced
   by the fork.

## Notes

- The user runs the real Flipping Copilot too; they disable it to use this fork.
- Attribution: FC BSD-2 in `LICENSE.flipping-copilot` + `THIRD_PARTY_LICENSES.md`.
