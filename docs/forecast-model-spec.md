# RuneAssist price-forecast model — build spec (for the DeepSeek harness)

Goal: replace the placeholder `forecastSeries()` (a volatility cone) in
`server/ingest-server.mjs` with a **learned** short-horizon price forecast, served in the
same shape so the plugin graph needs no changes.

## What exists now (the baseline to beat)

- `GET /v1/graph?id=N` on the RuneAssist ingest server (host: Ares, container
  `runeassist-ingest`, `~/selfhost/apps/runeassist-ingest/`). It fetches the OSRS wiki
  timeseries (`https://prices.runescape.wiki/api/v1/osrs/timeseries?timestep=1h|5m&id=N`)
  and returns historical low/high price + volume arrays plus a **forecast**:
  ```
  predictionTimes:        int[]   epoch seconds, next 24 hourly steps
  predictionLowMeans:     long[]  buy-price mean forecast
  predictionLowIQRUpper:  long[]  buy 75th pct
  predictionLowIQRLower:  long[]  buy 25th pct
  predictionHighMeans/IQRUpper/IQRLower: same for sell price
  forecastModel:          string  currently "v1-volcone"
  ```
- Current model = random walk + damped drift for the mean, IQR from a volatility cone
  (`sigma*sqrt(k)`, z=0.6745). Honest but naive.

## The task

Train a model that predicts, per item, the **next 24 hourly** buy (low) and sell (high)
prices as **quantiles** (at least p25/p50/p75; p10/p90 welcome), beating the baseline on
held-out data.

### Data
- Primary: the wiki timeseries (1h series gives ~1 year; 5m gives ~recent). Free, public,
  UA required (`RuneAssist-ingest/1.0`).
- Optional later: the ingest server's accrued `ge_offer` / price JSONL under
  `~/selfhost/apps/runeassist-ingest/data/` (thin for now — don't depend on it yet).
- Build a training set of (features at time t) -> (actual prices at t+1..t+24) across many
  items and history windows. Suggested item universe: all tradeable items with
  `dailyVolume` above a floor (say 1000) so series aren't degenerate.

### Features (suggestions, not prescriptive)
- Recent returns/levels: last price, EWMAs (1h/6h/24h), log-returns and their rolling
  std over several windows.
- Spread (high-low), recent volume (1h/5m), time-of-day / day-of-week (UTC), momentum,
  mean-reversion distance from longer EWMA.

### Model
- Quantile regression is the natural fit: gradient-boosted trees with a pinball/quantile
  loss (LightGBM/XGBoost), one model per quantile, or a single multi-quantile model. A
  small MLP with quantile loss is fine too. Keep it CPU-servable.
- Predict the **path** (24 steps) — either direct multi-horizon models per step, or an
  iterated 1-step model. Report which wins on the metric below.

### Metric / validation
- Time-based split (train on older, validate on most recent weeks) to avoid leakage.
- Score with average **pinball loss** across quantiles and horizons; also report coverage
  (does the p25–p75 band contain the realized price ~50% of the time?). Must beat the
  volatility-cone baseline on pinball loss.

### Serving contract (must match, so the plugin is unchanged)
Produce a function/endpoint that, given an item id, returns the 7 prediction arrays above
(epoch-second times + long price quantiles), plus `forecastModel` set to your model's name.
Two acceptable integrations:
1. A Python service (FastAPI) the Node server calls internally on `/v1/graph` (cache 5 min),
   OR
2. A batch job that writes per-item forecast JSON the Node server reads.
Keep inference fast and cache-friendly; the Node server already caches graph responses 5 min.

### Deliverables
- Training script + saved model artifact, a serving component matching the contract above,
  a short REPORT.md (data window, features, model, pinball-loss vs baseline, coverage), and
  wiring notes for `~/selfhost/apps/runeassist-ingest/`.
- Do NOT change the plugin — only the server forecast source. Keep the historical-series
  reshaping in `ingest-server.mjs` intact.

### Guardrails
- Never claim a number the model didn't produce; report honest validation metrics.
- Prices are near-random-walk — modest, well-calibrated bands beat overconfident point
  forecasts. If the model can't beat the baseline, say so.
