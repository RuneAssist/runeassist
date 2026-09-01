# Flipping-Copilot parity roadmap

Tracks features Flipping Copilot (FC) has that RuneAssist doesn't yet, plus who's building
each. FC is BSD-2 (github.com/cbrewitt/flipping-copilot) — **port with attribution** in
`THIRD_PARTY_LICENSES.md` where a clean port exists; only write from scratch when our
architecture differs. A local FC clone for reference lives in the scratchpad at
`…/scratchpad/flipping-copilot`.

Status: ☐ todo · ◐ in progress (owner) · ☑ done · ⊘ won't do (FC business model)
Owner: **CW**=Claude worktree agent · **DS**=DeepSeek harness on Ares · **me**=main session

## Already shipped (parity we have)

☑ Single next-flip action card (BUY/SELL/WAIT/MODIFY/DONE), skips non-actionable
☑ On-GE overlay + button highlight (buy button, set price/qty, confirm, modify slot)
☑ In-chatbox number hint + quick-set keybind + search pre-fill (last-searched row)
☑ Real GE tax (exempt list, 250M/5M cap)
☑ Profit tracker (session/all-time, recent flips), buy-limit tracking
☑ Cross-device flip-history sync (our hosted backend, opt-in, hashed rsn)
☑ Price graph + v1 forecast cone; Portfolio popup (market value, unrealized, cash, in-offers, assets)
☑ Sell-side loop incl. underwater "cut loss"; GE-slots budget setting

## High priority

- ◐ **Learned price-forecast model** (DS) — quantile GBM beating the v1 cone, serves the same
  prediction fields. Brief: `~/deepseek-workspace/runeassist-forecast/.task1.txt`. Spec:
  `docs/forecast-model-spec.md`. Verify pinball-loss vs baseline before wiring in.
- ☐ **Sell-from-bank suggestions** (CW) — suggest selling items already in the bank/inventory,
  not just tracker-recorded positions. FC: `AccountStatus.shouldSellFromBank`,
  `HighlightController.drawSellFromBankHighlight`. Needs bank-contents read.
- ☑ **Slot profit colorizer** (CW) — `GeSlotProfitOverlay` draws per-slot running P/L
  green/red on active GE offers.
- ☑ **Flips / Transactions history window** (CW) — `FlipsHistoryWindow`, sortable table of
  all completed flips + totals, opened from the Flips-tab History button.
- ☐ **Profit-over-time graph** (CW) — cumulative profit chart from local flip history. FC:
  Profit graph tab.

## Medium priority

- ☐ **Missed-flips tab** — items set up but not completed / opportunities passed. FC: Missed flips tab.
- ☐ **Dump alerts / buy-dump** — advise dumping into a price spike. FC: `DumpAlert`,
  `isBuyDumpSuggestion`. (Benefits from the forecast model.)
- ☐ **Skip + open-graph keybinds** — we only have quick-set. FC: `KeybindHandler`
  (`skipSuggestionKeybind`, `openGraphKeybind`).
- ☐ **Inventory slot tooltips** — profit info on inventory items. FC: `InventorySlotTooltipOverlay`.
- ☐ **"Set quantity all"** highlight when dumping a full stack. FC:
  `getSetQuantityAllButton`, `highlightQuantity`.
- ☐ **NPC highlight** — guide to the GE clerk / banker. FC: `NpcHighlightOverlay`,
  `highlightNpcAtGrandExchange`.
- ☐ **Visualize-flip** — per-flip price chart marking your buy/sell. FC: Visualize flip tab.
- ☐ **Items tab** — per-item stats/leaderboard from history. FC: Items tab.

## Low priority / research

- ☐ **Fuzzy item search** for the chat/goals. FC: `ui/FuzzySearchScorer`.
- ☐ **Discord webhook** notifications (opt-in). FC: `WebHookController`, `DiscordWebhookBody`.
- ☐ **Session tracking + pause button.** FC: `SessionManager`, `PauseButton`.
- ☐ **Server analytics endpoint** (DS) — aggregate ingest data for a "market movers" feed.

## Won't do (FC's paid/business model, not ours)

- ⊘ Premium instances / paid tier gating (`PremiumInstance*`)
- ⊘ FC login/account system (`CopilotLoginController`, `ApiRequestHandler`) — we use BYOK + our own opt-in sync
- ⊘ Patch-notes popup (`PatchNotesController`)
- ⊘ Multi-account portfolios beyond our hashed cross-device sync
