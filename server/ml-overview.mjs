// Server-side port of scripts/analyze-fills.mjs -- reconstructs GE offer lifecycles from the
// uploaded ge_offer-*.jsonl telemetry (place -> fill/cancel, with MODIFY reprices correctly
// merged into one logical flip attempt) and computes the same fill-rate / fraction-filled
// metrics, across every account that has ever uploaded telemetry to this server rather than
// just one local machine's files. See scripts/analyze-fills.mjs for the reasoning behind each
// metric (binary "fully filled" undercounts partial-then-cancelled as failure, etc.) -- kept
// in sync by hand; there's no shared package between the plugin-side script and this server.

import fs from 'node:fs';
import path from 'node:path';

const CHAIN_GAP_MS = 5 * 60 * 1000;
const WINDOWS_MIN = [5, 15, 30, 60];

function sig(o) {
  const side = (o.state === 'BUYING' || o.state === 'BOUGHT' || o.state === 'CANCELLED_BUY') ? 'buy' : 'sell';
  return `${o.item_id}|${o.price}|${o.total_quantity}|${side}`;
}

function median(arr) {
  if (arr.length === 0) return null;
  const s = [...arr].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
}

function fractionFilledAt(chain, minutes) {
  const cutoff = chain.startTs + minutes * 60 * 1000;
  let qty = 0;
  for (const pt of chain.trail) {
    if (pt.ts > cutoff) break;
    qty = pt.qtySold;
  }
  return chain.originalTotalQty > 0 ? Math.min(1, qty / chain.originalTotalQty) : 0;
}

/** Reads up to `maxDays` most-recent ge_offer-*.jsonl files from dataDir and reconstructs
 * chains. Returns null if no telemetry files exist yet. */
export function computeMlOverview(dataDir, maxDays = 14) {
  const files = fs.readdirSync(dataDir)
    .filter((f) => /^ge_offer-\d{4}-\d{2}-\d{2}\.jsonl$/.test(f))
    .sort()
    .slice(-maxDays);
  if (files.length === 0) return null;

  const streams = new Map(); // acct|slot -> rows[]
  for (const f of files) {
    const lines = fs.readFileSync(path.join(dataDir, f), 'utf8').split('\n').filter(Boolean);
    for (const line of lines) {
      let o;
      try { o = JSON.parse(line); } catch { continue; }
      const key = `${o.acct}|${o.slot}`;
      if (!streams.has(key)) streams.set(key, []);
      streams.get(key).push(o);
    }
  }

  const rawEpisodes = [];
  for (const rows of streams.values()) {
    rows.sort((a, b) => a.ts - b.ts);
    let cur = null;
    for (const o of rows) {
      if (o.state === 'EMPTY') {
        if (cur) { rawEpisodes.push(cur); cur = null; }
        continue;
      }
      const s = sig(o);
      if (!cur || cur.sig !== s) {
        if (cur) rawEpisodes.push(cur);
        cur = {
          sig: s,
          itemId: o.item_id,
          side: (o.state === 'BUYING' || o.state === 'BOUGHT' || o.state === 'CANCELLED_BUY') ? 'buy' : 'sell',
          startTs: o.ts,
          endTs: o.ts,
          firstFillTs: null,
          finalState: o.state,
          finalQtySold: o.quantity_sold,
          totalQty: o.total_quantity,
          trail: [],
        };
      }
      cur.endTs = o.ts;
      cur.finalState = o.state;
      cur.finalQtySold = o.quantity_sold;
      cur.totalQty = o.total_quantity;
      if (o.quantity_sold > 0 && cur.firstFillTs === null) cur.firstFillTs = o.ts;
      cur.trail.push({ ts: o.ts, qtySold: o.quantity_sold });
    }
    if (cur) rawEpisodes.push(cur);
  }

  const validEpisodes = rawEpisodes.filter((e) => e.totalQty > 0).sort((a, b) => a.startTs - b.startTs);

  const chains = [];
  let chain = null;
  for (const e of validEpisodes) {
    const continues = chain
      && chain.itemId === e.itemId
      && chain.side === e.side
      && chain.lastFinalState !== 'BOUGHT'
      && chain.lastFinalState !== 'SOLD'
      && (e.startTs - chain.lastEndTs) <= CHAIN_GAP_MS;
    if (continues) {
      const priorCumulative = chain.finalQtySold;
      chain.segments += 1;
      chain.endTs = e.endTs;
      chain.lastEndTs = e.endTs;
      chain.lastFinalState = e.finalState;
      chain.finalQtySold += e.finalQtySold;
      if (e.firstFillTs !== null && chain.firstFillTs === null) chain.firstFillTs = e.firstFillTs;
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
        originalTotalQty: e.totalQty,
        firstFillTs: e.firstFillTs,
        segments: 1,
        trail: [...e.trail],
      };
    }
  }
  if (chain) chains.push(chain);

  const full = chains.filter((c) => c.lastFinalState === 'BOUGHT' || c.lastFinalState === 'SOLD');
  const cancelled = chains.filter((c) => c.lastFinalState === 'CANCELLED_BUY' || c.lastFinalState === 'CANCELLED_SELL');
  const partialCancelled = cancelled.filter((c) => c.finalQtySold > 0);
  const zeroCancelled = cancelled.filter((c) => c.finalQtySold === 0);
  const stillOpen = chains.length - full.length - cancelled.length;

  const fillTimesFull = full.map((c) => c.endTs - c.startTs).filter((t) => t > 0);
  const firstFillTimes = chains.filter((c) => c.firstFillTs !== null).map((c) => c.firstFillTs - c.startTs);

  const bySide = {};
  for (const side of ['buy', 'sell']) {
    const sideChains = chains.filter((c) => c.side === side);
    const sideFull = sideChains.filter((c) => c.lastFinalState === 'BOUGHT' || c.lastFinalState === 'SOLD');
    bySide[side] = {
      n: sideChains.length,
      fillRate: sideChains.length ? sideFull.length / sideChains.length : 0,
      fractionFilledByWindow: Object.fromEntries(
        WINDOWS_MIN.map((m) => [m, median(sideChains.map((c) => fractionFilledAt(c, m)))]),
      ),
    };
  }

  const repriced = chains.filter((c) => c.segments > 1);
  const repricedFull = repriced.filter((c) => c.lastFinalState === 'BOUGHT' || c.lastFinalState === 'SOLD');
  const notRepriced = chains.filter((c) => c.segments === 1);
  const notRepricedFull = notRepriced.filter((c) => c.lastFinalState === 'BOUGHT' || c.lastFinalState === 'SOLD');

  const byItem = new Map();
  for (const c of chains) {
    if (!byItem.has(c.itemId)) byItem.set(c.itemId, []);
    byItem.get(c.itemId).push(c);
  }
  const perItem = [...byItem.entries()]
    .map(([itemId, cs]) => {
      const f = cs.filter((c) => c.lastFinalState === 'BOUGHT' || c.lastFinalState === 'SOLD');
      return { itemId: Number(itemId), n: cs.length, fillRate: f.length / cs.length };
    })
    .filter((r) => r.n >= 3)
    .sort((a, b) => b.n - a.n)
    .slice(0, 30);

  return {
    daysAnalyzed: files.length,
    dateRange: [files[0].slice(9, 19), files[files.length - 1].slice(9, 19)],
    accounts: streams.size ? new Set([...streams.keys()].map((k) => k.split('|')[0])).size : 0,
    episodes: {
      raw: validEpisodes.length,
      chains: chains.length,
      repriceRate: validEpisodes.length ? 1 - chains.length / validEpisodes.length : 0,
    },
    outcomes: {
      fullyFilled: full.length,
      partialThenCancelled: partialCancelled.length,
      zeroFillCancelled: zeroCancelled.length,
      stillOpen,
      total: chains.length,
    },
    timing: {
      medianFullFillMs: median(fillTimesFull),
      medianFirstFillMs: median(firstFillTimes),
      firstFillSampleSize: firstFillTimes.length,
    },
    bySide,
    repriceEffect: {
      neverRepriced: { n: notRepriced.length, fillRate: notRepriced.length ? notRepricedFull.length / notRepriced.length : 0 },
      repricedOnceOrMore: { n: repriced.length, fillRate: repriced.length ? repricedFull.length / repriced.length : 0 },
    },
    perItem,
  };
}
