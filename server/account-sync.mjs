// User/osrs_account cloud sync + website auth for runeassist-ingest.
// Schema matches docs/cloud-sync-spec.md (users / osrs_accounts / devices / transactions).
// Website routes match docs/cloud-sync-website-spec.md. Existing ingest JSONL is untouched.

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import pg from 'pg';
import { ensureMarketCache, itemName } from './flip-scorer.mjs';
import { replay, snapshot, enrichFlip, summaryFrom, profitSeries } from './flip-ledger.mjs';
import { computeMlOverview } from './ml-overview.mjs';

const { Pool } = pg;
const __dirname = path.dirname(fileURLToPath(import.meta.url));

const DATABASE_URL = process.env.DATABASE_URL || '';
const APP_ORIGIN = (process.env.APP_ORIGIN || 'https://runeassist.com').replace(/\/$/, '');
const MAIL_FROM = process.env.MAIL_FROM || 'RuneAssist <noreply@runeassist.com>';
const RESEND_API_KEY = process.env.RESEND_API_KEY || '';
const CF_ACCOUNT_ID = process.env.CLOUDFLARE_ACCOUNT_ID || '';
const CF_EMAIL_TOKEN = process.env.CF_EMAIL_API_TOKEN || process.env.CLOUDFLARE_API_TOKEN || '';
const COOKIE_SECURE = process.env.COOKIE_SECURE !== '0';
const PAIR_TTL_MS = 10 * 60 * 1000;
const LINK_TTL_MS = 15 * 60 * 1000;
const SESSION_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const MAX_TX_BATCH = 500;
const PAIR_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const WEBSITE_DIR = process.env.WEBSITE_DIR || path.join(__dirname, 'website-dist');
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const SCREENSHOT_DIR = path.join(DATA_DIR, 'bug-screenshots');
fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });
// Single-operator deploy -- no roles table, just a hardcoded admin email for the
// all-reports view. Revisit if this ever needs to support more than one admin.
const ADMIN_EMAIL = 'tom@tpharrison.co.uk';
const MAX_SCREENSHOT_BYTES = 3_500_000; // raw PNG, pre-base64

let pool = null;

// ── rate limiting ──────────────────────────────────────────────────────────
// In-memory, fixed-window. Fine for a single Node process (this server isn't
// clustered); revisit if that changes. Protects the two endpoints that are either
// fully unauthenticated (register) or trigger a real outbound email (request-link).
const rateBuckets = new Map(); // key -> { count, resetAt }
function rateLimited(key, max, windowMs) {
  const now = Date.now();
  const b = rateBuckets.get(key);
  if (!b || now > b.resetAt) {
    rateBuckets.set(key, { count: 1, resetAt: now + windowMs });
    return false;
  }
  b.count += 1;
  return b.count > max;
}
function clientIp(req) {
  const xff = req.headers['x-forwarded-for'];
  if (xff) return String(xff).split(',')[0].trim();
  return req.socket && req.socket.remoteAddress || 'unknown';
}
setInterval(() => {
  const now = Date.now();
  for (const [k, b] of rateBuckets) if (now > b.resetAt) rateBuckets.delete(k);
}, 10 * 60 * 1000).unref();

// ── derived (replayed) flip data cache ──────────────────────────────────────
// Replaying + re-querying every transaction on every dashboard load doesn't scale
// as an account's history grows (a real FC import was 2,546 rows; this account's
// own history will only grow). Cache the per-osrs_account replay result, keyed by
// osrs_account_id, invalidated whenever new transactions are ingested for that
// account (see /v1/account/transactions below) plus a TTL safety net in case an
// invalidation is ever missed.
const derivedCache = new Map(); // osrsAccountId -> { snap, updatedAt }
const DERIVED_CACHE_TTL_MS = 5 * 60 * 1000;
function invalidateDerived(osrsAccountId) {
  derivedCache.delete(osrsAccountId);
}

export function dbEnabled() {
  return Boolean(DATABASE_URL);
}

export async function initDb() {
  if (!DATABASE_URL) {
    console.warn('account-sync: DATABASE_URL unset — /v1/account and /v1/auth return 503');
    return;
  }
  pool = new Pool({ connectionString: DATABASE_URL, max: 8 });
  await pool.query(`
    CREATE TABLE IF NOT EXISTS users (
      id UUID PRIMARY KEY,
      created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
      email TEXT UNIQUE
    );
    CREATE TABLE IF NOT EXISTS osrs_accounts (
      id UUID PRIMARY KEY,
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      display_name TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
      UNIQUE (user_id, display_name)
    );
    CREATE TABLE IF NOT EXISTS devices (
      id UUID PRIMARY KEY,
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      token_hash TEXT NOT NULL UNIQUE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
      last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );
    CREATE TABLE IF NOT EXISTS transactions (
      id UUID PRIMARY KEY,
      osrs_account_id UUID NOT NULL REFERENCES osrs_accounts(id) ON DELETE CASCADE,
      type TEXT NOT NULL,
      item_id INT NOT NULL,
      price BIGINT NOT NULL,
      quantity INT NOT NULL,
      box_id INT NOT NULL DEFAULT 0,
      amount_spent BIGINT NOT NULL,
      ts TIMESTAMPTZ NOT NULL,
      received_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );
    CREATE INDEX IF NOT EXISTS transactions_account_ts ON transactions (osrs_account_id, ts);
    CREATE TABLE IF NOT EXISTS pairing_codes (
      code TEXT PRIMARY KEY,
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      expires_at TIMESTAMPTZ NOT NULL,
      used_at TIMESTAMPTZ
    );
    CREATE TABLE IF NOT EXISTS sessions (
      id UUID PRIMARY KEY,
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      token_hash TEXT NOT NULL UNIQUE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
      expires_at TIMESTAMPTZ NOT NULL
    );
    CREATE TABLE IF NOT EXISTS auth_links (
      token_hash TEXT PRIMARY KEY,
      email TEXT NOT NULL,
      expires_at TIMESTAMPTZ NOT NULL,
      used_at TIMESTAMPTZ
    );
    CREATE TABLE IF NOT EXISTS bug_reports (
      id UUID PRIMARY KEY,
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      display_name TEXT,
      message TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );
    CREATE INDEX IF NOT EXISTS bug_reports_user ON bug_reports (user_id, created_at DESC);
    ALTER TABLE bug_reports ADD COLUMN IF NOT EXISTS has_screenshot BOOLEAN NOT NULL DEFAULT false;
  `);
  console.log('account-sync: postgres schema ready');
}

function send(res, code, obj, extraHeaders) {
  const body = JSON.stringify(obj);
  const headers = {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(body),
    ...(extraHeaders || {}),
  };
  res.writeHead(code, headers);
  res.end(body);
}

function readBody(req, maxBytes) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > maxBytes) {
        reject(Object.assign(new Error('payload too large'), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      if (!chunks.length) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        reject(Object.assign(new Error('invalid json'), { status: 400 }));
      }
    });
    req.on('error', reject);
  });
}

function sha256(s) {
  return crypto.createHash('sha256').update(String(s)).digest('hex');
}

function randomToken(bytes = 32) {
  return crypto.randomBytes(bytes).toString('base64url');
}

function randomCode(len = 8) {
  let out = '';
  const buf = crypto.randomBytes(len);
  for (let i = 0; i < len; i++) out += PAIR_ALPHABET[buf[i] % PAIR_ALPHABET.length];
  return out;
}

function bearer(req) {
  const h = req.headers.authorization || '';
  if (!h.startsWith('Bearer ')) return null;
  return h.slice(7).trim() || null;
}

function cookieValue(req, name) {
  const raw = req.headers.cookie || '';
  for (const part of raw.split(';')) {
    const [k, ...rest] = part.trim().split('=');
    if (k === name) return decodeURIComponent(rest.join('='));
  }
  return null;
}

function setCookie(name, value, maxAgeSec) {
  const parts = [
    `${name}=${encodeURIComponent(value)}`,
    'Path=/',
    'HttpOnly',
    'SameSite=Lax',
    `Max-Age=${maxAgeSec}`,
  ];
  if (COOKIE_SECURE) parts.push('Secure');
  return parts.join('; ');
}

function clearCookie(name) {
  return `${name}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0`;
}

async function authDevice(req) {
  const token = bearer(req);
  if (!token || !pool) return null;
  const hash = sha256(token);
  const { rows } = await pool.query(
    `UPDATE devices SET last_seen_at = now()
     WHERE token_hash = $1
     RETURNING id, user_id`,
    [hash],
  );
  return rows[0] || null;
}

async function authSession(req) {
  const token = cookieValue(req, 'ra_session');
  if (!token || !pool) return null;
  const { rows } = await pool.query(
    `SELECT id, user_id FROM sessions
     WHERE token_hash = $1 AND expires_at > now()`,
    [sha256(token)],
  );
  return rows[0] || null;
}

async function authAny(req) {
  return (await authDevice(req)) || (await authSession(req));
}

async function requireOsrsOwned(userId, osrsAccountId) {
  const { rows } = await pool.query(
    `SELECT id, display_name FROM osrs_accounts WHERE id = $1 AND user_id = $2`,
    [osrsAccountId, userId],
  );
  return rows[0] || null;
}

function parseTx(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const id = String(raw.id || '').trim();
  if (!/^[0-9a-fA-F-]{36}$/.test(id)) return null;
  const type = String(raw.type || '').toUpperCase();
  if (type !== 'BUY' && type !== 'SELL') return null;
  const itemId = Number(raw.itemId);
  const price = Number(raw.price);
  const quantity = Number(raw.quantity);
  const boxId = Number(raw.boxId || 0);
  const amountSpent = Number(raw.amountSpent);
  if (![itemId, price, quantity, amountSpent].every(Number.isFinite)) return null;
  if (quantity <= 0) return null;
  let ts = raw.timestamp || raw.ts;
  const d = ts == null ? new Date() : new Date(ts);
  if (Number.isNaN(d.getTime())) return null;
  return {
    id,
    type,
    item_id: Math.trunc(itemId),
    price: Math.trunc(price),
    quantity: Math.trunc(quantity),
    box_id: Math.trunc(boxId),
    amount_spent: Math.trunc(amountSpent),
    ts: d.toISOString(),
  };
}

function rowToTx(r) {
  return {
    id: r.id,
    type: r.type,
    itemId: r.item_id,
    price: Number(r.price),
    quantity: r.quantity,
    boxId: r.box_id,
    amountSpent: Number(r.amount_spent),
    timestamp: r.ts instanceof Date ? r.ts.toISOString() : r.ts,
    osrsAccountId: r.osrs_account_id,
  };
}

async function issueDevice(userId) {
  const token = randomToken();
  const id = crypto.randomUUID();
  await pool.query(
    `INSERT INTO devices (id, user_id, token_hash) VALUES ($1, $2, $3)`,
    [id, userId, sha256(token)],
  );
  return { deviceId: id, deviceToken: token };
}

async function issueSession(userId) {
  const token = randomToken();
  const id = crypto.randomUUID();
  await pool.query(
    `INSERT INTO sessions (id, user_id, token_hash, expires_at)
     VALUES ($1, $2, $3, now() + interval '7 days')`,
    [id, userId, sha256(token)],
  );
  return token;
}

async function issuePairCode(userId) {
  for (let i = 0; i < 8; i++) {
    const code = randomCode(8);
    try {
      await pool.query(
        `INSERT INTO pairing_codes (code, user_id, expires_at)
         VALUES ($1, $2, now() + interval '10 minutes')`,
        [code, userId],
      );
      return code;
    } catch (e) {
      if (e && e.code === '23505') continue;
      throw e;
    }
  }
  throw new Error('could not allocate pairing code');
}

async function sendMagicEmail(email, url) {
  const text = `Sign in to RuneAssist:\n\n${url}\n\nThis link expires in 15 minutes.`;
  const html = `<p>Sign in to RuneAssist:</p><p><a href="${url}">${url}</a></p><p>This link expires in 15 minutes.</p>`;
  if (CF_EMAIL_TOKEN && CF_ACCOUNT_ID) {
    const r = await fetch(
      `https://api.cloudflare.com/client/v4/accounts/${CF_ACCOUNT_ID}/email/sending/send`,
      {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${CF_EMAIL_TOKEN}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          from: MAIL_FROM,
          to: email,
          subject: 'Sign in to RuneAssist',
          text,
          html,
        }),
      },
    );
    if (!r.ok) {
      const txt = await r.text();
      throw new Error(`cloudflare mail failed ${r.status}: ${txt}`);
    }
    return { emailed: true };
  }
  if (RESEND_API_KEY) {
    const r = await fetch('https://api.resend.com/emails', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${RESEND_API_KEY}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        from: MAIL_FROM,
        to: [email],
        subject: 'Sign in to RuneAssist',
        text,
      }),
    });
    if (!r.ok) {
      const txt = await r.text();
      throw new Error(`mail failed ${r.status}: ${txt}`);
    }
    return { emailed: true };
  }
  console.log(`account-sync: magic link for ${email}: ${url}`);
  return { emailed: false, devLink: url };
}

async function replayOneAccount(osrsAccountId) {
  const cached = derivedCache.get(osrsAccountId);
  if (cached && Date.now() - cached.updatedAt < DERIVED_CACHE_TTL_MS) {
    return cached.snap;
  }
  const { rows: txs } = await pool.query(
    `SELECT id, type, item_id, price, quantity, box_id, amount_spent, ts, osrs_account_id
     FROM transactions WHERE osrs_account_id = $1 ORDER BY ts ASC, received_at ASC`,
    [osrsAccountId],
  );
  const book = replay(txs.map(rowToTx));
  const snap = snapshot(book);
  derivedCache.set(osrsAccountId, { snap, updatedAt: Date.now() });
  return snap;
}

async function loadDerived(userId, osrsAccountId) {
  const accountsSql = osrsAccountId
    ? `SELECT id, display_name FROM osrs_accounts WHERE user_id = $1 AND id = $2 ORDER BY display_name`
    : `SELECT id, display_name FROM osrs_accounts WHERE user_id = $1 ORDER BY display_name`;
  const params = osrsAccountId ? [userId, osrsAccountId] : [userId];
  const { rows: accts } = await pool.query(accountsSql, params);

  const closedFlips = [];
  const openPositions = [];
  const stats = { profit: 0, gross: 0, taxPaid: 0, flipsMade: 0 };
  let portfolioValueTotal = 0;
  for (const a of accts) {
    const snap = await replayOneAccount(a.id);
    const ctx = { osrsAccountId: a.id, displayName: a.display_name };
    stats.profit += snap.stats.profit;
    stats.gross += snap.stats.gross;
    stats.taxPaid += snap.stats.taxPaid;
    stats.flipsMade += snap.stats.flipsMade;
    portfolioValueTotal += snap.portfolioValue;
    for (const f of snap.closedFlips) closedFlips.push(enrichFlip(f, ctx));
    for (const f of snap.openPositions) openPositions.push(enrichFlip(f, ctx));
  }
  closedFlips.sort((a, b) => (b.closedTime - a.closedTime) || String(a.id).localeCompare(String(b.id)));
  openPositions.sort((a, b) => b.openedTime - a.openedTime);

  await ensureMarketCache().catch(() => {});
  for (const f of closedFlips) f.itemName = itemName(f.itemId);
  for (const f of openPositions) f.itemName = itemName(f.itemId);
  const derived = { stats, portfolioValue: portfolioValueTotal, closedFlips, openPositions };
  return { accounts: accts.map((a) => ({ id: a.id, displayName: a.display_name })), derived };
}

/** Admin-only: same replay as loadDerived, but across every osrs_account for every user,
 * each closed flip tagged with the owning user's email for context. */
async function loadDerivedAllAccounts() {
  const { rows: accts } = await pool.query(
    `SELECT o.id, o.display_name, u.email
     FROM osrs_accounts o JOIN users u ON u.id = o.user_id
     ORDER BY o.display_name`,
  );
  const closedFlips = [];
  const stats = { profit: 0, gross: 0, taxPaid: 0, flipsMade: 0 };
  for (const a of accts) {
    const snap = await replayOneAccount(a.id);
    const ctx = { osrsAccountId: a.id, displayName: a.display_name };
    stats.profit += snap.stats.profit;
    stats.gross += snap.stats.gross;
    stats.taxPaid += snap.stats.taxPaid;
    stats.flipsMade += snap.stats.flipsMade;
    for (const f of snap.closedFlips) closedFlips.push({ ...enrichFlip(f, ctx), ownerEmail: a.email });
  }
  closedFlips.sort((a, b) => (b.closedTime - a.closedTime) || String(a.id).localeCompare(String(b.id)));
  await ensureMarketCache().catch(() => {});
  for (const f of closedFlips) f.itemName = itemName(f.itemId);
  return {
    accounts: accts.map((a) => ({ id: a.id, displayName: a.display_name, email: a.email })),
    stats,
    closedFlips,
  };
}

function serveWebsite(req, res, pathname) {
  if (!fs.existsSync(WEBSITE_DIR)) return false;
  let rel = pathname === '/app' || pathname === '/app/' ? 'index.html' : pathname.replace(/^\/app\/?/, '');
  rel = path.normalize(rel).replace(/^(\.\.(\/|\\|$))+/, '');
  let file = path.join(WEBSITE_DIR, rel);
  if (!file.startsWith(WEBSITE_DIR)) return false;
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    file = path.join(WEBSITE_DIR, 'index.html');
  }
  if (!fs.existsSync(file)) return false;
  const ext = path.extname(file).toLowerCase();
  const types = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.svg': 'image/svg+xml',
    '.json': 'application/json',
    '.ico': 'image/x-icon',
    '.png': 'image/png',
  };
  const buf = fs.readFileSync(file);
  res.writeHead(200, { 'Content-Type': types[ext] || 'application/octet-stream', 'Content-Length': buf.length });
  res.end(buf);
  return true;
}

export async function tryHandle(req, res) {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;

  if (p === '/app' || p.startsWith('/app/')) {
    if (serveWebsite(req, res, p)) return true;
    send(res, 404, { error: 'website not deployed' });
    return true;
  }

  const accountOrAuth = p.startsWith('/v1/account') || p.startsWith('/v1/auth');
  if (!accountOrAuth) return false;

  if (!pool) {
    send(res, 503, { error: 'cloud sync unavailable' });
    return true;
  }

  try {
    await handle(req, res, url);
  } catch (e) {
    const status = e.status || 500;
    if (status >= 500) console.error('account-sync:', e);
    if (!res.headersSent) send(res, status, { error: e.status ? e.message : 'internal' });
  }
  return true;
}

async function handle(req, res, url) {
  const p = url.pathname;
  const method = req.method;

  if (method === 'POST' && p === '/v1/account/register') {
    // Fully unauthenticated by design (a device has no credential yet) -- rate-limit by
    // IP so it can't be used to spam-create blank user rows.
    if (rateLimited(`register:${clientIp(req)}`, 5, 60 * 60 * 1000)) {
      return send(res, 429, { error: 'too many registration attempts, try again later' });
    }
    const userId = crypto.randomUUID();
    await pool.query(`INSERT INTO users (id) VALUES ($1)`, [userId]);
    const { deviceToken } = await issueDevice(userId);
    return send(res, 200, { userId, deviceToken });
  }

  if (method === 'POST' && p === '/v1/account/link-osrs') {
    const device = await authDevice(req);
    if (!device) return send(res, 401, { error: 'unauthorized' });
    const body = await readBody(req, 16_000);
    const displayName = String(body.displayName || '').trim();
    if (!displayName || displayName.length > 32) return send(res, 400, { error: 'bad displayName' });
    const existing = await pool.query(
      `SELECT id, display_name FROM osrs_accounts WHERE user_id = $1 AND display_name = $2`,
      [device.user_id, displayName],
    );
    if (existing.rows[0]) {
      return send(res, 200, { osrsAccountId: existing.rows[0].id, displayName: existing.rows[0].display_name });
    }
    const id = crypto.randomUUID();
    await pool.query(
      `INSERT INTO osrs_accounts (id, user_id, display_name) VALUES ($1, $2, $3)`,
      [id, device.user_id, displayName],
    );
    return send(res, 200, { osrsAccountId: id, displayName });
  }

  if (method === 'POST' && p === '/v1/account/pair/start') {
    const who = await authAny(req);
    if (!who) return send(res, 401, { error: 'unauthorized' });
    const code = await issuePairCode(who.user_id);
    return send(res, 200, { code, expiresInSec: Math.floor(PAIR_TTL_MS / 1000) });
  }

  if (method === 'POST' && p === '/v1/account/pair/redeem') {
    const body = await readBody(req, 16_000);
    const code = String(body.code || '').trim().toUpperCase();
    if (!code) return send(res, 400, { error: 'missing code' });
    const { rows } = await pool.query(
      `SELECT user_id FROM pairing_codes
       WHERE code = $1 AND used_at IS NULL AND expires_at > now()`,
      [code],
    );
    if (!rows[0]) return send(res, 400, { error: 'invalid or expired code' });
    const targetUser = rows[0].user_id;
    await pool.query(`UPDATE pairing_codes SET used_at = now() WHERE code = $1`, [code]);

    const existing = await authDevice(req);
    if (existing && existing.user_id !== targetUser) {
      await pool.query(
        `UPDATE osrs_accounts o SET user_id = $1
         WHERE o.user_id = $2
           AND NOT EXISTS (
             SELECT 1 FROM osrs_accounts t
             WHERE t.user_id = $1 AND t.display_name = o.display_name
           )`,
        [targetUser, existing.user_id],
      );
      await pool.query(`UPDATE devices SET user_id = $1 WHERE user_id = $2`, [targetUser, existing.user_id]);
    }
    const { deviceToken } = await issueDevice(targetUser);
    return send(res, 200, { userId: targetUser, deviceToken });
  }

  if (method === 'POST' && p === '/v1/account/transactions') {
    const device = await authDevice(req);
    if (!device) return send(res, 401, { error: 'unauthorized' });
    const body = await readBody(req, 2_000_000);
    const osrsAccountId = String(body.osrsAccountId || '').trim();
    if (!osrsAccountId) return send(res, 400, { error: 'missing osrsAccountId' });
    const owned = await requireOsrsOwned(device.user_id, osrsAccountId);
    if (!owned) return send(res, 403, { error: 'osrsAccountId not owned by this user' });
    const incoming = Array.isArray(body.transactions) ? body.transactions : [];
    if (incoming.length > MAX_TX_BATCH) return send(res, 413, { error: `batch over ${MAX_TX_BATCH}` });
    let upserted = 0;
    for (const raw of incoming) {
      const tx = parseTx(raw);
      if (!tx) continue;
      await pool.query(
        `INSERT INTO transactions
           (id, osrs_account_id, type, item_id, price, quantity, box_id, amount_spent, ts)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)
         ON CONFLICT (id) DO UPDATE SET
           type = EXCLUDED.type,
           item_id = EXCLUDED.item_id,
           price = EXCLUDED.price,
           quantity = EXCLUDED.quantity,
           box_id = EXCLUDED.box_id,
           amount_spent = EXCLUDED.amount_spent,
           ts = EXCLUDED.ts
         WHERE transactions.osrs_account_id = EXCLUDED.osrs_account_id`,
        [tx.id, osrsAccountId, tx.type, tx.item_id, tx.price, tx.quantity, tx.box_id, tx.amount_spent, tx.ts],
      );
      upserted++;
    }
    if (upserted > 0) invalidateDerived(osrsAccountId);
    return send(res, 200, { upserted });
  }

  if (method === 'GET' && p === '/v1/account/transactions') {
    const who = await authAny(req);
    if (!who) return send(res, 401, { error: 'unauthorized' });
    const osrsAccountId = url.searchParams.get('osrsAccountId');
    const since = url.searchParams.get('since');
    if (osrsAccountId) {
      const owned = await requireOsrsOwned(who.user_id, osrsAccountId);
      if (!owned) return send(res, 403, { error: 'osrsAccountId not owned by this user' });
    }
    const params = [who.user_id];
    let sql = `
      SELECT t.id, t.type, t.item_id, t.price, t.quantity, t.box_id, t.amount_spent, t.ts, t.osrs_account_id
      FROM transactions t
      JOIN osrs_accounts a ON a.id = t.osrs_account_id
      WHERE a.user_id = $1`;
    if (osrsAccountId) {
      params.push(osrsAccountId);
      sql += ` AND t.osrs_account_id = $${params.length}`;
    }
    if (since) {
      const d = new Date(since);
      if (Number.isNaN(d.getTime())) return send(res, 400, { error: 'bad since' });
      params.push(d.toISOString());
      sql += ` AND t.ts >= $${params.length}`;
    }
    sql += ' ORDER BY t.ts ASC, t.received_at ASC LIMIT 5000';
    const { rows } = await pool.query(sql, params);
    const transactions = rows.map(rowToTx);
    const cursor = transactions.length ? transactions[transactions.length - 1].timestamp : since;
    return send(res, 200, { transactions, cursor: cursor || null });
  }

  if (method === 'POST' && p === '/v1/auth/request-link') {
    const body = await readBody(req, 16_000);
    const email = String(body.email || '').trim().toLowerCase();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return send(res, 400, { error: 'bad email' });
    // This sends a real email -- rate-limit per address (can't be used to spam one inbox)
    // and per IP (can't be used to spam many addresses from one source).
    if (rateLimited(`link-email:${email}`, 3, 15 * 60 * 1000)
        || rateLimited(`link-ip:${clientIp(req)}`, 10, 60 * 60 * 1000)) {
      return send(res, 429, { error: 'too many sign-in attempts, try again shortly' });
    }
    const token = randomToken();
    await pool.query(
      `INSERT INTO auth_links (token_hash, email, expires_at)
       VALUES ($1, $2, now() + interval '15 minutes')`,
      [sha256(token), email],
    );
    const verifyUrl = `${APP_ORIGIN}/v1/auth/verify?token=${encodeURIComponent(token)}`;
    const mail = await sendMagicEmail(email, verifyUrl);
    const out = { ok: true, emailed: mail.emailed };
    if (!mail.emailed) out.devLink = mail.devLink;
    return send(res, 200, out);
  }

  if (method === 'GET' && p === '/v1/auth/verify') {
    const token = url.searchParams.get('token') || '';
    if (!token) return send(res, 400, { error: 'missing token' });
    const { rows } = await pool.query(
      `SELECT email FROM auth_links
       WHERE token_hash = $1 AND used_at IS NULL AND expires_at > now()`,
      [sha256(token)],
    );
    if (!rows[0]) {
      res.writeHead(302, { Location: `${APP_ORIGIN}/app/#/login?error=expired` });
      res.end();
      return;
    }
    const email = rows[0].email;
    await pool.query(`UPDATE auth_links SET used_at = now() WHERE token_hash = $1`, [sha256(token)]);
    let userId;
    const found = await pool.query(`SELECT id FROM users WHERE email = $1`, [email]);
    if (found.rows[0]) {
      userId = found.rows[0].id;
    } else {
      userId = crypto.randomUUID();
      await pool.query(`INSERT INTO users (id, email) VALUES ($1, $2)`, [userId, email]);
    }
    const session = await issueSession(userId);
    res.writeHead(302, {
      Location: `${APP_ORIGIN}/app/#/dashboard`,
      'Set-Cookie': setCookie('ra_session', session, Math.floor(SESSION_TTL_MS / 1000)),
    });
    res.end();
    return;
  }

  if (method === 'POST' && p === '/v1/auth/pair/redeem') {
    const body = await readBody(req, 16_000);
    const code = String(body.code || '').trim().toUpperCase();
    const email = String(body.email || '').trim().toLowerCase();
    if (!code || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      return send(res, 400, { error: 'code and email required' });
    }
    const { rows } = await pool.query(
      `SELECT user_id FROM pairing_codes
       WHERE code = $1 AND used_at IS NULL AND expires_at > now()`,
      [code],
    );
    if (!rows[0]) return send(res, 400, { error: 'invalid or expired code' });
    const userId = rows[0].user_id;
    const taken = await pool.query(`SELECT id FROM users WHERE email = $1 AND id <> $2`, [email, userId]);
    if (taken.rows[0]) return send(res, 409, { error: 'email already linked to another user' });
    await pool.query(`UPDATE pairing_codes SET used_at = now() WHERE code = $1`, [code]);
    await pool.query(`UPDATE users SET email = $1 WHERE id = $2`, [email, userId]);
    const session = await issueSession(userId);
    return send(res, 200, { ok: true, userId }, {
      'Set-Cookie': setCookie('ra_session', session, Math.floor(SESSION_TTL_MS / 1000)),
    });
  }

  if (method === 'POST' && p === '/v1/auth/logout') {
    const token = cookieValue(req, 'ra_session');
    if (token) await pool.query(`DELETE FROM sessions WHERE token_hash = $1`, [sha256(token)]);
    return send(res, 200, { ok: true }, { 'Set-Cookie': clearCookie('ra_session') });
  }

  if (method === 'GET' && p === '/v1/account/me') {
    const who = await authAny(req);
    if (!who) return send(res, 401, { error: 'unauthorized' });
    const u = await pool.query(`SELECT id, email, created_at FROM users WHERE id = $1`, [who.user_id]);
    const accts = await pool.query(
      `SELECT id, display_name FROM osrs_accounts WHERE user_id = $1 ORDER BY display_name`,
      [who.user_id],
    );
    return send(res, 200, {
      userId: who.user_id,
      email: u.rows[0] ? u.rows[0].email : null,
      osrsAccounts: accts.rows.map((a) => ({ id: a.id, displayName: a.display_name })),
    });
  }

  if (method === 'GET' && (p === '/v1/account/summary' || p === '/v1/account/flips'
      || p === '/v1/account/positions' || p === '/v1/account/profit-series')) {
    const who = await authSession(req);
    if (!who) return send(res, 401, { error: 'unauthorized' });
    const osrsAccountId = url.searchParams.get('osrsAccountId') || null;
    const range = url.searchParams.get('range') || 'all';
    if (osrsAccountId) {
      const owned = await requireOsrsOwned(who.user_id, osrsAccountId);
      if (!owned) return send(res, 403, { error: 'osrsAccountId not owned by this user' });
    }
    let derivedPack;
    try {
      derivedPack = await loadDerived(who.user_id, osrsAccountId);
    } catch (e) {
      console.error('derive failed:', e);
      return send(res, 500, { error: 'could not derive flip stats from transactions' });
    }
    const { accounts, derived } = derivedPack;

    if (p === '/v1/account/summary') {
      const summary = summaryFrom(derived, range);
      return send(res, 200, { ...summary, osrsAccounts: accounts });
    }
    if (p === '/v1/account/positions') {
      return send(res, 200, { positions: derived.openPositions, osrsAccounts: accounts });
    }
    if (p === '/v1/account/profit-series') {
      const granularity = url.searchParams.get('granularity') || 'cumulative';
      return send(res, 200, {
        points: profitSeries(derived.closedFlips, { range, granularity }),
        range,
        granularity,
      });
    }
    const page = Math.max(1, Number(url.searchParams.get('page') || 1) || 1);
    const pageSize = Math.min(100, Math.max(1, Number(url.searchParams.get('pageSize') || 50) || 50));
    const q = String(url.searchParams.get('q') || '').trim().toLowerCase();
    let flips = filterFlips(derived.closedFlips, range, q);
    const total = flips.length;
    const start = (page - 1) * pageSize;
    flips = flips.slice(start, start + pageSize).map(publicFlip);
    return send(res, 200, { flips, page, pageSize, total, osrsAccounts: accounts });
  }

  if (method === 'POST' && p === '/v1/account/feedback') {
    const who = await authAny(req);
    if (!who) return send(res, 401, { error: 'unauthorized' });
    if (rateLimited(`feedback:${who.user_id}`, 10, 60 * 60 * 1000)) {
      return send(res, 429, { error: 'too many reports, try again later' });
    }
    // Body cap sized for an optional base64 screenshot (~4/3 inflation over MAX_SCREENSHOT_BYTES)
    // plus JSON overhead, not just the message text.
    const body = await readBody(req, 5_000_000);
    const message = String(body.message || '').trim();
    if (!message) return send(res, 400, { error: 'missing message' });
    if (message.length > 4000) return send(res, 400, { error: 'message too long' });
    const displayName = String(body.displayName || '').trim().slice(0, 32) || null;

    let hasScreenshot = false;
    let screenshotBuf = null;
    if (body.screenshot) {
      try {
        screenshotBuf = Buffer.from(String(body.screenshot), 'base64');
      } catch {
        return send(res, 400, { error: 'invalid screenshot encoding' });
      }
      if (screenshotBuf.length === 0) {
        return send(res, 400, { error: 'invalid screenshot encoding' });
      }
      if (screenshotBuf.length > MAX_SCREENSHOT_BYTES) {
        return send(res, 400, { error: `screenshot too large (max ${MAX_SCREENSHOT_BYTES} bytes)` });
      }
      hasScreenshot = true;
    }

    const id = crypto.randomUUID();
    await pool.query(
      `INSERT INTO bug_reports (id, user_id, display_name, message, has_screenshot) VALUES ($1, $2, $3, $4, $5)`,
      [id, who.user_id, displayName, message, hasScreenshot],
    );
    if (screenshotBuf) {
      try {
        fs.writeFileSync(path.join(SCREENSHOT_DIR, `${id}.png`), screenshotBuf);
      } catch (e) {
        console.error('bug report screenshot write failed:', e.message);
        // Report itself is already saved -- don't fail the whole submission over this.
      }
    }
    return send(res, 200, { id });
  }

  if (method === 'GET' && p === '/v1/account/feedback') {
    const who = await authAny(req);
    if (!who) return send(res, 401, { error: 'unauthorized' });
    const { rows } = await pool.query(
      `SELECT id, display_name, message, created_at, has_screenshot FROM bug_reports
       WHERE user_id = $1 ORDER BY created_at DESC LIMIT 100`,
      [who.user_id],
    );
    return send(res, 200, { reports: rows.map(publicBugReport) });
  }

  const screenshotMatch = p.match(/^\/v1\/account\/feedback\/([0-9a-f-]{36})\/screenshot$/i);
  if (method === 'GET' && screenshotMatch) {
    const who = await authAny(req);
    if (!who) return send(res, 401, { error: 'unauthorized' });
    const reportId = screenshotMatch[1];
    const { rows } = await pool.query(
      `SELECT user_id, has_screenshot FROM bug_reports WHERE id = $1`,
      [reportId],
    );
    const report = rows[0];
    if (!report || !report.has_screenshot) return send(res, 404, { error: 'not found' });
    const isOwner = report.user_id === who.user_id;
    const isAdmin = isOwner ? false : await isAdminUser(who.user_id);
    if (!isOwner && !isAdmin) return send(res, 403, { error: 'forbidden' });
    const file = path.join(SCREENSHOT_DIR, `${reportId}.png`);
    if (!fs.existsSync(file)) return send(res, 404, { error: 'not found' });
    const buf = fs.readFileSync(file);
    res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': buf.length });
    return res.end(buf);
  }

  if (method === 'GET' && p === '/v1/account/admin/feedback') {
    const who = await authSession(req); // website-only, session cookie required
    if (!who) return send(res, 401, { error: 'unauthorized' });
    if (!(await isAdminUser(who.user_id))) return send(res, 403, { error: 'forbidden' });
    const { rows } = await pool.query(
      `SELECT br.id, br.display_name, br.message, br.created_at, br.has_screenshot, u.email
       FROM bug_reports br JOIN users u ON u.id = br.user_id
       ORDER BY br.created_at DESC LIMIT 500`,
    );
    return send(res, 200, {
      reports: rows.map((r) => ({ ...publicBugReport(r), reporterEmail: r.email })),
    });
  }

  if (method === 'GET' && p === '/v1/account/admin/trades') {
    const who = await authSession(req);
    if (!who) return send(res, 401, { error: 'unauthorized' });
    if (!(await isAdminUser(who.user_id))) return send(res, 403, { error: 'forbidden' });
    const page = Math.max(1, Number(url.searchParams.get('page') || 1) || 1);
    const pageSize = Math.min(200, Math.max(1, Number(url.searchParams.get('pageSize') || 100) || 100));
    let all;
    try {
      all = await loadDerivedAllAccounts();
    } catch (e) {
      console.error('admin trades derive failed:', e);
      return send(res, 500, { error: 'could not derive trades' });
    }
    const total = all.closedFlips.length;
    const start = (page - 1) * pageSize;
    const flips = all.closedFlips.slice(start, start + pageSize).map((f) => ({ ...publicFlip(f), ownerEmail: f.ownerEmail }));
    return send(res, 200, { stats: all.stats, accounts: all.accounts, flips, page, pageSize, total });
  }

  if (method === 'GET' && p === '/v1/account/admin/ml-overview') {
    const who = await authSession(req);
    if (!who) return send(res, 401, { error: 'unauthorized' });
    if (!(await isAdminUser(who.user_id))) return send(res, 403, { error: 'forbidden' });
    let overview;
    try {
      overview = computeMlOverview(DATA_DIR);
    } catch (e) {
      console.error('ml overview failed:', e);
      return send(res, 500, { error: 'could not compute ML overview' });
    }
    if (!overview) return send(res, 200, { overview: null, note: 'no telemetry uploaded yet' });
    await ensureMarketCache().catch(() => {});
    for (const r of overview.perItem) r.itemName = itemName(r.itemId);
    return send(res, 200, { overview });
  }

  send(res, 404, { error: 'not found' });
}

function publicBugReport(r) {
  return {
    id: r.id,
    displayName: r.display_name,
    message: r.message,
    createdAt: Math.floor(new Date(r.created_at).getTime() / 1000),
    hasScreenshot: r.has_screenshot,
  };
}

async function isAdminUser(userId) {
  const { rows } = await pool.query(`SELECT email FROM users WHERE id = $1`, [userId]);
  return rows[0] && rows[0].email === ADMIN_EMAIL;
}

function filterFlips(closed, range, q) {
  const cutoff = range === '30d' || range === '90d'
    ? Math.floor(Date.now() / 1000) - (range === '30d' ? 30 : 90) * 24 * 3600
    : 0;
  let out = closed;
  if (cutoff > 0) out = out.filter((f) => (f.closedTime || 0) >= cutoff);
  if (q) {
    out = out.filter((f) => {
      const item = String(f.itemName || '').toLowerCase();
      const acct = String(f.displayName || '').toLowerCase();
      const id = String(f.itemId || '');
      return item.includes(q) || acct.includes(q) || id.includes(q);
    });
  }
  return out;
}

function publicFlip(f) {
  return {
    id: f.id,
    osrsAccountId: f.osrsAccountId,
    displayName: f.displayName,
    itemId: f.itemId,
    itemName: f.itemName,
    status: f.status,
    qty: f.closedQuantity || f.openedQuantity,
    openedQuantity: f.openedQuantity,
    closedQuantity: f.closedQuantity,
    avgBuy: f.avgBuy,
    avgSell: f.avgSell,
    taxPaid: f.taxPaid,
    profit: f.profit,
    profitPerUnit: f.profitPerUnit,
    openedTime: f.openedTime,
    closedTime: f.closedTime,
  };
}