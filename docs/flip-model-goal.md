# Goal — Flip prediction model

## The goal
Give the player, for any GE item, a trustworthy flip call:
**recommended buy & sell price, margin after the 1% GE tax, probability the order
fills, expected time-to-fill, and a sensible quantity** (respecting the buy limit).
This is Flipping Copilot's proven-to-sell product — the strongest paid/moat feature
in IDEAS.md. Ship a useful **rules-based v1 with zero ML**, then upgrade the
fill-probability / time-to-fill parts with a model trained on our own data.

## Data we already have
1. **Public price+volume** (`prices.runescape.wiki`, already used by `WikiPriceService`):
   - `/latest` → high/low + timestamps (current spread).
   - `/5m`, `/1h` → `avgHighPrice/avgLowPrice`, `highPriceVolume/lowPriceVolume`
     (liquidity + short-term trend). **This is the whole public bootstrap.**
2. **Our own realised fills** (telemetry `ge_offer` records: item, state BOUGHT/SOLD,
   price, qty, spent, ts). The private moat signal — real fill prices and, by pairing
   place→complete timestamps, **real fill times per item**. Accrues once `shareTelemetry`
   is on; nobody else has this joined to live account context.

## Targets to predict
- **Margin** = sell − buy − 1% tax (tax capped per item). Simple arithmetic.
- **Fill probability** at a candidate price within a time window.
- **Time-to-fill** (minutes) for buy and for sell.
- **Suggested quantity** = min(buy limit, capital / buy, liquidity-safe size).

## Approach (two stages)
### v1 — rules/statistical, no ML, no private data (shippable now)
From `/5m` + `/1h` + `/latest`, per item:
- margin = low→high spread after tax; **margin% ** for ranking.
- liquidity = hourly volume; **est. fill time ≈ buy_limit / hourly_volume** (clamp).
- risk flags: thin volume, wide/volatile spread (high vs 1h range), stale last-trade.
- **Score = margin × liquidity**, penalised by risk. Rank items; size to buy limit + capital.
Ship as an upgraded `get_flip_suggestions` / a "Flips" view. Honest, useful, and it's
what most flip tools actually do.

### v2 — learned fill model (the moat), on telemetry
Once `ge_offer` data accrues, train:
- **Fill probability & time-to-fill** from features {spread, price vs recent range,
  hourly volume, volume imbalance high/low, hour-of-day, item}.
- Start simple (logistic / gradient-boosted trees); target = did it fill / how long.
- Personalise later; benchmark against the v1 heuristic to prove the model earns its keep.

## Bootstrap from public data (no userbase needed, like WOM rates)
The v1 heuristic needs only the public price API — buildable and shippable immediately.
Historical `/5m`/`/1h` can be sampled over time (a small collector like `wom-collect.mjs`)
to characterise per-item liquidity/volatility and to pre-seed the v2 features before our
own fills exist.

## First steps
1. Extend `WikiPriceService` use: pull `/1h` volume alongside `/latest` for a candidate set.
2. Write the v1 scorer (margin×liquidity − risk; est. fill time from volume) and surface
   it via `get_flip_suggestions`. Ship it.
3. Add a small **price/volume history collector** (mirror `wom-collect.mjs`) writing 5m/1h
   snapshots to JSONL → the v2 feature store.
4. When `ge_offer` telemetry has enough rows, train the fill-probability / time-to-fill
   model; A/B it against the v1 heuristic.

## Cautions
- Always account for the **1% GE tax** (and its per-item cap) and **buy limits**.
- Ironmen can't flip — gate the feature off for them.
- Never present a predicted number you didn't compute (same grounding rule as the chat).
- Thin/volatile items are where naive tools lose money — the risk penalty is the point.
