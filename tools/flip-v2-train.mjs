#!/usr/bin/env node
// flip-v2-train.mjs — v2 flip model trainer (fill-probability + time-to-fill).
//
// Reads the ge_offer telemetry JSONL written by TelemetryService (docs/flip-model-goal.md
// stage v2) and reconstructs COMPLETED flips from the offer-state stream, then fits an
// empirical model: per (item, side) fill rate and median/p90 time-to-fill. This is the v2
// "learned" model v0 — an empirical lookup grounded in the player's real fills — and the
// feature table an ML model (gradient-boosted trees) would train on once volume warrants.
//
// Data-gated: it does nothing useful until shareTelemetry is on and GE trades have
// accrued. Until then it reports "no data yet" — that's expected.
//
// Usage:
//   node tools/flip-v2-train.mjs                       # default telemetry dir
//   node tools/flip-v2-train.mjs path/to/ge_offer-*.jsonl
//   node tools/flip-v2-train.mjs --dir=~/.runelite/runeassist/telemetry

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const args = Object.fromEntries(process.argv.slice(2).filter(a => a.startsWith('--')).map(a => {
  const m = a.match(/^--([^=]+)(?:=(.*))?$/); return m ? [m[1], m[2] ?? true] : [a, true];
}));
const files = process.argv.slice(2).filter(a => !a.startsWith('--'));

function resolveFiles() {
  if (files.length) return files;
  const dir = (args.dir ? String(args.dir).replace(/^~/, os.homedir())
    : path.join(os.homedir(), '.runelite', 'runeassist', 'telemetry'));
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir).filter(f => f.startsWith('ge_offer-') && f.endsWith('.jsonl'))
           .map(f => path.join(dir, f));
}

// Load and sort all ge_offer records by timestamp.
function loadRecords() {
  const recs = [];
  for (const f of resolveFiles()) {
    for (const line of fs.readFileSync(f, 'utf8').split('\n')) {
      if (!line.trim()) continue;
      try { const r = JSON.parse(line); if (r.type === 'ge_offer') recs.push(r); } catch {}
    }
  }
  recs.sort((a, b) => a.ts - b.ts);
  return recs;
}

const BUYING = new Set(['BUYING']);
const SELLING = new Set(['SELLING']);
const FILLED = new Set(['BOUGHT', 'SOLD']);
const CANCELLED = new Set(['CANCELLED_BUY', 'CANCELLED_SELL', 'CANCELLED']);

// Reconstruct completed flips: a slot goes BUYING/SELLING (start) -> BOUGHT/SOLD (filled)
// or CANCELLED (not filled). Keyed per (acct, slot).
function reconstructFlips(recs) {
  const open = new Map(); // "acct|slot" -> {startTs, side, itemId, price}
  const flips = [];
  for (const r of recs) {
    const key = `${r.acct}|${r.slot}`;
    const side = BUYING.has(r.state) ? 'buy' : SELLING.has(r.state) ? 'sell' : null;
    if (side) { open.set(key, { startTs: r.ts, side, itemId: r.item_id, price: r.price }); continue; }
    if (FILLED.has(r.state) || CANCELLED.has(r.state)) {
      const o = open.get(key);
      if (!o) continue;
      open.delete(key);
      flips.push({
        itemId: o.itemId, side: o.side, price: o.price,
        minutes: Math.max(0, (r.ts - o.startTs) / 60000),
        filled: FILLED.has(r.state),
      });
    }
  }
  return flips;
}

function pct(sorted, p) { return sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))] : 0; }

function main() {
  const recs = loadRecords();
  if (recs.length === 0) {
    console.log('No ge_offer telemetry found yet.');
    console.log('Enable "Contribute anonymous data" (shareTelemetry) in RuneAssist and trade on the');
    console.log('GE; this trainer activates once ge_offer-*.jsonl accrues. (Data-gated by design.)');
    return;
  }
  const flips = reconstructFlips(recs);
  console.log(`# v2 flip model — ${recs.length} ge_offer records -> ${flips.length} completed flips\n`);
  if (flips.length === 0) { console.log('(records present but no completed BUY/SELL cycles yet)'); return; }

  // Empirical model: per (item, side) fill rate + time-to-fill distribution.
  const agg = new Map(); // "item|side" -> {n, filled, times[]}
  for (const f of flips) {
    const k = `${f.itemId}|${f.side}`;
    const a = agg.get(k) ?? agg.set(k, { n: 0, filled: 0, times: [] }).get(k);
    a.n++; if (f.filled) { a.filled++; a.times.push(f.minutes); }
  }
  console.log('  item     side  n    fill%   p50 min   p90 min');
  for (const [k, a] of [...agg].sort((x, y) => y[1].n - x[1].n)) {
    const [item, side] = k.split('|');
    a.times.sort((p, q) => p - q);
    console.log('  ' + item.padEnd(8) + ' ' + side.padEnd(4) + ' ' + String(a.n).padStart(3) +
      '  ' + (100 * a.filled / a.n).toFixed(0).padStart(4) + '%' +
      '  ' + pct(a.times, 0.5).toFixed(1).padStart(7) +
      '  ' + pct(a.times, 0.9).toFixed(1).padStart(7));
  }
  console.log('\n# This empirical table IS the v2 model v0. Upgrade path: once rows are plentiful,');
  console.log('# train fill-probability + time-to-fill on features {spread, price-vs-range, 1h');
  console.log('# volume, imbalance, hour-of-day, item} and A/B against the v1 heuristic.');
}

main();
