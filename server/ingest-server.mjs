#!/usr/bin/env node
// RuneAssist ingest server — the thin "data flywheel" backend.
//
// Collects opt-in, anonymised telemetry batches the plugin uploads (hashed RSN, GE
// offers, XP gains, account snapshots) and appends them to per-day JSONL files, in the
// SAME schema the plugin writes locally, so tools/flip-v2-train.mjs can train on either.
//
// Also serves public wiki-derived graphs and ranked flip candidates (no chat, no other
// users' telemetry). Zero npm dependencies — bare node:http — so it runs with just Node 18+.
//
// Run:   INGEST_TOKEN=secret node server/ingest-server.mjs
// Env:   PORT (default 8790), INGEST_TOKEN (required; reject if unset),
//        CONTRIBUTE_TOKEN (default ra-plugin-contribute-v1; plugin ingest key),
//        DATA_DIR (default ./server/data), MAX_BODY_BYTES (default 2_000_000),
//        MAX_BATCH (default 500)
//
// Endpoints:
//   GET  /health         -> {ok:true}
//   POST /v1/ingest      -> Bearer INGEST_TOKEN or CONTRIBUTE_TOKEN; body = JSON array; appends; {stored:N}
//   GET  /v1/stats       -> Bearer auth; per-type record counts for today
//   POST /v1/flips/sync  -> Bearer auth; body {account, flips:[...]}; merges the account's
//                           completed-flip history (union by flip identity) and returns the
//                           merged list. Phase 2: cross-device flip history. Keyed by the
//                           account's HASHED rsn — no real usernames.
//   GET  /v1/graph?id=N  -> public; price-history for one item shaped like Flipping
//                           Copilot's graph Data model (1h/5m/latest low+high price and
//                           volume series, plus buy/sell/dailyVolume). Sourced from the OSRS
//                           wiki timeseries; v2 forecast overlay when FORECAST_URL is up.
//   GET/POST /v1/flips   -> public (wiki + client constraints only; no telemetry, no
//                           forecast ML). Ranked buy candidates. POST JSON body preferred;
//                           GET query works for curl. Alias: /v1/suggest.

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { rankFlips, ensureMarketCache } from './flip-scorer.mjs';
import { initDb, tryHandle as tryAccountHandle } from './account-sync.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const PORT           = Number(process.env.PORT || 8790);
const TOKEN            = process.env.INGEST_TOKEN || '';
// Public plugin contribute key (compiled into the RuneLite plugin). Authorizes
// POST /v1/ingest only — not stats or flip sync. Not the admin INGEST_TOKEN.
const CONTRIBUTE_TOKEN = process.env.CONTRIBUTE_TOKEN || 'ra-plugin-contribute-v1';
const DATA_DIR       = process.env.DATA_DIR || path.join(__dirname, 'data');
const MAX_BODY_BYTES = Number(process.env.MAX_BODY_BYTES || 2_000_000);
const MAX_BATCH      = Number(process.env.MAX_BATCH || 500);
const SCHEMA_VERSION = 1;

// Record types we accept. Mirrors the plugin's UPLOAD_TYPES. ge_history is the
// completed-offer backfill from the in-game GE history UI (not live slot events).
// suggestion_decision is compact skip/abort/acted/ignored panel picks (no bank, no XP).
// Anything else is dropped so a misconfigured or malicious client can't push
// arbitrary blobs into storage.
const ALLOWED_TYPES = new Set(['ge_offer', 'account_snapshot', 'xp_gain', 'ge_history', 'suggestion_decision']);

const FLIP_DIR   = path.join(DATA_DIR, 'flips');
const MAX_FLIPS  = Number(process.env.MAX_FLIPS_PER_ACCT || 5000); // bound per-account history

const WIKI_UA    = 'RuneAssist-ingest/1.0 (github.com/nickbeddows-ctrl/osrs-mcp-plugin)';
const WIKI_TS    = 'https://prices.runescape.wiki/api/v1/osrs/timeseries';
const GRAPH_TTL  = 5 * 60 * 1000; // cache each item's graph for 5 min
const graphCache = new Map();     // itemId -> { at, data }

if (!TOKEN) {
  console.error('Refusing to start: set INGEST_TOKEN (a shared secret the plugin sends as Bearer).');
  process.exit(1);
}
fs.mkdirSync(DATA_DIR, { recursive: true });
fs.mkdirSync(FLIP_DIR, { recursive: true });

const today = () => new Date().toISOString().slice(0, 10); // UTC YYYY-MM-DD

function appendRecords(records) {
  // Group by type so files match the plugin's `${type}-${day}.jsonl` layout.
  const day = today();
  const byType = new Map();
  let stored = 0;
  for (const r of records) {
    if (!r || typeof r !== 'object') continue;
    const type = String(r.type || '');
    if (!ALLOWED_TYPES.has(type)) continue;
    if (r.v !== undefined && Number(r.v) !== SCHEMA_VERSION) continue; // version gate
    r.rx_ts = Date.now(); // server receive time, for lag analysis
    if (!byType.has(type)) byType.set(type, []);
    byType.get(type).push(JSON.stringify(r));
    stored++;
  }
  for (const [type, lines] of byType) {
    const file = path.join(DATA_DIR, `${type}-${day}.jsonl`);
    fs.appendFileSync(file, lines.join('\n') + '\n', 'utf8');
  }
  return stored;
}

function statsToday() {
  const day = today();
  const out = {};
  for (const type of ALLOWED_TYPES) {
    const file = path.join(DATA_DIR, `${type}-${day}.jsonl`);
    try {
      const txt = fs.readFileSync(file, 'utf8');
      out[type] = txt ? txt.split('\n').filter(Boolean).length : 0;
    } catch { out[type] = 0; }
  }
  return { day, counts: out };
}

// Account key = the plugin's hashed rsn (64 hex). Validate hard to prevent path traversal.
function safeAccount(a) {
  return typeof a === 'string' && /^[a-f0-9]{16,64}$/.test(a) ? a : null;
}

// Identity of a completed flip, stable across devices (each carries its original client time).
function flipKey(f) {
  return [f.item_id, f.qty, f.buy_at, f.sell_at, f.time].join('|');
}

function mergeFlips(account, incoming) {
  const file = path.join(FLIP_DIR, `${account}.json`);
  let existing = [];
  try { existing = JSON.parse(fs.readFileSync(file, 'utf8')); if (!Array.isArray(existing)) existing = []; }
  catch { existing = []; }

  const seen = new Map();
  for (const f of existing) seen.set(flipKey(f), f);
  for (const f of incoming) {
    if (!f || typeof f !== 'object') continue;
    if (f.item_id === undefined || f.time === undefined) continue; // malformed
    const k = flipKey(f);
    if (!seen.has(k)) seen.set(k, f);
  }

  let merged = [...seen.values()].sort((a, b) => (a.time || 0) - (b.time || 0));
  if (merged.length > MAX_FLIPS) merged = merged.slice(merged.length - MAX_FLIPS); // keep newest
  fs.writeFileSync(file, JSON.stringify(merged), 'utf8');
  return merged;
}

// Fetch one wiki timeseries (a timestep: "5m" or "1h") for an item.
async function wikiSeries(itemId, timestep) {
  const r = await fetch(`${WIKI_TS}?timestep=${timestep}&id=${itemId}`, {
    headers: { 'User-Agent': WIKI_UA },
  });
  if (!r.ok) throw new Error(`wiki ${timestep} HTTP ${r.status}`);
  const j = await r.json();
  return Array.isArray(j.data) ? j.data : [];
}

// Reshape wiki timeseries into Flipping Copilot's graph Data field layout (JSON, plain
// arrays — no proto, no prediction). Times are epoch seconds. Missing buckets are skipped
// for price series and treated as 0 for volume series.
function reshape(itemId, hourly, fiveMin) {
  const priceSeries = (rows, key) => {
    const t = [], p = [];
    for (const row of rows) {
      if (row[key] == null) continue;
      t.push(row.timestamp); p.push(row[key]);
    }
    return { t, p };
  };
  const volSeries = (rows) => {
    const t = [], lo = [], hi = [];
    for (const row of rows) {
      t.push(row.timestamp);
      lo.push(row.lowPriceVolume || 0);
      hi.push(row.highPriceVolume || 0);
    }
    return { t, lo, hi };
  };

  const l1 = priceSeries(hourly, 'avgLowPrice');
  const h1 = priceSeries(hourly, 'avgHighPrice');
  const l5 = priceSeries(fiveMin, 'avgLowPrice');
  const h5 = priceSeries(fiveMin, 'avgHighPrice');
  const latest = fiveMin.slice(-72); // ~6h of 5m buckets as the "latest" band
  const ll = priceSeries(latest, 'avgLowPrice');
  const hl = priceSeries(latest, 'avgHighPrice');
  const v1 = volSeries(hourly);
  const v5 = volSeries(fiveMin);

  const lastLow  = l5.p.length ? l5.p[l5.p.length - 1] : (l1.p[l1.p.length - 1] || 0);
  const lastHigh = h5.p.length ? h5.p[h5.p.length - 1] : (h1.p[h1.p.length - 1] || 0);
  const dailyVolume = v1.lo.slice(-24).reduce((a, b) => a + b, 0)
                    + v1.hi.slice(-24).reduce((a, b) => a + b, 0);

  // Forward forecast (buy/low and sell/high), sharing one prediction time axis.
  const fLow  = forecastSeries(l1.t, l1.p);
  const fHigh = forecastSeries(h1.t, h1.p);

  const out = {
    itemId,
    dailyVolume,
    buyPrice: lastLow,
    sellPrice: lastHigh,
    low1hTimes: l1.t, low1hPrices: l1.p, high1hTimes: h1.t, high1hPrices: h1.p,
    low5mTimes: l5.t, low5mPrices: l5.p, high5mTimes: h5.t, high5mPrices: h5.p,
    lowLatestTimes: ll.t, lowLatestPrices: ll.p, highLatestTimes: hl.t, highLatestPrices: hl.p,
    volume1hTimes: v1.t, volume1hLows: v1.lo, volume1hHighs: v1.hi,
    volume5mTimes: v5.t, volume5mLows: v5.lo, volume5mHighs: v5.hi,
  };
  if (fLow && fHigh) {
    out.predictionTimes         = fLow.t;
    out.predictionLowMeans      = fLow.m;
    out.predictionLowIQRUpper   = fLow.up;
    out.predictionLowIQRLower   = fLow.lo;
    out.predictionHighMeans     = fHigh.m;
    out.predictionHighIQRUpper  = fHigh.up;
    out.predictionHighIQRLower  = fHigh.lo;
    out.forecastModel = 'v1-volcone';
  }
  return out;
}

// v1 price forecast: a random-walk-with-damped-drift mean and a volatility cone for the
// interquartile band, estimated from recent log-returns. Honest for near-random-walk GE
// prices; widening bands reflect growing uncertainty. Not a learned model — that's the
// job of the model-dev spec (docs/forecast-model-spec.md) handed to the VPS harness.
function forecastSeries(times, prices, horizon = 24, step = 3600) {
  const n = prices ? prices.length : 0;
  if (n < 8) return null;
  const w = Math.min(n, 168); // up to 7 days of hourly points
  const rets = [];
  for (let i = n - w + 1; i < n; i++) {
    const a = prices[i - 1], b = prices[i];
    if (a > 0 && b > 0) rets.push(Math.log(b / a));
  }
  if (rets.length < 4) return null;
  const mean = rets.reduce((s, x) => s + x, 0) / rets.length;
  const varr = rets.reduce((s, x) => s + (x - mean) ** 2, 0) / rets.length;
  let sigma = Math.sqrt(varr);
  if (!(sigma > 0)) sigma = 0.001;
  // Damp drift so a noisy recent trend doesn't run away over the horizon.
  const drift = Math.max(-0.01, Math.min(0.01, mean * 0.5));
  const last = prices[n - 1], lastT = times[n - 1];
  const Q = 0.6745; // z for the interquartile range of a normal
  const t = [], m = [], up = [], lo = [];
  for (let k = 1; k <= horizon; k++) {
    const mk = last * Math.exp(drift * k);
    const sk = sigma * Math.sqrt(k);
    t.push(lastT + k * step);
    m.push(Math.round(mk));
    up.push(Math.round(mk * Math.exp(Q * sk)));
    lo.push(Math.round(mk * Math.exp(-Q * sk)));
  }
  return { t, m, up, lo };
}

async function buildGraph(itemId) {
  const cached = graphCache.get(itemId);
  if (cached && Date.now() - cached.at < GRAPH_TTL) return cached.data;
  const [hourly, fiveMin] = await Promise.all([
    wikiSeries(itemId, '1h'),
    wikiSeries(itemId, '5m'),
  ]);
  const data = reshape(itemId, hourly, fiveMin);
  // If the learned v2 forecast service is up, overlay its prediction; else keep the v1 cone.
  await applyLearnedForecast(itemId, data);
  graphCache.set(itemId, { at: Date.now(), data });
  return data;
}

// Overlay the trained v2-quantile-lgbm forecast onto data.prediction* when FORECAST_URL is
// set and reachable within FORECAST_TIMEOUT; on any failure leave the v1-volcone fallback.
async function applyLearnedForecast(itemId, data) {
  const base = process.env.FORECAST_URL;
  if (!base) return;
  const ms = Number(process.env.FORECAST_TIMEOUT || 800);
  const ac = new AbortController();
  const t = setTimeout(() => ac.abort(), ms);
  try {
    const r = await fetch(`${base}/forecast?id=${itemId}`, { signal: ac.signal });
    if (!r.ok) return;
    const f = await r.json();
    if (!Array.isArray(f.predictionTimes) || f.predictionTimes.length === 0) return;
    for (const k of ['predictionTimes', 'predictionLowMeans', 'predictionLowIQRUpper',
      'predictionLowIQRLower', 'predictionHighMeans', 'predictionHighIQRUpper',
      'predictionHighIQRLower']) {
      if (Array.isArray(f[k])) data[k] = f[k];
    }
    data.forecastModel = f.forecastModel || 'v2-quantile-lgbm';
  } catch { /* fall back to v1 already in data */ }
  finally { clearTimeout(t); }
}

function authed(req) {
  const h = req.headers['authorization'] || '';
  return h.startsWith('Bearer ') && h.slice(7).trim() === TOKEN;
}

function ingestAuthed(req) {
  const h = req.headers['authorization'] || '';
  if (!h.startsWith('Bearer ')) return false;
  const t = h.slice(7).trim();
  return t === TOKEN || (CONTRIBUTE_TOKEN && t === CONTRIBUTE_TOKEN);
}

function send(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

const server = http.createServer((req, res) => {
  tryAccountHandle(req, res).then((handled) => {
    if (handled) return;
    handleLegacy(req, res);
  }).catch((e) => {
    console.error('request failed:', e);
    if (!res.headersSent) send(res, 500, { error: 'internal' });
  });
});

function handleLegacy(req, res) {
  if (req.method === 'GET' && req.url === '/health') return send(res, 200, { ok: true });

  if (req.method === 'GET' && req.url === '/v1/stats') {
    if (!authed(req)) return send(res, 401, { error: 'unauthorized' });
    return send(res, 200, statsToday());
  }

  if (req.method === 'GET' && req.url.startsWith('/v1/graph')) {
    // Public: this only reshapes public OSRS-wiki price data, so no token is required
    // (lets every plugin user see graphs without configuring the contribution endpoint).
    const id = Number(new URL(req.url, 'http://x').searchParams.get('id'));
    if (!Number.isInteger(id) || id <= 0) return send(res, 400, { error: 'bad id' });
    buildGraph(id)
      .then((data) => send(res, 200, data))
      .catch((e) => { console.error('graph failed:', e.message); send(res, 502, { error: 'graph source failed' }); });
    return;
  }

  const flipsPath = req.url.split('?')[0];
  if ((req.method === 'GET' || req.method === 'POST')
      && (flipsPath === '/v1/flips' || flipsPath === '/v1/suggest')) {
    // Public like /v1/graph: ranks public wiki prices under client-supplied constraints.
    // Does not read ingest JSONL or other users' telemetry.
    const reply = (raw) => {
      rankFlips(raw)
        .then((data) => send(res, 200, data))
        .catch((e) => { console.error('flips failed:', e.message); send(res, 502, { error: 'flip source failed' }); });
    };
    if (req.method === 'GET') {
      const q = new URL(req.url, 'http://x').searchParams;
      const raw = {
        capital: q.get('capital'),
        timeframeMinutes: q.get('timeframeMinutes') || q.get('timeframe'),
        risk: q.get('risk') || q.get('riskLevel'),
        membersItemsAllowed: q.get('membersItemsAllowed'),
        f2pOnly: q.get('f2pOnly'),
        remainingSlots: q.get('remainingSlots'),
        minPredictedProfit: q.get('minPredictedProfit'),
        top: q.get('top') || q.get('limit'),
      };
      if (raw.membersItemsAllowed != null) raw.membersItemsAllowed = raw.membersItemsAllowed !== 'false';
      if (raw.f2pOnly != null) raw.f2pOnly = raw.f2pOnly === 'true' || raw.f2pOnly === '1';
      for (const key of ['remainingBuyLimit', 'usedBuyLimit', 'blockedIds', 'skippedIds']) {
        const v = q.get(key);
        if (!v) continue;
        try { raw[key] = JSON.parse(v); } catch { /* ignore bad query json */ }
      }
      reply(raw);
      return;
    }
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY_BYTES) { send(res, 413, { error: 'payload too large' }); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      if (!chunks.length) return reply({});
      let body;
      try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
      catch { return send(res, 400, { error: 'invalid json' }); }
      reply(body && typeof body === 'object' ? body : {});
    });
    return;
  }

  if (req.method === 'POST' && req.url === '/v1/flips/sync') {
    if (!authed(req)) return send(res, 401, { error: 'unauthorized' });
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY_BYTES) { send(res, 413, { error: 'payload too large' }); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      let body;
      try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
      catch { return send(res, 400, { error: 'invalid json' }); }
      const account = safeAccount(body && body.account);
      if (!account) return send(res, 400, { error: 'missing/invalid account' });
      const incoming = Array.isArray(body.flips) ? body.flips : [];
      let merged;
      try { merged = mergeFlips(account, incoming); }
      catch (e) { console.error('flip sync failed:', e.message); return send(res, 500, { error: 'sync failed' }); }
      return send(res, 200, { flips: merged, count: merged.length });
    });
    return;
  }

  if (req.method === 'POST' && req.url === '/v1/ingest') {
    if (!ingestAuthed(req)) return send(res, 401, { error: 'unauthorized' });
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY_BYTES) { send(res, 413, { error: 'payload too large' }); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      let records;
      try { records = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
      catch { return send(res, 400, { error: 'invalid json' }); }
      if (!Array.isArray(records)) return send(res, 400, { error: 'expected a json array' });
      if (records.length > MAX_BATCH) return send(res, 413, { error: `batch over ${MAX_BATCH}` });
      let stored;
      try { stored = appendRecords(records); }
      catch (e) { console.error('append failed:', e.message); return send(res, 500, { error: 'store failed' }); }
      return send(res, 200, { stored });
    });
    return;
  }

  send(res, 404, { error: 'not found' });
}

server.listen(PORT, () => {
  console.log(`RuneAssist ingest server on :${PORT}  data=${DATA_DIR}  (schema v${SCHEMA_VERSION})`);
  initDb().catch((e) => console.error('account-sync db init failed:', e.message));
  ensureMarketCache().catch((e) => console.error('market cache warmup failed:', e.message));
});
