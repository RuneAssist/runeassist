#!/usr/bin/env node
// Build-time generator: fetches OSRS Wiki quest requirement + XP-reward data and
// emits src/main/resources/com/osrsmcp/quest_data.json (bundled into the plugin).
// Sources:
//   - Module:Questreq/data        (prereq quests + skill requirements)
//   - Quest experience rewards     (fixed XP rewards per quest/skill)
// Plus a small hand-maintained quest_unlocks.json (marquee unlocks + xp choices).
// Run:  node tools/gen-quest-data.mjs
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const UA = 'osrs-mcp-plugin/1.0 (github.com/nickbeddows-ctrl/osrs-mcp-plugin)';
const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const OUT  = join(ROOT, 'src/main/resources/com/osrsmcp/quest_data.json');
const HAND = join(ROOT, 'tools/quest_unlocks.json');
const QUESTREQ_URL = 'https://oldschool.runescape.wiki/w/Module:Questreq/data?action=raw';
const XP_URL       = 'https://oldschool.runescape.wiki/w/Quest_experience_rewards?action=raw';
const OQG_URL      = 'https://oldschool.runescape.wiki/w/Optimal_quest_guide?action=raw';

async function fetchText(url) {
  const r = await fetch(url, { headers: { 'User-Agent': UA } });
  if (!r.ok) throw new Error(`HTTP ${r.status} for ${url}`);
  return await r.text();
}

// --- parse Module:Questreq/data (line-based state machine) -------------------
const unesc = s => s.replace(/\\(['"\\])/g, '$1');           // Lua \' \" \\ -> ' " \
const LSTR = "'((?:\\\\.|[^'\\\\])*)'";                        // a Lua single-quoted string (handles \')

function parseQuestreq(lua) {
  lua = lua.replace(/--\[\[[\s\S]*?\]\]/g, '');   // strip Lua block comments (contains the quest TEMPLATE)
  const quests = {};
  let cur = null, mode = null;
  // The source mixes tabs and spaces, so match a quest header at ANY indentation.
  // Distinguish it from the reserved ['quests']/['skills'] sub-keys by checking those first.
  const headerRe = new RegExp('^\\s*\\[' + LSTR + '\\]\\s*=\\s*\\{');
  const strRe    = new RegExp(LSTR);
  const skillRe  = new RegExp('\\{\\s*' + LSTR + '\\s*,\\s*(\\d+)((?:\\s*,\\s*' + LSTR + ')*)\\s*\\}');
  for (const raw of lua.split(/\r?\n/)) {
    if (/^\s*\['quests'\]/.test(raw)) { mode = 'quests'; continue; }
    if (/^\s*\['skills'\]/.test(raw)) { mode = 'skills'; continue; }
    const header = raw.match(headerRe);                         // top-level quest entry (any indent)
    if (header) { cur = unesc(header[1]); quests[cur] = { quests: [], skills: {}, skills_ironman: {}, quest_points: 0 }; mode = null; continue; }
    if (!cur) continue;
    if (mode === 'quests') {
      const m = raw.match(strRe);
      if (m) quests[cur].quests.push(unesc(m[1]));
    } else if (mode === 'skills') {
      const m = raw.match(skillRe);
      if (m) {
        const skill = unesc(m[1]).toLowerCase(), level = parseInt(m[2], 10);
        const mods = m[3] || '';
        if (skill === 'quest point' || skill === 'quest points') { quests[cur].quest_points = level; }
        else if (/'ironman'/.test(mods)) quests[cur].skills_ironman[skill] = level;
        else quests[cur].skills[skill] = level;
      }
    }
  }
  return quests;
}

// --- parse Quest experience rewards -----------------------------------------
function parseXpRewards(wt) {
  const xp = {};
  let curQuest = null;
  for (const raw of wt.split(/\r?\n/)) {
    const rid = raw.match(/data-rowid="(.+?)"/);
    if (rid) { curQuest = rid[1]; if (!xp[curQuest]) xp[curQuest] = {}; continue; }
    // {{+=|skill|1,234.5|echo=2}}  -- may appear more than once per line
    const re = /\{\{\+=\|([a-z ]+)\|([\d,\.]+)/gi;
    let m;
    while ((m = re.exec(raw)) !== null) {
      if (!curQuest) continue;
      const skill = m[1].trim().toLowerCase();
      const amount = parseFloat(m[2].replace(/,/g, ''));
      if (!isNaN(amount)) xp[curQuest][skill] = (xp[curQuest][skill] || 0) + amount;
    }
  }
  return xp;
}

// --- parse the Optimal Quest Guide ordered table ----------------------------
function parseOptimalOrder(wt) {
  const order = [];
  let inRow = false;
  for (const raw of wt.split(/\r?\n/)) {
    if (/^\|- data-rowid="/.test(raw)) { inRow = true; continue; }
    if (inRow) {
      const m = raw.match(/\[\[([^\]|]+)(?:\|[^\]]*)?\]\]/);   // first wikilink = the quest/activity
      if (m) { order.push(m[1].trim()); inRow = false; }
    }
  }
  return order;
}

(async () => {
  console.log('Fetching sources...');
  const [luaTxt, xpTxt, oqgTxt] = await Promise.all([fetchText(QUESTREQ_URL), fetchText(XP_URL), fetchText(OQG_URL)]);
  const reqs  = parseQuestreq(luaTxt);
  const xp    = parseXpRewards(xpTxt);
  const order = parseOptimalOrder(oqgTxt);
  const hand  = existsSync(HAND) ? JSON.parse(readFileSync(HAND, 'utf8')) : {};

  // union of quest names from requirements + xp + hand data (ignore _meta keys)
  const handKeys = Object.keys(hand).filter(k => !k.startsWith('_'));
  const names = new Set([...Object.keys(reqs), ...Object.keys(xp), ...handKeys]);
  const out = { _generated: new Date().toISOString(), _source: 'oldschool.runescape.wiki', optimal_order: order, quests: {} };
  for (const name of [...names].sort()) {
    const r = reqs[name] || { quests: [], skills: {}, skills_ironman: {}, quest_points: 0 };
    const h = hand[name] || {};
    out.quests[name] = {
      requirements: { quests: r.quests, skills: r.skills, skills_ironman: r.skills_ironman, quest_points: r.quest_points || 0 },
      xp_rewards: xp[name] || {},
      ...(h.xp_choice ? { xp_choice: h.xp_choice } : {}),
      ...(h.unlocks ? { unlocks: h.unlocks } : {})
    };
  }
  mkdirSync(dirname(OUT), { recursive: true });
  writeFileSync(OUT, JSON.stringify(out, null, 1));
  const nReq = Object.keys(reqs).length, nXp = Object.keys(xp).length;
  console.log(`quests: ${Object.keys(out.quests).length}  (reqs ${nReq}, xp ${nXp}, hand ${Object.keys(hand).length}, optimal_order ${order.length})`);
  console.log(`wrote ${OUT}`);
})().catch(e => { console.error('FAILED:', e.message); process.exit(1); });
