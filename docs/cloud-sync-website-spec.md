# RuneAssist website — build spec (for Cursor)

Goal: a read-only website showing flip history/stats for a RuneAssist account, built on top
of the sync API from **`docs/cloud-sync-spec.md`** (read that first — this doc assumes it's
already built: Postgres `accounts`/`devices`/`transactions` tables, `/v1/account/*` routes on
`runeassist-ingest`, the plugin already syncing). This doc only covers what's new for the
website: real user login, and the read-only dashboard.

## Scope for v1 of the website

- **Login only** (no signup forms with passwords to manage yet — see auth below).
- **Read-only**: profit/ROI overview, flip history list, open positions. No actions — you
  can't place, cancel, or edit anything from the website. Matches the plugin's own
  "advise, don't automate" stance, and is much simpler/safer for a first web pass.
- No real-time updates — refresh-on-load is enough for v1. Don't build websockets yet.

## Reference: flippingcopilot.com's existing dashboard (live-checked 2026-09-02)

FC already ships exactly this product — free tier even advertises "cross-device profit
tracking" on their `/subscriptions` page. Worth building toward a proven shape rather than
guessing. Two pages checked directly (logged into a real FC account):

- **`/flipping-history`** — the closed-flip/stats view. Layout: a top stat-card row (Total
  Profit, Flips — with an "N open" callout alongside the total, Win Rate — with a "best: X gp"
  callout, Tax Paid), an account-filter dropdown defaulting to **"All accounts"** (this is not
  a nice-to-have — FC shows flips from every linked OSRS account interleaved in one table,
  filterable down to one), a date-range selector (30d/90d/All), a profit-over-time chart
  (Cumulative/Daily/By-item toggle), and the flip table itself: Closed (time), Account, Item
  (with icon), Status pill (buying/selling/finished — color-coded), Qty, Avg Buy, Avg Sell,
  Tax, Profit (colored), and Ea. (profit per unit) — plus a free-text filter box over
  item/account. This maps directly onto `GET /v1/account/flips` below; add the missing pieces
  (win rate, tax paid, per-account filtering, profit-per-unit) to that endpoint's response
  rather than inventing a thinner shape.
- **`/item-price-graph`** (item search → per-item view) — a stat row (Instabuy, Instasell,
  Margin+ROI, Daily Volume, Buy Limit) above a dual-line price chart (instabuy/instasell
  overlaid) with a shaded **IQR forecast band extending past "now"** on the right edge of the
  chart (labeled "Forecast"), a paired volume sub-chart directly underneath sharing the same
  time axis (instabuy/instasell volume as mirrored bars above/below zero), and timeframe tabs
  (24h/3d/7d/30d/6m/1y/Max). This is item-market data, not account data — it's the website
  equivalent of what `docs/forecast-model-spec.md`'s `v2-quantile-lgbm` forecast already
  produces server-side; if the website gets an item-graph page, this is the shape to match
  (particularly: show the forecast as a continuation of the same chart, not a separate widget).

## 1. Auth: magic-link email, layered onto the existing `user` UUID

The sync spec has two levels: a **`user`** (the login/auth identity, owns device tokens) and
one or more **`osrs_account`**s under that user (one per OSRS display name, where transaction
history actually lives — see the sync spec's "One login can own multiple OSRS accounts"
section, added after checking FC's own dashboard: it shows every linked OSRS account
interleaved in one view, which only works if login identity and game-account identity are
separate concepts). `user` and `device token` were kept separate from day one specifically so
a real login could be layered on without touching the plugin's pairing flow. This is that
layer — it authenticates a **`user`**, who may see stats/history for *any* `osrs_account` they
own, same as the plugin already can from any of its linked devices.

- Passwordless (magic link) for v1 — no password hashing/reset-flow surface to build and get
  wrong on a first pass. `POST /v1/auth/request-link {email}` → server generates a short-lived
  signed token, emails a link containing it (needs an outbound mail sender — e.g. a
  transactional email API; pick one when implementing, don't self-host SMTP). Visiting the
  link (`GET /v1/auth/verify?token=...`) sets a session cookie (signed, httpOnly, `Secure`,
  short-lived JWT or an opaque session id backed by a `sessions` table — either is fine, keep
  it simple).
- **Linking email to an existing plugin-tracked user**: the `user` row already exists once the
  plugin has registered (per the sync spec). Add `email` as an optional column on `users`
  (nullable — a user can exist plugin-only, with no web login, indefinitely). Two ways to
  attach an email to a user, both needed:
  - **From the plugin**: a "Link a website login" action in preferences — same pairing-code
    UX already specified for device linking (`/v1/account/pair/start` on an already-linked
    device), except redeeming the code on the website (`POST /v1/auth/pair/redeem {code,
    email}`) attaches that email to the user instead of creating a new device token.
  - **From the website** (someone who signs in with an email that has no linked user yet):
    show a pairing code to enter into the plugin's preferences panel, same flow in reverse.
  Either direction ends at the same place: one `users` row with both an email and one or more
  device tokens, and (via those devices, over time) one or more `osrs_account`s.
- A logged-in website session is authorized for exactly one `user`, and therefore every
  `osrs_account` that user owns — no cross-user access, obviously, but cross-*OSRS-account*
  access within one user is the whole point (that's the "All accounts" view).

## 2. Deriving flip history/stats server-side (the real design decision here)

The sync spec's core guardrail: sync raw `Transaction`s, never derived state, so there's one
canonical implementation of "what counts as a flip" (`LocalFlipLedger`'s FIFO open/close
logic, `src/main/java/com/runeassist/flip/model/LocalFlipLedger.java:314-383`, and
`Stats.addFlip`/`subtractFlip`, `src/main/java/com/runeassist/flip/model/Stats.java`, for the
aggregates). A website can't run that Java code directly, so it needs a second
implementation — **this is an accepted, already-established pattern in this codebase**, not a
new risk: `server/flip-scorer.mjs` is already a maintained JS port of `FlipScorer.java`,
explicitly "kept in sync with the Java" per its own comments. Do the same thing here:

- Port the FIFO open/close algorithm (and the `Stats` aggregation) to JS, living in
  `runeassist-ingest` alongside `flip-scorer.mjs` (e.g. `flip-ledger.mjs`), computing
  derived flips/stats from the `transactions` table on read (either on-the-fly per API call,
  or materialized into a cache table if it turns out to be slow — don't pre-optimize this,
  measure first).
- **Guardrail the drift risk directly**: add a small shared test-vector file (a handful of
  representative transaction sequences — partial fills, cancels, a full round-trip flip —
  with their expected resulting flips/profit) that both `LocalFlipLedger`'s Java tests and
  the new JS port must reproduce identically. This is the concrete thing that keeps "one
  canonical algorithm, two implementations" honest instead of becoming "two algorithms that
  quietly disagree." Do not skip this — it's cheap to add now and expensive to debug later
  (a website showing different profit numbers than the plugin is the kind of bug users notice
  immediately and stop trusting the whole product over).

## 3. API additions (on top of `/v1/account/*` from the sync spec)

All require the website session (cookie) and resolve to the `user` it's tied to — separate
from the plugin's bearer-token device auth, same underlying `user`/`osrs_account` model.

- `GET /v1/account/summary?osrsAccountId=&range=` — total profit, flip count (+ open count), win
  rate (+ best single-flip profit), tax paid, portfolio value. `osrsAccountId` optional (omit
  for "all OSRS accounts this user owns, combined", matching FC's default view — see reference
  above); `range` one of `30d`/`90d`/`all`.
- `GET /v1/account/flips?osrsAccountId=&page=&pageSize=&q=` — paginated closed-flip history
  (account, item, qty, avg buy, avg sell, tax, profit, profit-per-unit, opened/closed
  timestamps), newest first, `q` a free-text item/account filter. Mirrors what
  `FlipManager.getPageFlips(...)` already returns to the plugin's own "Recent Flips" panel —
  same shape, so the website's list and the plugin's list read the same way.
- `GET /v1/account/profit-series?osrsAccountId=&range=&granularity=cumulative|daily` — the
  profit-over-time chart data.
- `GET /v1/account/positions?osrsAccountId=` — currently-open (unmatched) buy lots, i.e. what's
  still held.

## 4. Stack

- **Frontend**: a small React/Next.js app (static-exportable is fine for v1 — no
  server-rendering requirement since everything's behind login anyway) is the path of least
  friction for an AI-assisted build and for adding auth-gated pages later. Don't reach for
  anything heavier for a read-only dashboard.
- **Backend**: extend `ingest-server.mjs` (Node) rather than standing up a second service —
  it already owns the Caddy-fronted domain, the Docker/compose wiring, and (once the sync
  spec is built) the Postgres connection. Add the `/v1/auth/*` and `/v1/account/summary`
  `/flips` `/positions` routes there.
- **Hosting**: same Ares VPS, same `proxy` Docker network, same Caddy — add a route/subdomain
  for the frontend build (e.g. `app.runeassist.ares-server.co.uk` or a path on the existing
  domain) rather than a new host.

## Guardrails

- Read-only in v1 — resist the temptation to add "cancel offer" or similar remote-control
  features from the website; that's a much bigger trust/safety surface (arbitrary remote
  actions on a live game account) and isn't needed for the stated goal (viewing history
  across devices).
- Never let the website compute or display anything the transaction-replay logic doesn't
  produce — no client-side "estimate" numbers that could diverge from what the plugin shows
  for the same account.
- Magic-link tokens: short expiry (e.g. 15 minutes), single-use, invalidate on use.
- Same "degrade gracefully" principle as the plugin's Ares fallback — if the derived-stats
  computation fails for some malformed/edge-case transaction sequence, the API should return
  a clear error for that endpoint, not silently show wrong numbers.
