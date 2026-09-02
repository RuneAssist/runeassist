// Port of plugin FlipScorer.java — ranks GE flip candidates from public OSRS-wiki
// prices. ingest-server.mjs serves this as POST/GET /v1/flips so scoring can iterate
// without a plugin rebuild. Wiki 1h/5m averages only — do not feed the graph forecast ML.

const WIKI_UA = 'RuneAssist-ingest/1.0 (github.com/nickbeddows-ctrl/osrs-mcp-plugin)';
const WIKI = 'https://prices.runescape.wiki/api/v1/osrs';
const PRICE_TTL = 60_000;
const MAP_TTL = 60 * 60 * 1000;
const STALE_TRADE_SEC = 90 * 60;

const TAX_RATE = 0.02;
const TAX_CAP = 5_000_000;
const TAX_MAX_PRICE = 250_000_000;
const TAX_EXEMPT = new Set([
  8011, 365, 2309, 882, 806, 1891, 8010, 1755, 28824, 2140, 2142, 8009, 5325, 1785, 2347,
  347, 884, 807, 28790, 379, 8008, 355, 2327, 558, 1733, 13190, 233, 351, 5341, 2552, 329,
  8794, 5329, 5343, 1735, 315, 952, 886, 808, 8013, 361, 8007, 5331,
]);

const RISK = {
  low:    { minVolume: 2000, minSideVolume: 400, minMargin: 30, maxMarginPct: 8,  maxImbalance: 0.45 },
  medium: { minVolume: 800,  minSideVolume: 150, minMargin: 20, maxMarginPct: 12, maxImbalance: 0.55 },
  high:   { minVolume: 400,  minSideVolume: 50,  minMargin: 15, maxMarginPct: 20, maxImbalance: 0.75 },
};

let meta = new Map();       // id -> { name, limit, members }
let mappingAt = 0;
let latest = new Map();     // id -> { high, low, highTime, lowTime }
let volume1h = new Map();   // id -> { highVol, lowVol, avgHigh, avgLow }
let volume5m = new Map();
let pricesAt = 0;
let inflight = null;

async function wikiJson(path) {
  const r = await fetch(`${WIKI}/${path}`, { headers: { 'User-Agent': WIKI_UA } });
  if (!r.ok) throw new Error(`wiki ${path} HTTP ${r.status}`);
  return r.json();
}

async function loadMapping() {
  const arr = await wikiJson('mapping');
  const next = new Map();
  if (Array.isArray(arr)) {
    for (const o of arr) {
      if (!o || o.id == null) continue;
      const id = Number(o.id);
      next.set(id, {
        name: o.name != null ? String(o.name) : `item ${id}`,
        limit: o.limit != null ? Number(o.limit) || 0 : 0,
        members: o.members !== false,
      });
    }
  }
  if (next.size) { meta = next; mappingAt = Date.now(); }
}

function parseLatest(j) {
  const out = new Map();
  const data = j && j.data && typeof j.data === 'object' ? j.data : {};
  for (const [k, o] of Object.entries(data)) {
    if (!o) continue;
    const high = o.high != null ? Number(o.high) : 0;
    const low = o.low != null ? Number(o.low) : 0;
    if (!(high > 0 || low > 0)) continue;
    out.set(Number(k), {
      high, low,
      highTime: o.highTime != null ? Number(o.highTime) : 0,
      lowTime: o.lowTime != null ? Number(o.lowTime) : 0,
    });
  }
  return out;
}

function parseVolume(j) {
  const out = new Map();
  const data = j && j.data && typeof j.data === 'object' ? j.data : {};
  for (const [k, o] of Object.entries(data)) {
    if (!o) continue;
    out.set(Number(k), {
      highVol: Number(o.highPriceVolume) || 0,
      lowVol: Number(o.lowPriceVolume) || 0,
      avgHigh: o.avgHighPrice != null ? Number(o.avgHighPrice) || 0 : 0,
      avgLow: o.avgLowPrice != null ? Number(o.avgLowPrice) || 0 : 0,
    });
  }
  return out;
}

async function loadPrices() {
  const [lat, h, f] = await Promise.all([
    wikiJson('latest'),
    wikiJson('1h'),
    wikiJson('5m'),
  ]);
  latest = parseLatest(lat);
  volume1h = parseVolume(h);
  volume5m = parseVolume(f);
  pricesAt = Date.now();
}

export function ensureMarketCache() {
  const pricesFresh = latest.size > 0 && Date.now() - pricesAt < PRICE_TTL;
  const mapFresh = meta.size > 0 && Date.now() - mappingAt < MAP_TTL;
  if (pricesFresh && mapFresh) return Promise.resolve();
  if (inflight) return inflight;
  inflight = (async () => {
    try {
      if (!mapFresh) await loadMapping();
      if (!pricesFresh) await loadPrices();
    } finally {
      inflight = null;
    }
  })();
  return inflight;
}

function taxAmount(id, price) {
  if (!(price > 0) || TAX_EXEMPT.has(id)) return 0;
  if (price >= TAX_MAX_PRICE) return TAX_CAP;
  return Math.floor(price * TAX_RATE);
}

function isOddName(name) {
  if (!name) return true;
  const n = String(name).toLowerCase();
  return n.includes('placeholder') || n.startsWith('broken ') || n.includes('(nz)');
}

function pickPrices(p, v1, v5, timeframeMinutes, nowSec) {
  if (timeframeMinutes <= 30 && v5 && v5.avgLow > 0 && v5.avgHigh > v5.avgLow) {
    return { buy: v5.avgLow, sell: v5.avgHigh };
  }
  if (v1 && v1.avgLow > 0 && v1.avgHigh > v1.avgLow) {
    return { buy: v1.avgLow, sell: v1.avgHigh };
  }
  if (!p) return null;
  const { high, low, highTime, lowTime } = p;
  if (!(high > 0) || !(low > 0) || high <= low) return null;
  if (nowSec - highTime > STALE_TRADE_SEC || nowSec - lowTime > STALE_TRADE_SEC) return null;
  return { buy: low, sell: high };
}

function liquidityQty(timeframeMinutes, perHour, v5) {
  let perMinute = perHour / 60;
  if (timeframeMinutes <= 30 && v5) {
    const side5 = Math.max(0, Math.min(v5.highVol, v5.lowVol));
    if (side5 > 0) perMinute = side5 / 5;
  }
  return Math.max(1, Math.floor(perMinute * timeframeMinutes));
}

function asIntMap(obj) {
  const out = new Map();
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return out;
  for (const [k, v] of Object.entries(obj)) {
    const id = Number(k);
    if (!Number.isInteger(id) || id <= 0) continue;
    const n = Number(v);
    if (!Number.isFinite(n)) continue;
    out.set(id, Math.floor(n));
  }
  return out;
}

function asIdSet(arr) {
  const out = new Set();
  if (!Array.isArray(arr)) return out;
  for (const v of arr) {
    const id = Number(v);
    if (Number.isInteger(id) && id > 0) out.add(id);
  }
  return out;
}

function remainingFor(id, geLimit, remainingBuyLimit, usedBuyLimit) {
  if (remainingBuyLimit.has(id)) return remainingBuyLimit.get(id);
  if (usedBuyLimit.has(id)) return geLimit - usedBuyLimit.get(id);
  return geLimit;
}

function riskProfile(risk) {
  const k = String(risk || 'medium').toLowerCase();
  return RISK[k] || RISK.medium;
}

function asBool(v, defaultVal) {
  if (v === undefined || v === null || v === '') return defaultVal;
  if (v === false || v === 0 || v === 'false' || v === '0') return false;
  if (v === true || v === 1 || v === 'true' || v === '1') return true;
  return defaultVal;
}

function parseConstraints(raw) {
  const c = raw && typeof raw === 'object' ? raw : {};
  let membersItemsAllowed = asBool(c.membersItemsAllowed, true);
  if (asBool(c.f2pOnly, false) || c.membersWorld === false || c.membersWorld === 'false') {
    membersItemsAllowed = false;
  }
  const top = Math.max(1, Math.min(50, Number(c.top || c.limit) || 12));
  return {
    capital: Math.max(0, Math.floor(Number(c.capital) || 0)),
    timeframeMinutes: Math.max(1, Math.min(24 * 60, Math.floor(Number(c.timeframeMinutes ?? c.timeframe) || 5))),
    risk: riskProfile(c.risk || c.riskLevel),
    membersItemsAllowed,
    remainingSlots: Math.max(1, Math.floor(Number(c.remainingSlots) || 4)),
    remainingBuyLimit: asIntMap(c.remainingBuyLimit),
    usedBuyLimit: asIntMap(c.usedBuyLimit),
    excludeIds: new Set([...asIdSet(c.blockedIds), ...asIdSet(c.skippedIds), ...asIdSet(c.blocked)]),
    minPredictedProfit: Math.max(0, Math.floor(Number(c.minPredictedProfit) || 0)),
    top,
  };
}

function scoreUniverse(c) {
  const nowSec = Math.floor(Date.now() / 1000);
  const rows = [];
  const slots = c.remainingSlots;
  const risk = c.risk;

  for (const [id, m] of meta) {
    if (m.limit <= 0) continue;
    if (!c.membersItemsAllowed && m.members) continue;
    if (c.excludeIds.has(id)) continue;
    if (isOddName(m.name)) continue;

    const v1 = volume1h.get(id);
    if (!v1) continue;
    const p = latest.get(id);
    const v5 = volume5m.get(id);

    const highVol = v1.highVol, lowVol = v1.lowVol;
    const vol = highVol + lowVol;
    if (vol < risk.minVolume) continue;
    if (Math.min(highVol, lowVol) < risk.minSideVolume) continue;

    const prices = pickPrices(p, v1, v5, c.timeframeMinutes, nowSec);
    if (!prices) continue;
    const { buy, sell } = prices;
    if (!(buy > 0) || !(sell > 0) || sell <= buy) continue;

    const tax = taxAmount(id, sell);
    const margin = sell - buy - tax;
    if (margin < risk.minMargin) continue;
    const marginPct = margin * 100 / buy;
    if (marginPct > risk.maxMarginPct) continue;

    const imbalance = vol > 0 ? Math.abs(highVol - lowVol) / vol : 1;
    if (imbalance > risk.maxImbalance) continue;

    const perHour = Math.max(1, Math.min(highVol, lowVol));
    const liqQty = liquidityQty(c.timeframeMinutes, perHour, v5);
    let qtyCap = Math.min(m.limit, liqQty);
    const remainingKnown = c.remainingBuyLimit.has(id) || c.usedBuyLimit.has(id);
    const remaining = remainingFor(id, m.limit, c.remainingBuyLimit, c.usedBuyLimit);
    if (remaining <= 0) continue;
    qtyCap = Math.min(qtyCap, remaining);
    const budget = c.capital > 0 ? Math.min(c.capital, Math.floor(c.capital / slots)) : 0;
    if (budget > 0) qtyCap = Math.min(qtyCap, Math.floor(budget / buy));
    qtyCap = Math.floor(qtyCap);
    if (qtyCap < 1) continue;

    const projected = margin * qtyCap;
    if (c.minPredictedProfit > 0 && projected < c.minPredictedProfit) continue;

    const fillHrs = qtyCap / perHour;
    const spreadRisk = marginPct > 15 ? 0.35 : 0;
    const liqRisk = vol < risk.minVolume * 4 ? 0.4 : 0;
    const riskScore = Math.min(0.9, imbalance * 0.6 + spreadRisk + liqRisk);
    const turnover = 1 / Math.max(0.15, fillHrs);
    const score = Math.round(margin * qtyCap * turnover * (1 - riskScore));

    const flags = [];
    if (imbalance > 0.5) flags.push('one-sided');
    if (spreadRisk > 0) flags.push('wide-spread');
    if (liqRisk > 0) flags.push('thin');

    const row = {
      id,
      name: m.name,
      buy_at: buy,
      sell_at: sell,
      margin_post_tax: margin,
      margin_pct: Math.round(marginPct * 10) / 10,
      suggested_qty: qtyCap,
      ge_limit: m.limit,
      members: m.members,
      est_fill_hours: Math.round(fillHrs * 100) / 100,
      projected_profit: projected,
      flags,
      score,
    };
    if (remainingKnown) row.limit_remaining = remaining;
    rows.push(row);
  }
  rows.sort((a, b) => b.score - a.score);
  return rows;
}

export async function rankFlips(raw) {
  await ensureMarketCache();
  const c = parseConstraints(raw);
  const rows = scoreUniverse(c).slice(0, c.top);
  return {
    ok: true,
    source: 'wiki',
    generated_at: Date.now(),
    candidates: rows,
  };
}

export function cacheStatus() {
  return {
    mapping: meta.size,
    latest: latest.size,
    volume1h: volume1h.size,
    volume5m: volume5m.size,
    mappingAgeMs: mappingAt ? Date.now() - mappingAt : null,
    pricesAgeMs: pricesAt ? Date.now() - pricesAt : null,
  };
}
