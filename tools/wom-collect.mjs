#!/usr/bin/env node
// wom-collect.mjs — polite collector for the RuneAssist "real rates" dataset.
//
// Pulls public account data + weekly gains from the Wise Old Man API (see
// docs/wom-ml-research.md) and writes one JSONL record per player. Feeds the
// population XP/hr rate model (Track B in IDEAS.md) — the public-data bootstrap that
// replaces the wiki's stale ballpark rates. No game client needed.
//
// Usage:
//   node tools/wom-collect.mjs --groups=5 --max-members=50 --out=research/wom.jsonl --rpm=18
//   node tools/wom-collect.mjs --group=82                     # one specific group id
//
// Respects WOM's rate limit (20/60s unauth; 100/60s with an API key via their Discord).
// Set WOM_API_KEY to use a key (header) and a higher default rpm. Be polite: identify
// with a real User-Agent and do NOT hammer the API — prefer a slow, capped crawl.

import fs from 'node:fs';
import path from 'node:path';

const BASE = 'https://api.wiseoldman.net/v2';
const UA = 'runeassist-research/1.0 (github: RuneAssist; contact tom@tpharrison.co.uk)';
const API_KEY = process.env.WOM_API_KEY || '';

// ── args ────────────────────────────────────────────────────────────────────
const args = Object.fromEntries(process.argv.slice(2).map(a => {
  const m = a.match(/^--([^=]+)(?:=(.*))?$/);
  return m ? [m[1], m[2] ?? true] : [a, true];
}));
const N_GROUPS    = args.group ? 0 : parseInt(args.groups ?? '3', 10);
const ONE_GROUP   = args.group ? parseInt(args.group, 10) : null;
const MAX_MEMBERS = parseInt(args['max-members'] ?? '40', 10);
const RPM         = parseInt(args.rpm ?? (API_KEY ? '90' : '18'), 10); // stay under the ceiling
const OUT         = args.out ?? 'research/wom.jsonl';

// ── polite throttle: space requests to stay under RPM ────────────────────────
const MIN_GAP_MS = Math.ceil(60000 / Math.max(1, RPM));
let lastReq = 0;
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function throttle() {
  const wait = lastReq + MIN_GAP_MS - Date.now();
  if (wait > 0) await sleep(wait);
  lastReq = Date.now();
}

async function get(url) {
  await throttle();
  const headers = { 'User-Agent': UA };
  if (API_KEY) headers['x-api-key'] = API_KEY;
  for (let attempt = 0; attempt < 3; attempt++) {
    const res = await fetch(url, { headers });
    if (res.status === 429) { // rate limited — back off on the reset header
      const reset = parseInt(res.headers.get('ratelimit-reset') ?? '10', 10);
      console.warn(`  429; backing off ${reset}s`);
      await sleep((reset + 1) * 1000);
      continue;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
    return res.json();
  }
  throw new Error(`giving up after retries: ${url}`);
}

// ── shape one player into a compact rate-model record ────────────────────────
function toRecord(player, gainedWeek) {
  const skills = {};
  const src = player?.latestSnapshot?.data?.skills ?? {};
  for (const [name, s] of Object.entries(src)) {
    if (name === 'overall') continue;
    skills[name] = { level: s.level, xp: s.experience, rank: s.rank };
  }
  const gw = {};
  const gsrc = gainedWeek?.data?.skills ?? {};
  for (const [name, g] of Object.entries(gsrc)) {
    if (name === 'overall') continue;
    const gained = g?.experience?.gained ?? 0;
    if (gained > 0) gw[name] = gained;
  }
  return {
    v: 1,
    ts: Date.now(),
    username: player.username,
    type: player.type,
    build: player.build,
    country: player.country ?? null,
    combat_level: player.combatLevel,
    exp: player.exp,
    ehp: player.ehp,
    ehb: player.ehb,
    ttm: player.ttm,
    skills,
    gained_week: gw, // per-skill XP gained in the last week (the rate signal)
  };
}

// ── collect ───────────────────────────────────────────────────────────────────
async function collectGroupIds() {
  if (ONE_GROUP) return [ONE_GROUP];
  const ids = [];
  // page through groups by memberCount-ish default ordering
  for (let offset = 0; ids.length < N_GROUPS; offset += 50) {
    const page = await get(`${BASE}/groups?limit=50&offset=${offset}`);
    if (!Array.isArray(page) || page.length === 0) break;
    for (const g of page) {
      if (g.memberCount >= 20) ids.push(g.id); // skip tiny/dead groups
      if (ids.length >= N_GROUPS) break;
    }
  }
  return ids;
}

async function membersOf(groupId) {
  const g = await get(`${BASE}/groups/${groupId}`);
  const memberships = g?.memberships ?? [];
  return memberships.map(m => m?.player?.username).filter(Boolean);
}

async function main() {
  const outPath = path.resolve(OUT);
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  const stream = fs.createWriteStream(outPath, { flags: 'a' });
  const seen = new Set();
  let written = 0;

  console.log(`WOM collect: groups=${ONE_GROUP ?? N_GROUPS} max-members=${MAX_MEMBERS} rpm=${RPM} key=${API_KEY ? 'yes' : 'no'}`);
  const groupIds = await collectGroupIds();
  console.log(`groups: ${groupIds.join(', ') || '(none)'}`);

  for (const gid of groupIds) {
    let members = [];
    try { members = await membersOf(gid); }
    catch (e) { console.warn(`group ${gid}: ${e.message}`); continue; }
    members = members.slice(0, MAX_MEMBERS);
    console.log(`group ${gid}: ${members.length} members`);

    for (const name of members) {
      if (seen.has(name.toLowerCase())) continue;
      seen.add(name.toLowerCase());
      try {
        const enc = encodeURIComponent(name);
        const player = await get(`${BASE}/players/${enc}`);
        const gained = await get(`${BASE}/players/${enc}/gained?period=week`);
        stream.write(JSON.stringify(toRecord(player, gained)) + '\n');
        written++;
        if (written % 10 === 0) console.log(`  ...${written} written`);
      } catch (e) {
        console.warn(`  ${name}: ${e.message}`);
      }
    }
  }
  stream.end();
  console.log(`done: ${written} records -> ${outPath}`);
}

main().catch(e => { console.error(e); process.exit(1); });
