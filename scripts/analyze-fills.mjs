import fs from 'node:fs';
import path from 'node:path';

const dir = process.argv[2];
const files = fs.readdirSync(dir).filter((f) => /^ge_offer-\d{4}-\d{2}-\d{2}\.jsonl$/.test(f)).sort();

const streams = new Map(); // acct|slot -> rows[]
for (const f of files) {
  const lines = fs.readFileSync(path.join(dir, f), 'utf8').split('\n').filter(Boolean);
  for (const line of lines) {
    const o = JSON.parse(line);
    const key = `${o.acct}|${o.slot}`;
    if (!streams.has(key)) streams.set(key, []);
    streams.get(key).push(o);
  }
}

function sig(o) {
  return `${o.item_id}|${o.price}|${o.total_quantity}|${o.state === 'BUYING' || o.state === 'BOUGHT' || o.state === 'CANCELLED_BUY' ? 'buy' : 'sell'}`;
}

const episodes = [];
for (const rows of streams.values()) {
  rows.sort((a, b) => a.ts - b.ts);
  let cur = null;
  for (const o of rows) {
    const isPlaced = o.state !== 'EMPTY';
    if (!isPlaced) {
      if (cur) { episodes.push(cur); cur = null; }
      continue;
    }
    const s = sig(o);
    if (!cur || cur.sig !== s) {
      if (cur) episodes.push(cur);
      cur = {
        sig: s,
        itemId: o.item_id,
        price: o.price,
        totalQty: o.total_quantity,
        side: (o.state === 'BUYING' || o.state === 'BOUGHT' || o.state === 'CANCELLED_BUY') ? 'buy' : 'sell',
        startTs: o.ts,
        endTs: o.ts,
        firstFillTs: null,
        finalState: o.state,
        finalQtySold: o.quantity_sold,
        trail: [],
      };
    }
    cur.endTs = o.ts;
    cur.finalState = o.state;
    cur.finalQtySold = o.quantity_sold;
    cur.trail.push({ ts: o.ts, qtySold: o.quantity_sold });
    if (o.quantity_sold > 0 && cur.firstFillTs === null) cur.firstFillTs = o.ts;
  }
  if (cur) episodes.push(cur);
}

// Drop episodes that never left the initial snapshot state cleanly (total_quantity 0 = noise)
const rawValid = episodes.filter((e) => e.totalQty > 0).sort((a, b) => a.startTs - b.startTs);

// A MODIFY (reprice) cancels the live offer and immediately places a new one at a new price,
// which the raw episode detector above sees as two unrelated episodes -- one "cancelled with
// zero fill" (the pre-reprice offer) followed by a fresh one. That inflates the raw
// zero-fill-cancel rate and deflates per-item fill rate for exactly the items suggestions are
// actively working on. Merge same item+side episodes back into a "chain" (one real flip
// attempt, possibly repriced several times) when the gap between them is short and the prior
// segment didn't already fully fill.
const CHAIN_GAP_MS = 5 * 60 * 1000;
const chains = [];
let chain = null;
for (const e of rawValid) {
  const continuesChain = chain
    && chain.itemId === e.itemId
    && chain.side === e.side
    && chain.lastFinalState !== 'BOUGHT'
    && chain.lastFinalState !== 'SOLD'
    && (e.startTs - chain.lastEndTs) <= CHAIN_GAP_MS;
  if (continuesChain) {
    const priorCumulative = chain.finalQtySold;
    chain.segments.push(e);
    chain.endTs = e.endTs;
    chain.lastEndTs = e.endTs;
    chain.lastFinalState = e.finalState;
    chain.finalQtySold += e.finalQtySold;
    chain.totalQty = e.totalQty; // most recent target (post-partial-fill remaining, if the engine reduces it)
    // originalTotalQty stays as the FIRST segment's target -- what "fraction filled" should be
    // measured against, regardless of whether reprices later shrink total_quantity to "remaining"
    if (e.firstFillTs !== null && chain.firstFillTs === null) chain.firstFillTs = e.firstFillTs;
    // offset this segment's trail by whatever the chain had already accumulated, so the
    // trail reads as one continuous cumulative-fill curve across reprices
    for (const pt of e.trail) chain.trail.push({ ts: pt.ts, qtySold: priorCumulative + pt.qtySold });
  } else {
    if (chain) chains.push(chain);
    chain = {
      itemId: e.itemId,
      side: e.side,
      startTs: e.startTs,
      endTs: e.endTs,
      lastEndTs: e.endTs,
      lastFinalState: e.finalState,
      finalQtySold: e.finalQtySold,
      totalQty: e.totalQty,
      originalTotalQty: e.totalQty,
      firstFillTs: e.firstFillTs,
      segments: [e],
      trail: [...e.trail],
    };
  }
}
if (chain) chains.push(chain);

const valid = rawValid;

const fullyFilled = valid.filter((e) => e.finalState === 'BOUGHT' || e.finalState === 'SOLD');
const cancelled = valid.filter((e) => e.finalState === 'CANCELLED_BUY' || e.finalState === 'CANCELLED_SELL');
const partialCancelled = cancelled.filter((e) => e.finalQtySold > 0);
const zeroFillCancelled = cancelled.filter((e) => e.finalQtySold === 0);

function pct(n, d) { return d === 0 ? 'n/a' : (100 * n / d).toFixed(1) + '%'; }
function median(arr) {
  if (arr.length === 0) return null;
  const s = [...arr].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
}
function p90(arr) {
  if (arr.length === 0) return null;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.floor(s.length * 0.9)];
}

console.log('=== Episode reconstruction ===');
console.log('total episodes:', valid.length);
console.log('fully filled:', fullyFilled.length, pct(fullyFilled.length, valid.length));
console.log('cancelled with partial fill:', partialCancelled.length, pct(partialCancelled.length, valid.length));
console.log('cancelled with zero fill:', zeroFillCancelled.length, pct(zeroFillCancelled.length, valid.length));
console.log('still open at end of log:', valid.length - fullyFilled.length - cancelled.length);

const fillTimesFull = fullyFilled.map((e) => e.endTs - e.startTs).filter((t) => t > 0);
console.log('\n=== Time to full fill (fully filled offers only) ===');
console.log('median ms:', median(fillTimesFull), '(', (median(fillTimesFull) / 1000).toFixed(1), 's)');
console.log('p90 ms:', p90(fillTimesFull), '(', (p90(fillTimesFull) / 1000 / 60).toFixed(1), 'min)');

const firstFillTimes = valid.filter((e) => e.firstFillTs !== null).map((e) => e.firstFillTs - e.startTs);
console.log('\n=== Time to FIRST partial fill (any episode with >=1 fill) ===');
console.log('n:', firstFillTimes.length);
console.log('median ms:', median(firstFillTimes), '(', (median(firstFillTimes) / 1000).toFixed(1), 's)');

console.log('\n=== By side ===');
for (const side of ['buy', 'sell']) {
  const sideEp = valid.filter((e) => e.side === side);
  const sideFull = sideEp.filter((e) => e.finalState === 'BOUGHT' || e.finalState === 'SOLD');
  console.log(side, ': n=', sideEp.length, 'fill rate=', pct(sideFull.length, sideEp.length));
}

// Liquidity proxy: how many episodes per item (more episodes = more frequently traded by this account)
const byItem = new Map();
for (const e of valid) {
  if (!byItem.has(e.itemId)) byItem.set(e.itemId, []);
  byItem.get(e.itemId).push(e);
}
const itemRows = [...byItem.entries()].map(([itemId, eps]) => {
  const full = eps.filter((e) => e.finalState === 'BOUGHT' || e.finalState === 'SOLD');
  return { itemId, n: eps.length, fillRate: full.length / eps.length };
}).filter((r) => r.n >= 3).sort((a, b) => b.n - a.n);

console.log('\n=== Per-item fill rate (items with >=3 episodes today+yesterday) ===');
for (const r of itemRows.slice(0, 20)) {
  console.log(`item ${r.itemId}: n=${r.n} fillRate=${(r.fillRate * 100).toFixed(0)}%`);
}

console.log('\n\n=== CHAIN-LEVEL (reprices merged into one real flip attempt) ===');
console.log('raw episodes:', rawValid.length, '-> merged chains:', chains.length,
  `(${(100 * (1 - chains.length / rawValid.length)).toFixed(0)}% were reprices of an existing attempt, not new placements)`);

const chainFull = chains.filter((c) => c.lastFinalState === 'BOUGHT' || c.lastFinalState === 'SOLD');
const chainCancelled = chains.filter((c) => c.lastFinalState === 'CANCELLED_BUY' || c.lastFinalState === 'CANCELLED_SELL');
const chainPartial = chainCancelled.filter((c) => c.finalQtySold > 0);
const chainZero = chainCancelled.filter((c) => c.finalQtySold === 0);
console.log('fully filled:', chainFull.length, pct(chainFull.length, chains.length));
console.log('cancelled with partial fill:', chainPartial.length, pct(chainPartial.length, chains.length));
console.log('cancelled with zero fill (genuinely no interest):', chainZero.length, pct(chainZero.length, chains.length));
console.log('still open at end of log:', chains.length - chainFull.length - chainCancelled.length);

const repricedChains = chains.filter((c) => c.segments.length > 1);
const repricedFull = repricedChains.filter((c) => c.lastFinalState === 'BOUGHT' || c.lastFinalState === 'SOLD');
const singleChains = chains.filter((c) => c.segments.length === 1);
const singleFull = singleChains.filter((c) => c.lastFinalState === 'BOUGHT' || c.lastFinalState === 'SOLD');
console.log('\n=== Does repricing (MODIFY) actually help fills? ===');
console.log('chains never repriced: n=', singleChains.length, 'fill rate=', pct(singleFull.length, singleChains.length));
console.log('chains repriced >=1x : n=', repricedChains.length, 'fill rate=', pct(repricedFull.length, repricedChains.length),
  '(avg', (repricedChains.reduce((s, c) => s + c.segments.length, 0) / repricedChains.length).toFixed(1), 'segments/chain)');

console.log('\n=== Per-item fill rate, chain-level (items with >=3 chains) ===');
const byItemChain = new Map();
for (const c of chains) {
  if (!byItemChain.has(c.itemId)) byItemChain.set(c.itemId, []);
  byItemChain.get(c.itemId).push(c);
}
const itemChainRows = [...byItemChain.entries()].map(([itemId, cs]) => {
  const full = cs.filter((c) => c.lastFinalState === 'BOUGHT' || c.lastFinalState === 'SOLD');
  return { itemId, n: cs.length, fillRate: full.length / cs.length };
}).filter((r) => r.n >= 3).sort((a, b) => b.n - a.n);
for (const r of itemChainRows.slice(0, 20)) {
  console.log(`item ${r.itemId}: n=${r.n} fillRate=${(r.fillRate * 100).toFixed(0)}%`);
}

// --- Fraction filled within N minutes -------------------------------------------------
// The better ML target: not "did this reach 100%" but "how much of the order did the market
// actually absorb in the window we care about". Uses each chain's own snapshot trail (not
// interpolation) -- the fraction as of the last snapshot at or before startTs + N minutes.
function fractionFilledAt(chainObj, minutes) {
  const cutoff = chainObj.startTs + minutes * 60 * 1000;
  let qty = 0;
  for (const pt of chainObj.trail) {
    if (pt.ts > cutoff) break;
    qty = pt.qtySold;
  }
  return chainObj.originalTotalQty > 0 ? Math.min(1, qty / chainObj.originalTotalQty) : 0;
}

const windows = [5, 15, 30, 60];
console.log('\n\n=== Fraction filled within N minutes (all chains, median / mean) ===');
for (const mins of windows) {
  const fractions = chains.map((c) => fractionFilledAt(c, mins));
  const nonZero = fractions.filter((f) => f > 0);
  const mean = fractions.reduce((a, b) => a + b, 0) / fractions.length;
  console.log(`${mins}min: median=${(median(fractions) * 100).toFixed(0)}% mean=${(mean * 100).toFixed(0)}% `
    + `any-fill-rate=${pct(nonZero.length, fractions.length)}`);
}

console.log('\n=== Same, split by side ===');
for (const side of ['buy', 'sell']) {
  const sideChains = chains.filter((c) => c.side === side);
  const row = windows.map((m) => {
    const fractions = sideChains.map((c) => fractionFilledAt(c, m));
    return `${m}min=${(median(fractions) * 100).toFixed(0)}%`;
  }).join('  ');
  console.log(side, '(n=' + sideChains.length + '):', row);
}
