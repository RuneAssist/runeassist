#!/usr/bin/env node
// RuneAssist ingest server — the thin "data flywheel" backend.
//
// Collects opt-in, anonymised telemetry batches the plugin uploads (hashed RSN, GE
// offers, XP gains, account snapshots) and appends them to per-day JSONL files, in the
// SAME schema the plugin writes locally, so tools/flip-v2-train.mjs can train on either.
//
// It only INGESTS. It serves no predictions and stores no chat questions (the plugin never
// uploads them). Zero npm dependencies — bare node:http — so it runs with just Node 18+.
//
// Run:   INGEST_TOKEN=secret node server/ingest-server.mjs
// Env:   PORT (default 8790), INGEST_TOKEN (required; reject if unset),
//        DATA_DIR (default ./server/data), MAX_BODY_BYTES (default 2_000_000),
//        MAX_BATCH (default 500)
//
// Endpoints:
//   GET  /health         -> {ok:true}
//   POST /v1/ingest      -> Bearer auth; body = JSON array of records; appends; {stored:N}
//   GET  /v1/stats       -> Bearer auth; per-type record counts for today

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const PORT           = Number(process.env.PORT || 8790);
const TOKEN          = process.env.INGEST_TOKEN || '';
const DATA_DIR       = process.env.DATA_DIR || path.join(__dirname, 'data');
const MAX_BODY_BYTES = Number(process.env.MAX_BODY_BYTES || 2_000_000);
const MAX_BATCH      = Number(process.env.MAX_BATCH || 500);
const SCHEMA_VERSION = 1;

// Record types we accept. Mirrors the plugin's UPLOAD_TYPES. Anything else is dropped
// so a misconfigured or malicious client can't push arbitrary blobs into storage.
const ALLOWED_TYPES = new Set(['ge_offer', 'account_snapshot', 'xp_gain']);

if (!TOKEN) {
  console.error('Refusing to start: set INGEST_TOKEN (a shared secret the plugin sends as Bearer).');
  process.exit(1);
}
fs.mkdirSync(DATA_DIR, { recursive: true });

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

function authed(req) {
  const h = req.headers['authorization'] || '';
  return h.startsWith('Bearer ') && h.slice(7).trim() === TOKEN;
}

function send(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') return send(res, 200, { ok: true });

  if (req.method === 'GET' && req.url === '/v1/stats') {
    if (!authed(req)) return send(res, 401, { error: 'unauthorized' });
    return send(res, 200, statsToday());
  }

  if (req.method === 'POST' && req.url === '/v1/ingest') {
    if (!authed(req)) return send(res, 401, { error: 'unauthorized' });
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
});

server.listen(PORT, () => {
  console.log(`RuneAssist ingest server on :${PORT}  data=${DATA_DIR}  (schema v${SCHEMA_VERSION})`);
});
