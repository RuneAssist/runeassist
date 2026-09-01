# DeepSeek prompt — identify the GE offer-setup item/price/qty API

Research task (no build). Goal: find the exact RuneLite API to drive a display-only overlay
on the Grand Exchange **offer-setup** screen (the buy/sell screen where you pick item, price,
quantity — BEFORE confirming). Claude will verify the answer live and build the overlay.

Paste into DeepSeek:

---

```
You are researching the RuneLite client's open-source code (github.com/runelite/runelite)
to find the exact API a plugin uses to read the Grand Exchange OFFER-SETUP screen — the
screen where the player is choosing an item, price and quantity before confirming a buy or
sell. I need this to draw a DISPLAY-ONLY overlay (I will NOT automate any input).

Answer these precisely, citing the RuneLite source file(s) and the exact identifiers:

1. DETECT the offer-setup screen is open (and whether it's a BUY or a SELL). What widget
   group / interface id, or which @Subscribe event (e.g. WidgetLoaded groupId, VarbitChanged,
   or a Varbit/VarClientInt), signals that the GE buy-setup or sell-setup panel is showing?
   Give the exact constant name(s) and their class (WidgetInfo / InterfaceID / ComponentID /
   Varbits / VarClientInt / VarPlayer) and, if known, numeric value(s).

2. ITEM being configured. How does a plugin read the item id the player is currently setting
   up an offer for on that screen? Is it a Varbit/VarPlayer/VarClientInt (give the exact
   constant + value), or is it read from a child Widget of the offer container (give the exact
   WidgetInfo / group+child ids and the accessor, e.g. Widget.getItemId())?

3. PRICE and QUANTITY currently entered in the setup. How are the price-per-item and quantity
   values read (Varbit / VarClientInt / widget text)? Give exact constants/ids.

4. Show how RuneLite's OWN GrandExchangePlugin (net.runelite.client.plugins.grandexchange)
   reads these — quote the relevant lines/method names — since that's the authoritative
   reference implementation.

Constraints:
- Prefer identifiers that exist across recent RuneLite versions; if a constant was renamed
  (e.g. VarClientInt vs the newer gameval packages, or WidgetInfo vs ComponentID/InterfaceID),
  note BOTH the old and new names so Claude can match whichever this build has.
- This project's build uses classes like net.runelite.api.widgets.WidgetInfo and
  net.runelite.api.VarPlayerID / net.runelite.api.gameval.* — flag which package the GE
  constants live in for a current RuneLite.

Output: a short table of {purpose -> exact class.CONSTANT (value) / method} plus the
GrandExchangePlugin code excerpt you based it on, with file paths and any version caveats.
```

---

## VERIFIED ANSWER (checked against runelite-api-1.12.37.jar with javap — 2026-09-01)

All constants below resolve in this build with the cited values:

| Purpose | Identifier (value) |
|---|---|
| GE offer-setup build script | `net.runelite.api.ScriptID.GE_OFFERS_SETUP_BUILD` (779) |
| Offer container widget | `net.runelite.api.widgets.WidgetInfo.GRAND_EXCHANGE_OFFER_CONTAINER` |
| Buy vs sell | `net.runelite.api.gameval.VarbitID.GE_NEWOFFER_TYPE` (4397) — 0 = BUY |
| Item being set up | `net.runelite.api.gameval.VarPlayerID.TRADINGPOST_SEARCH` (1151), via `client.getVarpValue(...)` |
| Price per item | `VarbitID.GE_NEWOFFER_PRICE` (4398) (+ `GE_PRICE_CUSTOM` 4284 flag) |
| Quantity | `VarbitID.GE_NEWOFFER_QUANTITY` (4396) |

Reference impl: `plugins/itemstats/ItemStatPlugin` (not GrandExchangePlugin, which reads
*placed* offers via `client.getGrandExchangeOffers()`).

Build: `GeOfferSetupOverlay` — when the setup screen is open, annotate the item being
configured with suggested buy/sell + post-tax margin + 1h volume + verdict from the flip
model (`PlayerDataService.flipQuoteForItem`). Display only; price fetch happens off the
render thread (cached per item id).
