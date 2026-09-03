#!/usr/bin/env node
// fill-time-model.mjs — offline fill-probability + time-to-fill trainer/eval.
//
// Reconstructs real GE offer episodes from ge_offer state transitions and scores
// fill-within-5m/30m plus time-to-complete. This is NOT wired into the live picker
// (FlipScorer / LocalSuggestionEngine / RuneAssistSuggestionSource / suggestion UI).
// It does NOT use v2-quantile-lgbm or the price-forecast service as a fill model.
// Synthetic Monte Carlo GE sims are not the eval — only offline replay of real offers.
//
// Labels (from ge_offer only; no invented fills, ge_history is not used as a label):
//   time-to-first-progress  first quantity_sold increase after a 0-sold placement
//   time-to-complete        first BOUGHT/SOLD (or qty_sold >= total)
//   filled-within-T         complete fill by T minutes (5 and 30). Cancelled / still
//                           open past T = 0. Still open with observed life < T = censored.
//
// Features: offer fields (qty, price, side, hour-of-day). Wiki 5m/1h/latest are joined
// at offer start ts when a snapshot exists within the staleness window. If that join is
// sparse, the fitted model uses offer fields only and the volume heuristic is scored on
// the joined subset.
//
// Usage:
//   node tools/fill-time-model.mjs
//   node tools/fill-time-model.mjs --dir=%USERPROFILE%\.runelite\runeassist\telemetry
//   node tools/fill-time-model.mjs --wiki-dir=tools/.cache/wiki-archive
//   node tools/fill-time-model.mjs --ares-data=tools/.cache/ares-data
//   node tools/fill-time-model.mjs --split=0.7 --windows=5,30
//   node tools/fill-time-model.mjs path/to/ge_offer-*.jsonl
//
// Optional Ares copies (read-only; does not touch ingest):
//   scp Ares-Server:~/selfhost/apps/runeassist-ingest/wiki-archive/*.jsonl tools/.cache/wiki-archive/
//   ssh Ares-Server "docker exec runeassist-ingest cat /data/ge_offer-YYYY-MM-DD.jsonl" > tools/.cache/ares-data/ge_offer-YYYY-MM-DD.jsonl

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const args = Object.fromEntries(process.argv.slice(2).filter(a => a.startsWith('--')).map(a => {
  const m = a.match(/^--([^=]+)(?:=(.*))?$/);
  return m ? [m[1], m[2] ?? true] : [a, true];
}));
const extraFiles = process.argv.slice(2).filter(a => !a.startsWith('--'));

const SPLIT = Math.min(0.9, Math.max(0.5, Number(args.split ?? 0.7)));
const WINDOWS = String(args.windows ?? '5,30').split(',').map(Number).filter(n => n > 0);
const WIKI_5M_MAX_MS = 20 * 60 * 1000;
const WIKI_1H_MAX_MS = 90 * 60 * 1000;
const LATEST_MAX_MS = 20 * 60 * 1000;

const ACTIVE = new Set(['BUYING', 'SELLING']);
const COMPLETE = new Set(['BOUGHT', 'SOLD']);
const CANCEL = new Set(['CANCELLED_BUY', 'CANCELLED_SELL', 'CANCELLED']);

function expandHome(p) {
  if (p == null) return p;
  const s = String(p);
  if (s.startsWith('~/') || s === '~') return path.join(os.homedir(), s.slice(2));
  return s;
}

function defaultTelemetryDir() {
  return args.dir
    ? expandHome(args.dir)
    : path.join(os.homedir(), '.runelite', 'runeassist', 'telemetry');
}

function defaultWikiDir() {
  if (args['wiki-dir']) return expandHome(args['wiki-dir']);
  const cached = path.join(__dirname, '.cache', 'wiki-archive');
  if (fs.existsSync(cached)) return cached;
  return null;
}

function defaultAresDir() {
  if (args['ares-data']) return expandHome(args['ares-data']);
  const cached = path.join(__dirname, '.cache', 'ares-data');
  if (fs.existsSync(cached)) return cached;
  return null;
}

function listGeOfferFiles(dir) {
  if (!dir || !fs.existsSync(dir)) return [];
  return fs.readdirSync(dir)
    .filter(f => f.startsWith('ge_offer-') && f.endsWith('.jsonl'))
    .map(f => path.join(dir, f));
}

function recKey(r) {
  return [r.ts, r.acct, r.slot, r.state, r.item_id, r.price, r.total_quantity, r.quantity_sold].join('|');
}

function loadGeOffers() {
  const files = [
    ...listGeOfferFiles(defaultTelemetryDir()),
    ...listGeOfferFiles(defaultAresDir()),
    ...extraFiles.map(expandHome),
  ];
  const seen = new Set();
  const recs = [];
  const sources = [];
  for (const f of files) {
    if (!f || !fs.existsSync(f)) continue;
    let n = 0;
    for (const line of fs.readFileSync(f, 'utf8').split('\n')) {
      if (!line.trim()) continue;
      let r;
      try { r = JSON.parse(line); } catch { continue; }
      if (r.type && r.type !== 'ge_offer') continue;
      const k = recKey(r);
      if (seen.has(k)) continue;
      seen.add(k);
      recs.push(r);
      n++;
    }
    sources.push({ file: f, added: n });
  }
  recs.sort((a, b) => (a.ts - b.ts) || (a.slot - b.slot) || String(a.acct).localeCompare(String(b.acct)));
  return { recs, sources };
}

function sideOf(state) {
  if (state === 'BUYING' || state === 'BOUGHT' || state === 'CANCELLED_BUY') return 'buy';
  if (state === 'SELLING' || state === 'SOLD' || state === 'CANCELLED_SELL') return 'sell';
  return null;
}

function identityOf(r) {
  return `${r.item_id}|${r.price}|${r.total_quantity}|${sideOf(r.state)}`;
}

// Replay per-(acct,slot) transitions. A new episode starts when a slot shows a fresh
// BUYING/SELLING with item+qty. Login dumps of already-complete/cancel slots are dropped
// (first observation is not a placement). EMPTY / identity change closes the prior episode.
function reconstructEpisodes(recs) {
  const open = new Map();
  const episodes = [];

  function close(key, endTs, fallback) {
    const ep = open.get(key);
    if (!ep) return;
    if (!ep.terminal) ep.terminal = fallback;
    ep.endTs = endTs;
    episodes.push(ep);
    open.delete(key);
  }

  for (const r of recs) {
    const k = `${r.acct}|${r.slot}`;
    const ep = open.get(k);
    const side = sideOf(r.state);

    if (r.state === 'EMPTY' || !(r.item_id > 0) || !(r.total_quantity > 0)) {
      if (ep) close(k, r.ts, ep.terminal || 'empty');
      continue;
    }

    if (ep && identityOf(r) !== `${ep.itemId}|${ep.price}|${ep.qty}|${ep.side}`) {
      close(k, r.ts, ep.terminal || 'replaced');
    }

    let cur = open.get(k);
    if (!cur) {
      // Skip mid-stream first sightings of a finished slot (login / collect dump).
      if (!ACTIVE.has(r.state) || r.quantity_sold > 0) continue;
      cur = {
        acct: r.acct,
        slot: r.slot,
        itemId: r.item_id,
        price: r.price,
        qty: r.total_quantity,
        side,
        startTs: r.ts,
        startQtySold: r.quantity_sold,
        firstProgressTs: null,
        lastQty: r.quantity_sold,
        lastState: r.state,
        nTicks: 0,
        terminal: null,
        completeTs: null,
        cancelTs: null,
        endTs: r.ts,
      };
      open.set(k, cur);
    }

    cur.nTicks++;
    cur.lastQty = r.quantity_sold;
    cur.lastState = r.state;
    cur.endTs = r.ts;
    if (cur.firstProgressTs == null && r.quantity_sold > cur.startQtySold) cur.firstProgressTs = r.ts;
    if (COMPLETE.has(r.state) || r.quantity_sold >= r.total_quantity) {
      if (!cur.completeTs) cur.completeTs = r.ts;
      cur.terminal = 'complete';
    } else if (CANCEL.has(r.state)) {
      if (!cur.cancelTs) cur.cancelTs = r.ts;
      if (cur.terminal !== 'complete') cur.terminal = 'cancelled';
    }
  }

  const streamEnd = recs.length ? recs[recs.length - 1].ts : Date.now();
  for (const k of [...open.keys()]) close(k, streamEnd, open.get(k).terminal || 'open');
  return episodes;
}

function filledWithin(ep, minutes) {
  const horizon = ep.startTs + minutes * 60_000;
  if (ep.completeTs != null && ep.completeTs <= horizon) return 1;
  if (ep.completeTs != null && ep.completeTs > horizon) return 0;
  if (ep.terminal === 'open' && ep.endTs < horizon) return null;
  if (ep.endTs >= horizon) return 0;
  if (ep.terminal === 'cancelled' || ep.terminal === 'replaced' || ep.terminal === 'empty') return 0;
  return null;
}

function minutesBetween(a, b) {
  return Math.max(0, (b - a) / 60_000);
}

function loadWikiArchive(dir) {
  const out = { snaps5m: [], snaps1h: [], snapsLatest: [], files: 0 };
  if (!dir || !fs.existsSync(dir)) return out;
  for (const f of fs.readdirSync(dir)) {
    if (!f.startsWith('wiki-') || !f.endsWith('.jsonl')) continue;
    const kind = f.startsWith('wiki-5m-') ? '5m'
      : f.startsWith('wiki-1h-') ? '1h'
        : f.startsWith('wiki-latest-') ? 'latest'
          : null;
    if (!kind) continue;
    out.files++;
    for (const line of fs.readFileSync(path.join(dir, f), 'utf8').split('\n')) {
      if (!line.trim()) continue;
      let r;
      try { r = JSON.parse(line); } catch { continue; }
      const t = r.wiki_ts != null ? Number(r.wiki_ts) * 1000 : Number(r.ts);
      if (!Number.isFinite(t) || !r.data || typeof r.data !== 'object') continue;
      const snap = { t, data: r.data };
      if (kind === '5m') out.snaps5m.push(snap);
      else if (kind === '1h') out.snaps1h.push(snap);
      else out.snapsLatest.push(snap);
    }
  }
  out.snaps5m.sort((a, b) => a.t - b.t);
  out.snaps1h.sort((a, b) => a.t - b.t);
  out.snapsLatest.sort((a, b) => a.t - b.t);
  return out;
}

function lastAtOrBefore(snaps, t, maxAge) {
  if (!snaps.length) return null;
  let lo = 0, hi = snaps.length - 1, best = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (snaps[mid].t <= t) { best = mid; lo = mid + 1; }
    else hi = mid - 1;
  }
  if (best < 0) return null;
  const s = snaps[best];
  if (t - s.t > maxAge) return null;
  return s;
}

function itemRow(data, id) {
  if (!data) return null;
  return data[id] || data[String(id)] || null;
}

function joinWiki(ep, wiki) {
  const s5 = lastAtOrBefore(wiki.snaps5m, ep.startTs, WIKI_5M_MAX_MS);
  const s1 = lastAtOrBefore(wiki.snaps1h, ep.startTs, WIKI_1H_MAX_MS);
  const sl = lastAtOrBefore(wiki.snapsLatest, ep.startTs, LATEST_MAX_MS);
  const r5 = itemRow(s5 && s5.data, ep.itemId);
  const r1 = itemRow(s1 && s1.data, ep.itemId);
  const rl = itemRow(sl && sl.data, ep.itemId);
  const high5 = r5 && r5.avgHighPrice != null ? Number(r5.avgHighPrice) : null;
  const low5 = r5 && r5.avgLowPrice != null ? Number(r5.avgLowPrice) : null;
  const hv5 = r5 ? Number(r5.highPriceVolume) || 0 : null;
  const lv5 = r5 ? Number(r5.lowPriceVolume) || 0 : null;
  const hv1 = r1 ? Number(r1.highPriceVolume) || 0 : null;
  const lv1 = r1 ? Number(r1.lowPriceVolume) || 0 : null;
  const highL = rl && rl.high != null ? Number(rl.high) : high5;
  const lowL = rl && rl.low != null ? Number(rl.low) : low5;
  const sideVol5 = r5 == null ? null : (ep.side === 'buy' ? lv5 : hv5);
  const sideVol1 = r1 == null ? null : (ep.side === 'buy' ? lv1 : hv1);
  const mid = (highL > 0 && lowL > 0) ? (highL + lowL) / 2 : null;
  const spread = (highL > 0 && lowL > 0) ? (highL - lowL) / mid : null;
  const tot5 = r5 == null ? null : (hv5 + lv5);
  const imb = tot5 > 0 ? Math.abs(hv5 - lv5) / tot5 : null;
  const vsMid = mid > 0 ? (ep.price - mid) / mid : null;
  return {
    joined: !!(r5 || r1 || rl),
    has5m: !!r5,
    has1h: !!r1,
    hasLatest: !!rl,
    vol5: sideVol5,
    vol1h: sideVol1,
    vol5Tot: tot5,
    spread,
    vsMid,
    imbalance: imb,
    high: highL,
    low: lowL,
  };
}

function hodFeatures(ts) {
  const hour = new Date(ts).getUTCHours() + new Date(ts).getUTCMinutes() / 60;
  const ang = (2 * Math.PI * hour) / 24;
  return { sin: Math.sin(ang), cos: Math.cos(ang), hour };
}

function offerFeatures(ep, wikiJoin, includeWiki) {
  const hod = hodFeatures(ep.startTs);
  const feats = {
    logQty: Math.log1p(ep.qty),
    logPrice: Math.log1p(ep.price),
    buy: ep.side === 'buy' ? 1 : 0,
    sinHod: hod.sin,
    cosHod: hod.cos,
  };
  if (includeWiki) {
    feats.hasWiki = wikiJoin.joined ? 1 : 0;
    feats.logVol5 = Math.log1p(wikiJoin.vol5 ?? 0);
    feats.logVol1h = Math.log1p(wikiJoin.vol1h ?? 0);
    feats.logQtyOverVol5 = wikiJoin.vol5 != null
      ? Math.log1p(ep.qty) - Math.log1p(wikiJoin.vol5)
      : 0;
    feats.spread = wikiJoin.spread ?? 0;
    feats.vsMid = wikiJoin.vsMid ?? 0;
    feats.imbalance = wikiJoin.imbalance ?? 0;
  }
  return feats;
}

function keysOf(obj) { return Object.keys(obj); }

function colStats(rows, keys) {
  const stats = {};
  for (const k of keys) {
    const xs = rows.map(r => r[k]).filter(Number.isFinite);
    const mean = xs.length ? xs.reduce((s, x) => s + x, 0) / xs.length : 0;
    const var_ = xs.length ? xs.reduce((s, x) => s + (x - mean) ** 2, 0) / xs.length : 0;
    stats[k] = { mean, std: Math.sqrt(var_) || 1 };
  }
  return stats;
}

function toX(feats, keys, stats) {
  return keys.map(k => {
    const s = stats[k];
    const v = feats[k];
    return Number.isFinite(v) ? (v - s.mean) / s.std : 0;
  });
}

function sigmoid(z) {
  if (z >= 30) return 1;
  if (z <= -30) return 0;
  return 1 / (1 + Math.exp(-z));
}

function dot(w, x) {
  let s = w[0];
  for (let i = 0; i < x.length; i++) s += w[i + 1] * x[i];
  return s;
}

function fitLogistic(X, y, { lambda = 1, steps = 400, lr = 0.15 } = {}) {
  const p = X[0].length;
  const w = new Array(p + 1).fill(0);
  const n = X.length;
  if (!n) return w;
  for (let step = 0; step < steps; step++) {
    const g = new Array(p + 1).fill(0);
    for (let i = 0; i < n; i++) {
      const err = sigmoid(dot(w, X[i])) - y[i];
      g[0] += err;
      for (let j = 0; j < p; j++) g[j + 1] += err * X[i][j];
    }
    w[0] -= lr * (g[0] / n);
    for (let j = 0; j < p; j++) w[j + 1] -= lr * (g[j + 1] / n + lambda * w[j + 1]);
  }
  return w;
}

function predictLogistic(w, x) { return sigmoid(dot(w, x)); }

function solveLinear(A, b) {
  const n = A.length;
  const M = A.map((row, i) => [...row, b[i]]);
  for (let i = 0; i < n; i++) {
    let piv = i;
    for (let r = i + 1; r < n; r++) if (Math.abs(M[r][i]) > Math.abs(M[piv][i])) piv = r;
    [M[i], M[piv]] = [M[piv], M[i]];
    const d = M[i][i];
    if (Math.abs(d) < 1e-12) continue;
    for (let c = i; c <= n; c++) M[i][c] /= d;
    for (let r = 0; r < n; r++) {
      if (r === i) continue;
      const f = M[r][i];
      for (let c = i; c <= n; c++) M[r][c] -= f * M[i][c];
    }
  }
  return M.map(row => row[n]);
}

function fitRidge(X, y, lambda = 1) {
  const n = X.length;
  const p = X[0].length + 1;
  const XtX = Array.from({ length: p }, () => new Array(p).fill(0));
  const Xty = new Array(p).fill(0);
  for (let i = 0; i < n; i++) {
    const xi = [1, ...X[i]];
    for (let a = 0; a < p; a++) {
      Xty[a] += xi[a] * y[i];
      for (let b = 0; b < p; b++) XtX[a][b] += xi[a] * xi[b];
    }
  }
  for (let i = 1; i < p; i++) XtX[i][i] += lambda;
  return solveLinear(XtX, Xty);
}

function predictRidge(w, x) { return dot(w, x); }

function median(xs) {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
}

function mean(xs) { return xs.length ? xs.reduce((s, x) => s + x, 0) / xs.length : null; }

function mae(pairs) {
  if (!pairs.length) return null;
  return mean(pairs.map(([y, yhat]) => Math.abs(y - yhat)));
}

function medianAe(pairs) {
  if (!pairs.length) return null;
  return median(pairs.map(([y, yhat]) => Math.abs(y - yhat)));
}

function brier(pairs) {
  if (!pairs.length) return null;
  return mean(pairs.map(([y, p]) => (p - y) ** 2));
}

function auc(pairs) {
  const pos = pairs.filter(([y]) => y === 1).map(([, p]) => p);
  const neg = pairs.filter(([y]) => y === 0).map(([, p]) => p);
  if (!pos.length || !neg.length) return null;
  let wins = 0, ties = 0;
  for (const p of pos) {
    for (const n of neg) {
      if (p > n) wins++;
      else if (p === n) ties++;
    }
  }
  return (wins + 0.5 * ties) / (pos.length * neg.length);
}

function fmt(n, d = 3) {
  if (n == null || Number.isNaN(n)) return '  n/a ';
  if (!Number.isFinite(n)) return '  inf ';
  const s = n.toFixed(d);
  return s.padStart(6);
}

function pct(n) { return n == null ? '  n/a ' : (100 * n).toFixed(1).padStart(5) + '%'; }

function volumeHeuristic(ep, wikiJoin, minutes) {
  const vol5 = wikiJoin.vol5;
  const vol1h = wikiJoin.vol1h;
  let estMin = Infinity;
  if (vol5 != null && vol5 > 0) estMin = (ep.qty / vol5) * 5;
  else if (vol1h != null && vol1h > 0) estMin = (ep.qty / vol1h) * 60;
  else return { defined: false, estMin: null, pHard: null, pExp: null };
  const pHard = estMin <= minutes ? 1 : 0;
  const pExp = 1 - Math.exp(-minutes / Math.max(estMin, 1e-6));
  return { defined: true, estMin, pHard, pExp };
}

function timeSplit(episodes, frac) {
  const sorted = [...episodes].sort((a, b) => a.startTs - b.startTs);
  const cut = Math.max(1, Math.min(sorted.length - 1, Math.floor(sorted.length * frac)));
  // Keep the cut on a timestamp so equal-start rows stay together.
  const cutTs = sorted[cut].startTs;
  const train = sorted.filter(e => e.startTs < cutTs);
  const test = sorted.filter(e => e.startTs >= cutTs);
  if (!train.length || !test.length) {
    return { train: sorted.slice(0, cut), test: sorted.slice(cut), cutTs: sorted[cut].startTs };
  }
  return { train, test, cutTs };
}

function priorLookup(train, window) {
  const map = new Map();
  for (const e of train) {
    const y = filledWithin(e, window);
    if (y == null) continue;
    const k = `${e.itemId}|${e.side}`;
    const a = map.get(k) ?? { n: 0, filled: 0, times: [] };
    a.n++;
    a.filled += y;
    if (e.completeTs) a.times.push(minutesBetween(e.startTs, e.completeTs));
    map.set(k, a);
  }
  const globalY = [];
  const globalT = [];
  for (const a of map.values()) {
    for (let i = 0; i < a.n; i++) globalY.push(i < a.filled ? 1 : 0);
    globalT.push(...a.times);
  }
  return {
    map,
    globalRate: globalY.length ? mean(globalY) : 0.5,
    globalMedian: median(globalT) ?? 15,
    predict(ep) {
      const a = map.get(`${ep.itemId}|${ep.side}`);
      if (!a || a.n < 1) return { p: this.globalRate, t: this.globalMedian };
      return { p: a.filled / a.n, t: median(a.times) ?? this.globalMedian };
    },
  };
}

function pad(s, n) { return String(s).padEnd(n); }
function rpad(s, n) { return String(s).padStart(n); }

function main() {
  const { recs, sources } = loadGeOffers();
  const wiki = loadWikiArchive(defaultWikiDir());

  console.log('# RuneAssist fill-time model — offline replay of real ge_offer transitions');
  console.log('# Not a picker input. Not v2-quantile-lgbm. No synthetic GE fills.\n');
  console.log('sources:');
  for (const s of sources) console.log(`  ${s.added}  ${s.file}`);
  if (!sources.length) {
    console.log('No ge_offer JSONL found. Enable shareTelemetry and pass --dir or files.');
    process.exit(0);
  }

  const episodes = reconstructEpisodes(recs);
  const joins = episodes.map(e => joinWiki(e, wiki));
  const joinedN = joins.filter(j => j.joined).length;
  const join5 = joins.filter(j => j.has5m).length;
  const join1h = joins.filter(j => j.has1h).length;
  const joinLatest = joins.filter(j => j.hasLatest).length;
  const joinRate = episodes.length ? joinedN / episodes.length : 0;

  const term = {};
  for (const e of episodes) term[e.terminal] = (term[e.terminal] || 0) + 1;
  const completed = episodes.filter(e => e.terminal === 'complete' && e.completeTs);
  const ttc = completed.map(e => minutesBetween(e.startTs, e.completeTs));
  const ttp = episodes.filter(e => e.firstProgressTs && e.startQtySold === 0)
    .map(e => minutesBetween(e.startTs, e.firstProgressTs));

  console.log(`\nge_offer records     ${recs.length}`);
  console.log(`offer episodes       ${episodes.length}   (unique items ${new Set(episodes.map(e => e.itemId)).size})`);
  console.log(`terminal             ${Object.entries(term).map(([k, v]) => `${k}=${v}`).join('  ')}`);
  console.log(`completed fills      ${completed.length}   ttc median ${fmt(median(ttc), 2)} min   MAE-ref mean ${fmt(mean(ttc), 2)}`);
  console.log(`first progress       ${ttp.length}   median ${fmt(median(ttp), 2)} min`);
  console.log(`wiki snapshots       5m=${wiki.snaps5m.length}  1h=${wiki.snaps1h.length}  latest=${wiki.snapsLatest.length}`);
  console.log(`wiki join at start   ${joinedN}/${episodes.length} (${pct(joinRate).trim()})  5m=${join5}  1h=${join1h}  latest=${joinLatest}`);

  if (episodes.length < 5) {
    console.log('\nToo few reconstructed episodes to train. Scaffold is ready; accrue more ge_offer days.');
    process.exit(0);
  }

  const { train, test, cutTs } = timeSplit(episodes, SPLIT);
  const trainJoinRate = train.filter(e => joinWiki(e, wiki).joined).length / (train.length || 1);
  const useWikiInFit = trainJoinRate >= 0.3 && joinRate >= 0.4;
  console.log(`wiki in fitted model ${useWikiInFit ? 'yes (offer+wiki)' : 'no — train-period join too sparse; offer fields only'}`);
  console.log('                      volume heuristic is scored on the wiki-joined test subset only');
  console.log(`\ntime split           train ${train.length} / test ${test.length}  cut ${new Date(cutTs).toISOString()}  (frac=${SPLIT}, by start ts)`);
  console.log(`decision-grade?      no — one-day N=${episodes.length} from a single telemetry day; numbers are a pipeline check, not a ship gate\n`);

  for (const window of WINDOWS) {
    evalWindow({ window, train, test, wiki, useWikiInFit });
  }

  evalFillTime({ train, test, wiki, useWikiInFit });

  console.log('\n(eval only — do not copy scores into the plugin jar picker)');
}

function labeled(rows, window, wiki) {
  const out = [];
  for (const e of rows) {
    const y = filledWithin(e, window);
    if (y == null) continue;
    out.push({ e, y, w: joinWiki(e, wiki) });
  }
  return out;
}

function evalWindow({ window, train, test, wiki, useWikiInFit }) {
  const tr = labeled(train, window, wiki);
  const te = labeled(test, window, wiki);
  const rate = mean(tr.map(r => r.y)) ?? 0.5;
  const lookup = priorLookup(train, window);

  const featKeys = tr.length
    ? keysOf(offerFeatures(tr[0].e, tr[0].w, useWikiInFit))
    : [];
  const stats = colStats(tr.map(r => offerFeatures(r.e, r.w, useWikiInFit)), featKeys);
  const Xtr = tr.map(r => toX(offerFeatures(r.e, r.w, useWikiInFit), featKeys, stats));
  const w = tr.length ? fitLogistic(Xtr, tr.map(r => r.y)) : [0];

  const models = [];

  function scoreModel(name, predFn, subset = te) {
    const pairs = [];
    for (const r of subset) {
      const p = predFn(r);
      if (p == null || !Number.isFinite(p)) continue;
      pairs.push([r.y, Math.min(1, Math.max(0, p))]);
    }
    models.push({
      name,
      n: pairs.length,
      pos: pairs.filter(([y]) => y === 1).length,
      auc: auc(pairs),
      brier: brier(pairs),
    });
  }

  scoreModel('always-fill (p=1)', () => 1);
  scoreModel(`constant prior (p=${rate.toFixed(2)})`, () => rate);
  scoreModel('empirical item|side (flip-v2-train)', r => lookup.predict(r.e).p);
  scoreModel(`logistic ${useWikiInFit ? 'offer+wiki' : 'offer-only'}`, r =>
    predictLogistic(w, toX(offerFeatures(r.e, r.w, useWikiInFit), featKeys, stats)));

  const teWiki = te.filter(r => r.w.joined && (r.w.vol5 != null || r.w.vol1h != null));
  if (teWiki.length) {
    scoreModel('vol heuristic hard (5m qty/vol)', r => volumeHeuristic(r.e, r.w, window).pHard, teWiki);
    scoreModel('vol heuristic exp (5m qty/vol)', r => volumeHeuristic(r.e, r.w, window).pExp, teWiki);
  }

  console.log(`## filled-within-${window}m   train n=${tr.length} pos=${tr.filter(r => r.y === 1).length}   test n=${te.length} pos=${te.filter(r => r.y === 1).length}`);
  console.log(pad('model', 42) + rpad('n', 5) + rpad('pos', 5) + rpad('AUC', 8) + rpad('Brier', 8));
  for (const m of models) {
    console.log(pad(m.name, 42) + rpad(m.n, 5) + rpad(m.pos, 5) + rpad(fmt(m.auc), 8) + rpad(fmt(m.brier), 8));
  }
  console.log('');
}

function evalFillTime({ train, test, wiki, useWikiInFit }) {
  const tr = train.filter(e => e.completeTs);
  const te = test.filter(e => e.completeTs);
  const med = median(tr.map(e => minutesBetween(e.startTs, e.completeTs))) ?? 15;
  const lookup = priorLookup(train, 30);

  const featKeys = tr.length
    ? keysOf(offerFeatures(tr[0], joinWiki(tr[0], wiki), useWikiInFit))
    : [];
  const stats = colStats(tr.map(e => offerFeatures(e, joinWiki(e, wiki), useWikiInFit)), featKeys);
  const yLog = tr.map(e => Math.log(minutesBetween(e.startTs, e.completeTs) + 0.05));
  const Xtr = tr.map(e => toX(offerFeatures(e, joinWiki(e, wiki), useWikiInFit), featKeys, stats));
  const w = tr.length ? fitRidge(Xtr, yLog, 1.5) : [Math.log(med + 0.05)];

  const rows = [];
  function add(name, predFn, subset = te) {
    const pairs = [];
    for (const e of subset) {
      const yhat = predFn(e);
      if (yhat == null || !Number.isFinite(yhat)) continue;
      pairs.push([minutesBetween(e.startTs, e.completeTs), Math.max(0, yhat)]);
    }
    rows.push({ name, n: pairs.length, mae: mae(pairs), medae: medianAe(pairs) });
  }

  add('constant train-median ttc', () => med);
  add('empirical item|side median (flip-v2)', e => lookup.predict(e).t);
  add(`ridge-log ${useWikiInFit ? 'offer+wiki' : 'offer-only'}`, e => {
    const z = predictRidge(w, toX(offerFeatures(e, joinWiki(e, wiki), useWikiInFit), featKeys, stats));
    return Math.exp(z) - 0.05;
  });

  const teWiki = te.filter(e => {
    const wj = joinWiki(e, wiki);
    return wj.joined && (wj.vol5 != null || wj.vol1h != null);
  });
  if (teWiki.length) {
    add('vol heuristic (5m qty/vol * 5min)', e => volumeHeuristic(e, joinWiki(e, wiki), 30).estMin, teWiki);
  }

  console.log(`## time-to-complete (minutes, completed offers only)   train n=${tr.length}  test n=${te.length}  train median=${fmt(med, 2)}`);
  console.log(pad('model', 42) + rpad('n', 5) + rpad('MAE', 8) + rpad('MedAE', 8));
  for (const m of rows) {
    console.log(pad(m.name, 42) + rpad(m.n, 5) + rpad(fmt(m.mae, 2), 8) + rpad(fmt(m.medae, 2), 8));
  }

  const ttpTr = train.filter(e => e.firstProgressTs).map(e => minutesBetween(e.startTs, e.firstProgressTs));
  const ttpTe = test.filter(e => e.firstProgressTs).map(e => minutesBetween(e.startTs, e.firstProgressTs));
  const ttpMed = median(ttpTr);
  if (ttpTe.length && ttpMed != null) {
    const pairs = ttpTe.map(y => [y, ttpMed]);
    console.log(`\n## time-to-first-progress   train n=${ttpTr.length} median=${fmt(ttpMed, 2)}  test n=${ttpTe.length}  constant-median MAE=${fmt(mae(pairs), 2)}  MedAE=${fmt(medianAe(pairs), 2)}`);
  }
}

main();
