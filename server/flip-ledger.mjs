// Port of plugin FlipLedgerEngine.java / LocalFlipLedger.applyToBook — FIFO open/close
// matching plus Stats aggregation. Kept in sync with the Java via server/flip-ledger-vectors.json.
// Do not invent a second profit model; if a vector fails, fix this file or the Java, not the website.

const TAX_RATE = 0.02;
const TAX_CAP = 5_000_000;
const TAX_MAX_PRICE = 250_000_000;
const TAX_EXEMPT = new Set([
  8011, 365, 2309, 882, 806, 1891, 8010, 1755, 28824, 2140, 2142, 8009, 5325, 1785, 2347,
  347, 884, 807, 28790, 379, 8008, 355, 2327, 558, 1733, 13190, 233, 351, 5341, 2552, 329,
  8794, 5329, 5343, 1735, 315, 952, 886, 808, 8013, 361, 8007, 5331,
]);

function taxAmount(itemId, price) {
  if (TAX_EXEMPT.has(Number(itemId))) return 0;
  if (price >= TAX_MAX_PRICE) return TAX_CAP;
  return Math.floor(price * TAX_RATE);
}

function postTaxPrice(itemId, price) {
  return price - taxAmount(itemId, price);
}

function isBuy(type) {
  const t = String(type || '').toUpperCase();
  return t === 'BUY' || t === 'BUYING';
}

function epochSec(ts) {
  if (ts == null) return Math.floor(Date.now() / 1000);
  if (typeof ts === 'number' && Number.isFinite(ts)) {
    return ts > 1e12 ? Math.floor(ts / 1000) : Math.floor(ts);
  }
  const d = new Date(ts);
  if (Number.isNaN(d.getTime())) return Math.floor(Date.now() / 1000);
  return Math.floor(d.getTime() / 1000);
}

function copyFlip(src) {
  return { ...src };
}

function newOpenFlip(book, tx, now) {
  const flip = {
    id: book.flipIds(),
    accountId: book.accountId,
    itemId: tx.itemId,
    openedTime: now,
    openedQuantity: tx.quantity,
    spent: tx.amountSpent,
    closedTime: 0,
    closedQuantity: 0,
    receivedPostTax: 0,
    profit: 0,
    taxPaid: 0,
    status: 'BUYING',
    updatedTime: now,
    deleted: false,
    portfolioId: 1,
    seqNo: 1,
    userId: 0,
  };
  book.openByItemId.set(tx.itemId, flip);
  return flip;
}

export function emptyBook() {
  return {
    accountId: 1,
    flips: new Map(),
    openByItemId: new Map(),
    flipIds: () => crypto.randomUUID(),
  };
}

export function apply(book, transaction) {
  const tx = transaction || {};
  const now = epochSec(tx.timestamp ?? tx.ts);
  const buy = isBuy(tx.type);
  let open = book.openByItemId.get(tx.itemId);
  let flipId = '00000000-0000-0000-0000-000000000000';
  let profitThisTx = 0;
  let touched = null;

  if (buy) {
    if (!open || open.status === 'FINISHED') {
      open = newOpenFlip(book, tx, now);
    } else {
      open.openedQuantity += tx.quantity;
      open.spent += tx.amountSpent;
      open.updatedTime = now;
      open.seqNo += 1;
      open.status = open.closedQuantity > 0 ? 'SELLING' : 'BUYING';
    }
    flipId = open.id;
    book.flips.set(open.id, copyFlip(open));
    touched = open;
  } else if (open && open.status !== 'FINISHED') {
    const remaining = open.openedQuantity - open.closedQuantity;
    const amountToClose = Math.min(remaining, tx.quantity);
    if (amountToClose > 0) {
      const sellPrice = tx.quantity > 0 ? Math.trunc(tx.amountSpent / tx.quantity) : tx.price;
      const sellPostTax = postTaxPrice(tx.itemId, sellPrice);
      const taxEach = sellPrice - sellPostTax;
      const gpOut = Math.trunc((open.spent * amountToClose) / Math.max(1, open.openedQuantity));
      const gpIn = amountToClose * sellPostTax;
      profitThisTx = gpIn - gpOut;

      open.closedQuantity += amountToClose;
      open.receivedPostTax += gpIn;
      open.taxPaid += taxEach * amountToClose;
      open.profit += profitThisTx;
      open.closedTime = now;
      open.updatedTime = now;
      open.seqNo += 1;
      if (open.closedQuantity >= open.openedQuantity) {
        open.status = 'FINISHED';
        book.openByItemId.delete(tx.itemId);
      } else {
        open.status = 'SELLING';
      }
      flipId = open.id;
      book.flips.set(open.id, copyFlip(open));
      touched = open;
    }
  }

  return { profitThisTx, flipId, buy, touched };
}

export function replay(transactions) {
  const book = emptyBook();
  if (!Array.isArray(transactions)) return book;
  for (const tx of transactions) {
    if (tx) apply(book, tx);
  }
  return book;
}

function isTrackedFlip(f) {
  return f && (f.portfolioId === 0 || f.portfolioId === 1);
}

export function statsOf(book) {
  const stats = { profit: 0, gross: 0, taxPaid: 0, flipsMade: 0 };
  for (const f of book.flips.values()) {
    if (!isTrackedFlip(f) || f.deleted) continue;
    stats.profit += f.profit;
    stats.gross += f.spent;
    stats.taxPaid += f.taxPaid;
    stats.flipsMade += 1;
  }
  return stats;
}

export function portfolioValue(book) {
  let value = 0;
  for (const open of book.openByItemId.values()) {
    if (!open || open.status === 'FINISHED' || open.deleted) continue;
    const remaining = open.openedQuantity - open.closedQuantity;
    if (remaining <= 0 || open.openedQuantity <= 0) continue;
    value += Math.trunc((open.spent * remaining) / open.openedQuantity);
  }
  return value;
}

export function closedNewestFirst(book) {
  const closed = [];
  for (const f of book.flips.values()) {
    if (f && f.status === 'FINISHED' && !f.deleted) closed.push({ ...f });
  }
  closed.sort((a, b) => (b.closedTime - a.closedTime) || String(a.id).localeCompare(String(b.id)));
  return closed;
}

export function openPositions(book) {
  const open = [];
  for (const f of book.openByItemId.values()) {
    if (f && f.status !== 'FINISHED' && !f.deleted) open.push({ ...f });
  }
  open.sort((a, b) => b.openedTime - a.openedTime);
  return open;
}

export function snapshot(book) {
  return {
    stats: statsOf(book),
    portfolioValue: portfolioValue(book),
    closedFlips: closedNewestFirst(book),
    openPositions: openPositions(book),
  };
}

function truncDiv(a, b) {
  if (!b) return 0;
  return Math.trunc(a / b);
}

export function enrichFlip(flip, { osrsAccountId, displayName, itemName } = {}) {
  if (!flip) return flip;
  const avgBuy = truncDiv(flip.spent, flip.openedQuantity);
  const avgSell = truncDiv((flip.receivedPostTax || 0) + (flip.taxPaid || 0), flip.closedQuantity);
  const profitPerUnit = truncDiv(flip.profit, flip.closedQuantity || 0);
  return {
    ...flip,
    osrsAccountId: osrsAccountId || flip.osrsAccountId || null,
    displayName: displayName || flip.displayName || null,
    itemName: itemName || flip.itemName || null,
    avgBuy,
    avgSell,
    profitPerUnit,
  };
}

/** Replay each OSRS account's transactions in isolation, then combine derived flips. */
export function replayAccounts(accounts) {
  const closedFlips = [];
  const open = [];
  let stats = { profit: 0, gross: 0, taxPaid: 0, flipsMade: 0 };
  let port = 0;
  for (const acct of accounts || []) {
    const book = replay(acct.transactions || []);
    const snap = snapshot(book);
    stats.profit += snap.stats.profit;
    stats.gross += snap.stats.gross;
    stats.taxPaid += snap.stats.taxPaid;
    stats.flipsMade += snap.stats.flipsMade;
    port += snap.portfolioValue;
    for (const f of snap.closedFlips) {
      closedFlips.push(enrichFlip(f, acct));
    }
    for (const f of snap.openPositions) {
      open.push(enrichFlip(f, acct));
    }
  }
  closedFlips.sort((a, b) => (b.closedTime - a.closedTime) || String(a.id).localeCompare(String(b.id)));
  open.sort((a, b) => b.openedTime - a.openedTime);
  return { stats, portfolioValue: port, closedFlips, openPositions: open };
}

export function rangeCutoffSec(range, nowSec = Math.floor(Date.now() / 1000)) {
  const r = String(range || 'all').toLowerCase();
  if (r === '30d') return nowSec - 30 * 24 * 3600;
  if (r === '90d') return nowSec - 90 * 24 * 3600;
  return 0;
}

export function filterClosedByRange(closedFlips, range) {
  const cutoff = rangeCutoffSec(range);
  if (cutoff <= 0) return closedFlips.slice();
  return closedFlips.filter((f) => (f.closedTime || 0) >= cutoff);
}

export function summaryFrom(derived, range = 'all') {
  const closed = filterClosedByRange(derived.closedFlips, range);
  let profit = 0;
  let taxPaid = 0;
  let gross = 0;
  let wins = 0;
  let bestProfit = 0;
  for (const f of closed) {
    profit += f.profit || 0;
    taxPaid += f.taxPaid || 0;
    gross += f.spent || 0;
    if ((f.profit || 0) > 0) wins += 1;
    if ((f.profit || 0) > bestProfit) bestProfit = f.profit;
  }
  const flipsMade = closed.length;
  const openCount = (derived.openPositions || []).length;
  const winRate = flipsMade ? wins / flipsMade : 0;
  const roi = gross ? profit / gross : 0;
  return {
    profit,
    roi,
    taxPaid,
    flipsMade,
    openCount,
    winRate,
    bestProfit,
    portfolioValue: derived.portfolioValue || 0,
    range: String(range || 'all').toLowerCase(),
  };
}

export function profitSeries(closedFlips, { range = 'all', granularity = 'cumulative' } = {}) {
  const closed = filterClosedByRange(closedFlips, range).slice().sort((a, b) => a.closedTime - b.closedTime);
  const gran = String(granularity || 'cumulative').toLowerCase();
  if (gran === 'daily') {
    const byDay = new Map();
    for (const f of closed) {
      const day = new Date((f.closedTime || 0) * 1000).toISOString().slice(0, 10);
      byDay.set(day, (byDay.get(day) || 0) + (f.profit || 0));
    }
    return [...byDay.entries()].map(([t, profit]) => ({ t, profit }));
  }
  let cum = 0;
  return closed.map((f) => {
    cum += f.profit || 0;
    return { t: f.closedTime, profit: cum };
  });
}
