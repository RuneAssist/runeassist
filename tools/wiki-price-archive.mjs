#!/usr/bin/env node
// wiki-price-archive.mjs — polite OSRS Wiki price+volume archive (forecast/volume fuel).
//
// This is NOT a fill model and NOT ge_offer telemetry. It snapshots the wiki's bulk
// endpoints on a 5-minute cadence so forecast training has a growing 5m/1h history
// without walking `/5m?timestamp=` in a tight loop (Wiki AUP: a few bulk calls per
// interval, not 100k per-item or per-timestamp walks).
//
// Usage:
//   node tools/wiki-price-archive.mjs --dir=wiki-archive
//   node tools/wiki-price-archive.mjs --dir=/path --no-volumes
//   node tools/wiki-price-archive.mjs --backfill --step=5m --from=UNIX --to=UNIX
//
// Regular tick (default): GET /5m + /latest. If this UTC hour has no 1h snapshot yet,
// also GET /1h and (unless --no-volumes) /volumes. Two to four bulk calls, then exit.
// Intended to be driven by a systemd timer every 5 minutes — do not cron a timestamp walk.
//
// Optional one-shot backfill (default OFF): walks `/5m?timestamp=` or `/1h?timestamp=`
// at --delay-ms (minimum 2000). Documented for historical gaps only.
//
// Env: WIKI_ARCHIVE_DIR (overridden by --dir)

import fs from 'node:fs';
import path from 'node:path';

const BASE = 'https://prices.runescape.wiki/api/v1/osrs';
const UA = 'RuneAssist-wiki-archive/1.0 (github.com/nickbeddows-ctrl/osrs-mcp-plugin; contact tom@tpharrison.co.uk)';

const args = Object.fromEntries(process.argv.slice(2).map(a => {
  const m = a.match(/^--([^=]+)(?:=(.*))?$/);
  return m ? [m[1], m[2] ?? true] : [a, true];
}));

const DIR = args.dir || process.env.WIKI_ARCHIVE_DIR || 'wiki-archive';
const WANT_VOLUMES = !args['no-volumes'];
const BACKFILL = !!args.backfill;
const MIN_DELAY_MS = 2000;
const MAX_LINE_BYTES = 40 * 1024 * 1024; // bound a single JSONL line if a payload explodes

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function get(pathname, query = {}) {
  const url = new URL(`${BASE}/${pathname}`);
  for (const [k, v] of Object.entries(query)) {
    if (v != null && v !== '') url.searchParams.set(k, String(v));
  }
  let lastErr;
  for (let attempt = 0; attempt < 4; attempt++) {
    try {
      const res = await fetch(url, {
        headers: { 'User-Agent': UA },
        signal: AbortSignal.timeout(30_000),
      });
      if (res.status === 429) {
        const reset = parseInt(res.headers.get('retry-after') ?? '10', 10);
        const wait = Math.max(2, Number.isFinite(reset) ? reset : 10);
        console.warn(`  429 on ${pathname}; backing off ${wait}s`);
        await sleep(wait * 1000);
        continue;
      }
      if (!res.ok) throw new Error(`HTTP ${res.status} for ${url.pathname}${url.search}`);
      return res.json();
    } catch (e) {
      lastErr = e;
      if (attempt < 3) await sleep(500 * (attempt + 1));
    }
  }
  throw lastErr || new Error(`giving up: ${pathname}`);
}

function utcDay(ms = Date.now()) {
  return new Date(ms).toISOString().slice(0, 10);
}

function itemCount(data) {
  return data && typeof data === 'object' && !Array.isArray(data) ? Object.keys(data).length : 0;
}

function appendLine(dir, endpoint, record) {
  // Prefer the wiki interval timestamp so a backfill lands on the historical day.
  const dayMs = record.wiki_ts ? record.wiki_ts * 1000 : (record.ts || Date.now());
  const day = utcDay(dayMs);
  const file = path.join(dir, `wiki-${endpoint}-${day}.jsonl`);
  const line = JSON.stringify(record);
  const bytes = Buffer.byteLength(line);
  if (bytes > MAX_LINE_BYTES) {
    const slim = JSON.stringify({
      v: 1,
      ts: record.ts,
      endpoint,
      wiki_ts: record.wiki_ts ?? null,
      n: record.n ?? 0,
      skipped: 'payload_too_large',
      bytes,
    });
    fs.appendFileSync(file, slim + '\n');
    console.warn(`  ${endpoint}: payload ${bytes}B > ${MAX_LINE_BYTES}B; wrote summary only`);
    return { file, bytes: Buffer.byteLength(slim), n: record.n ?? 0, slim: true };
  }
  fs.appendFileSync(file, line + '\n');
  return { file, bytes, n: record.n ?? 0, slim: false };
}

function snapshotRecord(endpoint, body) {
  const data = body && typeof body === 'object' ? (body.data ?? body) : {};
  const wikiTs = body && typeof body === 'object' && body.timestamp != null
    ? Number(body.timestamp)
    : null;
  return {
    v: 1,
    ts: Date.now(),
    endpoint,
    wiki_ts: Number.isFinite(wikiTs) ? wikiTs : null,
    n: itemCount(data),
    data,
  };
}

function hourStampPath(dir) {
  return path.join(dir, '.last-1h');
}

function hourDue(dir) {
  const nowHour = Math.floor(Date.now() / 3_600_000);
  try {
    const last = parseInt(fs.readFileSync(hourStampPath(dir), 'utf8'), 10);
    if (last === nowHour) return false;
  } catch { /* first run */ }
  return true;
}

function markHour(dir) {
  fs.writeFileSync(hourStampPath(dir), String(Math.floor(Date.now() / 3_600_000)));
}

async function pullEndpoint(dir, endpoint, query) {
  const body = await get(endpoint, query);
  const rec = snapshotRecord(endpoint, body);
  const out = appendLine(dir, endpoint, rec);
  const where = path.basename(out.file);
  console.log(`  ${endpoint}: n=${out.n} ${out.bytes}B -> ${where}${out.slim ? ' (summary)' : ''}`);
  return rec;
}

async function regularTick(dir) {
  console.log(`wiki-archive tick dir=${dir} volumes=${WANT_VOLUMES ? 'yes' : 'no'}`);
  await pullEndpoint(dir, '5m');
  await pullEndpoint(dir, 'latest');
  if (hourDue(dir)) {
    await pullEndpoint(dir, '1h');
    if (WANT_VOLUMES) {
      try {
        await pullEndpoint(dir, 'volumes');
      } catch (e) {
        console.warn(`  volumes: ${e.message} (optional; continuing)`);
      }
    }
    markHour(dir);
  } else {
    console.log('  1h: skipped (already pulled this UTC hour)');
  }
}

function parseStep(step) {
  const s = String(step || '5m').toLowerCase();
  if (s === '5m' || s === '5min') return { endpoint: '5m', seconds: 300 };
  if (s === '1h' || s === 'hour') return { endpoint: '1h', seconds: 3600 };
  throw new Error(`unknown --step=${step} (use 5m or 1h)`);
}

async function backfill(dir) {
  const delay = Math.max(MIN_DELAY_MS, parseInt(args['delay-ms'] ?? String(MIN_DELAY_MS), 10) || MIN_DELAY_MS);
  if (delay < MIN_DELAY_MS) throw new Error(`backfill delay must be >= ${MIN_DELAY_MS}ms`);
  const { endpoint, seconds } = parseStep(args.step);
  const from = parseInt(args.from, 10);
  const to = parseInt(args.to ?? String(Math.floor(Date.now() / 1000)), 10);
  if (!Number.isFinite(from) || from <= 0) {
    throw new Error('backfill requires --from=UNIX (seconds). Default is off; this is a one-shot historical walk.');
  }
  if (!Number.isFinite(to) || to <= from) throw new Error('--to must be a unix timestamp > --from');

  const start = from - (from % seconds);
  const end = to - (to % seconds);
  const steps = Math.floor((end - start) / seconds) + 1;
  console.log(`wiki-archive BACKFILL ${endpoint} from=${start} to=${end} steps=${steps} delay=${delay}ms`);
  console.log('(Wiki AUP: do not run this in a tight loop. ≥2s/request, one-shot only.)');

  let ok = 0;
  for (let ts = start; ts <= end; ts += seconds) {
    try {
      await pullEndpoint(dir, endpoint, { timestamp: ts });
      ok++;
    } catch (e) {
      console.warn(`  ${endpoint}?timestamp=${ts}: ${e.message}`);
    }
    await sleep(delay);
  }
  console.log(`backfill done: ${ok}/${steps} windows -> ${dir}`);
}

async function main() {
  const dir = path.resolve(DIR);
  fs.mkdirSync(dir, { recursive: true });
  if (BACKFILL) await backfill(dir);
  else await regularTick(dir);
}

main().catch(e => { console.error(e); process.exit(1); });
