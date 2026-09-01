#!/usr/bin/env node
// flip-model.mjs — v1 flip predictor (rules/statistical, no ML), per docs/flip-model-goal.md.
//
// Ranks GE items by a margin x liquidity score (penalised for risk) using the public
// OSRS Wiki price+volume API — the same source WikiPriceService uses. Outputs, per item:
// buy/sell price, margin after GE tax, margin%, 1h volume, estimated buy-limit fill time,
// suggested quantity for a capital budget, and risk flags. This is the shippable v1 that
// ports into the plugin's get_flip_suggestions.
//
// Usage:
//   node tools/flip-model.mjs --capital=10m --min-volume=1000 --min-margin=20 --top=20
//
// NOTE: GE tax is modelled as TAX_RATE of the sell price, capped per item, exempt below a
// small threshold. Verify the CURRENT in-game tax rate/cap before shipping — Jagex has
// changed it before.

const BASE = 'https://prices.runescape.wiki/api/v1/osrs';
const UA = 'runeassist-flip-model/1.0 (contact tom@tpharrison.co.uk)';

// GE tax params — CONFIRM against current game rules before shipping.
const TAX_RATE = 0.01;          // 1% of sell price
const TAX_CAP = 5_000_000;      // capped per item
const TAX_EXEMPT_BELOW = 50;    // no tax on cheap items

// ── args ──────────────────────────────────────────────────────────────────────
const args = Object.fromEntries(process.argv.slice(2).map(a => {
  const m = a.match(/^--([^=]+)(?:=(.*))?$/); return m ? [m[1], m[2] ?? true] : [a, true];
}));
const num = (v, d) => {
  if (v == null) return d;
  const s = String(v).toLowerCase().replace(/,/g, '');
  const mul = s.endsWith('m') ? 1e6 : s.endsWith('k') ? 1e3 : 1;
  return Math.round(parseFloat(s) * mul);
};
const CAPITAL    = num(args.capital, 10_000_000);
const MIN_VOLUME = num(args['min-volume'], 500);   // 1h units traded
const MIN_MARGIN = num(args['min-margin'], 10);     // gp after tax
const TOP        = num(args.top, 20);

async function get(path) {
  const res = await fetch(`${BASE}/${path}`, { headers: { 'User-Agent': UA } });
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${path}`);
  return res.json();
}
const tax = sell => sell < TAX_EXEMPT_BELOW ? 0 : Math.min(TAX_CAP, Math.floor(sell * TAX_RATE));
const fmt = n => {
  const a = Math.abs(n);
  return a >= 1e6 ? (n / 1e6).toFixed(2) + 'M' : a >= 1e3 ? (n / 1e3).toFixed(1) + 'k' : String(n);
};
const fmtTime = h => h >= 24 ? (h / 24).toFixed(1) + 'd' : h >= 1 ? h.toFixed(1) + 'h' : Math.round(h * 60) + 'm';

async function main() {
  console.error('fetching mapping, latest, 1h…');
  const [mapping, latest, hour] = await Promise.all([get('mapping'), get('latest'), get('1h')]);
  const meta = new Map(mapping.map(m => [m.id, m]));
  const L = latest.data, H = hour.data;

  const rows = [];
  for (const [idStr, l] of Object.entries(L)) {
    const id = +idStr;
    const m = meta.get(id);
    const h = H[idStr];
    if (!m || !h) continue;
    const buy = l.low, sell = l.high;            // buy at low (insta-sell price), sell at high
    if (!(buy > 0) || !(sell > 0) || sell <= buy) continue;

    const vol = (h.highPriceVolume ?? 0) + (h.lowPriceVolume ?? 0); // 1h units
    if (vol < MIN_VOLUME) continue;

    const margin = sell - buy - tax(sell);
    if (margin < MIN_MARGIN) continue;

    const marginPct = margin / buy * 100;
    const limit = m.limit ?? 0;

    // Estimate fill time for a full buy-limit order from hourly throughput.
    const buyVol = h.lowPriceVolume ?? 0;   // people selling to us (we buy)
    const sellVol = h.highPriceVolume ?? 0; // people buying from us (we sell)
    const perHour = Math.max(1, Math.min(buyVol, sellVol)); // bottleneck side
    const qtyCap = Math.min(limit || Infinity, Math.floor(CAPITAL / buy)) || 0;
    const fillHrs = qtyCap > 0 ? qtyCap / perHour : Infinity;

    // Risk penalty: thin liquidity, extreme spread, or one-sided volume.
    const imbalance = Math.abs(buyVol - sellVol) / vol;        // 0 balanced .. 1 one-sided
    const spreadRisk = marginPct > 25 ? 0.5 : 0;               // suspiciously wide = flip trap
    const liqRisk = vol < MIN_VOLUME * 4 ? 0.4 : 0;
    const risk = Math.min(0.9, imbalance * 0.6 + spreadRisk + liqRisk);

    // Score: profit per full order * turnover, discounted by risk and fill time.
    const perOrderProfit = margin * qtyCap;
    const turnover = 1 / Math.max(0.25, fillHrs);             // orders/hour-ish
    const score = perOrderProfit * turnover * (1 - risk);

    const flags = [];
    if (imbalance > 0.5) flags.push('one-sided');
    if (spreadRisk) flags.push('wide-spread');
    if (liqRisk) flags.push('thin');

    rows.push({ name: m.name, buy, sell, margin, marginPct, vol, limit, qtyCap, fillHrs, score, flags });
  }

  rows.sort((a, b) => b.score - a.score);
  const top = rows.slice(0, TOP);

  console.log(`\n# Flip candidates — capital ${fmt(CAPITAL)}, min-vol ${MIN_VOLUME}/h, min-margin ${MIN_MARGIN}gp`);
  console.log(`# ${rows.length} items passed filters; top ${top.length} by score (margin x liquidity - risk)\n`);
  console.log('  item                      buy       sell      margin   marg%   1h vol   qty    ~fill   flags');
  for (const r of top) {
    console.log(
      '  ' + r.name.slice(0, 24).padEnd(24) +
      ' ' + fmt(r.buy).padStart(8) +
      ' ' + fmt(r.sell).padStart(9) +
      ' ' + (fmt(r.margin) + 'gp').padStart(8) +
      ' ' + (r.marginPct.toFixed(1) + '%').padStart(6) +
      ' ' + fmt(r.vol).padStart(7) +
      ' ' + String(r.qtyCap).padStart(6) +
      ' ' + fmtTime(r.fillHrs).padStart(6) +
      '  ' + r.flags.join(',')
    );
  }
  console.log('\n(v1 heuristic — verify tax rate; fill time is a volume estimate, not a guarantee.)');
}

main().catch(e => { console.error(e); process.exit(1); });
