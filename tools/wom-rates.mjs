#!/usr/bin/env node
// wom-rates.mjs — first-pass "real rates" table from collected WOM data.
//
// Reads the JSONL produced by wom-collect.mjs and, for each (skill, build, level-band),
// reports the distribution of ACTIVE weekly XP gains (players who trained that skill in
// the last week). This is the population activity signal that grounds/replaces the
// wiki's ballpark rates (Track B / docs/wom-ml-research.md).
//
// Note: WOM gives XP/week, not XP/hr (no per-skill hours). So this reports weekly XP for
// active trainers by level band — a real, comparable signal — not a raw xp/hr. Converting
// to xp/hr later needs an hours proxy (e.g. ehp gained).
//
// Usage: node tools/wom-rates.mjs research/wom.jsonl [--skill=slayer] [--min=5]

import fs from 'node:fs';

const file = process.argv[2] ?? 'research/wom.jsonl';
const args = Object.fromEntries(process.argv.slice(3).map(a => {
  const m = a.match(/^--([^=]+)(?:=(.*))?$/); return m ? [m[1], m[2] ?? true] : [a, true];
}));
const ONLY_SKILL = args.skill ?? null;
const MIN_N = parseInt(args.min ?? '3', 10);

function band(level) {
  if (level >= 99) return '99';
  if (level >= 93) return '93-98';
  if (level >= 85) return '85-92';
  if (level >= 70) return '70-84';
  if (level >= 50) return '50-69';
  return '1-49';
}
const BAND_ORDER = ['1-49', '50-69', '70-84', '85-92', '93-98', '99'];

function pct(sorted, p) {
  if (sorted.length === 0) return 0;
  const i = Math.min(sorted.length - 1, Math.floor(p * sorted.length));
  return sorted[i];
}
const fmt = n => n >= 1e6 ? (n / 1e6).toFixed(2) + 'M' : n >= 1e3 ? Math.round(n / 1e3) + 'k' : String(n);

// key "skill|build|band" -> array of weekly-xp-gained for active trainers
const buckets = new Map();
let players = 0;

for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
  if (!line.trim()) continue;
  let r; try { r = JSON.parse(line); } catch { continue; }
  players++;
  const build = r.build ?? 'main';
  for (const [skill, gained] of Object.entries(r.gained_week ?? {})) {
    if (ONLY_SKILL && skill !== ONLY_SKILL) continue;
    if (!(gained > 0)) continue;
    const lvl = r.skills?.[skill]?.level ?? 1;
    const key = `${skill}|${build}|${band(lvl)}`;
    (buckets.get(key) ?? buckets.set(key, []).get(key)).push(gained);
  }
}

// group rows by skill for readable output
const bySkill = new Map();
for (const [key, arr] of buckets) {
  if (arr.length < MIN_N) continue;
  const [skill, build, b] = key.split('|');
  arr.sort((a, z) => a - z);
  (bySkill.get(skill) ?? bySkill.set(skill, []).get(skill)).push({
    build, band: b, n: arr.length, p50: pct(arr, 0.5), p90: pct(arr, 0.9),
  });
}

console.log(`# WOM real-rates (weekly XP for ACTIVE trainers) — ${players} players, buckets>=${MIN_N}\n`);
for (const [skill, rows] of [...bySkill].sort()) {
  rows.sort((a, z) => a.build.localeCompare(z.build) || BAND_ORDER.indexOf(a.band) - BAND_ORDER.indexOf(z.band));
  console.log(`## ${skill}`);
  console.log('  build      band     n     p50/wk    p90/wk');
  for (const r of rows)
    console.log(`  ${r.build.padEnd(9)} ${r.band.padEnd(7)} ${String(r.n).padStart(4)}  ${fmt(r.p50).padStart(8)}  ${fmt(r.p90).padStart(8)}`);
  console.log('');
}
if (bySkill.size === 0) console.log(`(no buckets with n>=${MIN_N} — collect more players with wom-collect.mjs)`);
