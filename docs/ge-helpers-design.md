# GE helpers + richer flipping — design

Making the flip tool feel like Flipping Copilot, plus in-GE helpers. Needs the RuneLite
client (overlay + widget reading), so it's built/tested in-game — specced here.

## The hard boundary (non-negotiable)
**Display and advise only. NEVER automate GE input.** We may SHOW the suggested price /
quantity and make it easy to read; the player types it. Auto-clicking price/quantity
buttons or filling inputs = botting → instant Hub rejection + bannable. Flipping Copilot
works this way too. Everything below is display-only.

## In-GE overlay helpers (display-only)
1. **Offer-setup overlay** — when the buy/sell offer screen is open for an item, draw a
   small panel: suggested **buy** and **sell** price, post-tax **margin** + **margin %**,
   **1h volume**, GE **limit**, and a verdict (good / thin / one-sided / wide-spread) — all
   from the flip model (`buildFlipSuggestions` logic, per-item). The player reads and types
   the price themselves.
2. **Open-offer annotations** — on each active GE slot, overlay current margin/est. profit;
   when an offer completes, show **realised profit** (sell − buy − 2% tax).
3. **Copy-price affordance** — a click to copy the suggested price to clipboard (player
   pastes). Display+clipboard, not automation — still the player's action to enter it.
4. **Search annotate (stretch)** — when the GE search list is open, mark items that are
   currently strong flips. Harder (list widget scraping); do last.

### Implementation notes
- Extend `Overlay` (or a `WidgetItemOverlay`) rendered only when a GE widget group is open.
- Get the currently-selected GE item from the offer-setup widget / varbits; look up
  price+volume via `WikiPriceService` and score with the shared flip logic (extract the
  per-item scoring from `buildFlipSuggestions` into a reusable method so overlay + tool
  share it).
- Live offer events: `GrandExchangeOfferChanged` (already subscribed for telemetry) drives
  the open-offer annotations + realised-profit.
- Config toggles: `geOverlay` (default ON), `geOverlayCopyPrice`.

## Richer flipping (tool + panel; some buildable without the client)
- **Active flips / session P&L** — reconstruct open+completed flips from
  `GrandExchangeOfferChanged` (live) and the `ge_offer` telemetry (history); show session
  profit, per-item P&L. A `get_active_flips` tool + a panel/overlay view. (The v2 trainer
  already reconstructs completed flips — reuse that logic.)
- **Recommended prices that refresh** — the model already emits buy/sell; surface them live.
- **Blocked items / favourites** — config lists to exclude junk or pin staples; the scorer
  filters/boosts accordingly.
- **Stale-offer reminders** — nudge (via NudgeService) when an offer has sat unfilled beyond
  its estimated fill time.

## Build order
1. Extract shared per-item flip scoring from `buildFlipSuggestions` into a method the
   overlay can call. (Client-free; buildable now.)
2. `get_active_flips` tool from live `GrandExchangeOfferChanged` + `ge_offer` telemetry.
3. GE offer-setup overlay (display-only) — client, in-game testing.
4. Open-offer annotations + realised profit; then copy-price, blocked/favourites, reminders.

## Cautions (carry over)
- GE tax 2% (cap 5M); buy limits; ironman = bonds only, UIM = no GE.
- Never show a number not computed from data (grounding rule).
- Fill time is a volume estimate — label it as such.
