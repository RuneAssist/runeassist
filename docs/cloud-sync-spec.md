# RuneAssist account cloud sync — build spec (for Cursor)

Goal: sync each OSRS account's flip/transaction history to Ares so RuneAssist works across
devices, and so a future website frontend has something to read. Today everything is
**100% local** — nothing about a specific account leaves the machine.

## What exists now (read this first — reuse it, don't reinvent it)

- **`com.runeassist.flip.model.Transaction`** (`src/main/java/com/runeassist/flip/model/Transaction.java`)
  is already the atomic unit of "something happened on the GE": `id` (UUID), `type`
  (`OfferStatus.BUY`/`SELL`), `itemId`, `price`, `quantity`, `boxId`, `amountSpent`,
  `timestamp`. It already has a stable UUID — this is the natural sync payload; don't invent
  a new shape.
- **`GrandExchangeOfferEventHandler`** (`src/main/java/com/runeassist/flip/controller/GrandExchangeOfferEventHandler.java`)
  produces `Transaction`s from real GE offer fills and feeds them to `TransactionManager` →
  `LocalFlipLedger.applyToBook(...)` (`src/main/java/com/runeassist/flip/model/LocalFlipLedger.java:314-383`),
  which derives FIFO opens/closes (`FlipV2` records) purely from the transaction stream. This
  is the pattern to extend: sync **raw transactions**, not derived state (positions, FIFO
  lots, flip records) — every device already knows how to rebuild derived state from
  transactions, so syncing derived state directly risks exactly the kind of cross-account /
  stale-lot corruption bug just fixed in `HeldCostTracker` (see git history: "scope
  HeldCostTracker per account"). Replaying a transaction log is idempotent and mergeable;
  syncing FIFO state is not.
- **Per-account local storage today**: `LocalFlipLedger` → JSON files at
  `~/.runelite/runeassist-flip/<hash of displayName>_local_flips.json`
  (`Persistance.hashDisplayName(...)`, `src/main/java/com/runeassist/flip/controller/Persistance.java:120`).
  `HeldCostTracker` → RuneLite config, key `heldcost:<hash of displayName>`
  (`src/main/java/com/runeassist/flip/HeldCostTracker.java`). Both are keyed by OSRS display
  name today, both local-only. Cloud sync should be additive to this, not a replacement —
  local storage stays the fast path; cloud is the durable, cross-device copy.
- **Ares server** (`~/selfhost/apps/runeassist-ingest/` on the Ares VPS, fronted by Caddy at
  `runeassist.ares-server.co.uk`, `docker-compose.yml` on the `proxy` network) currently
  serves only stateless market data (`/v1/flips`, `/v1/graph`) — **no user/account concept, no
  database exists yet.** The VPS already runs several per-app Postgres containers as a
  convention (`stockyard-postgres-1`, `terawatt-postgres`, `codex-work-postgres-1`, etc.) —
  follow that pattern: give this feature its own `runeassist-postgres` container, don't share
  another app's database.

## Why an OSRS display name alone is not enough

RSN is not proof of ownership — anyone who knows (or guesses) a display name could otherwise
read or pollute that account's synced history once this is network-exposed. This needs real
device/account auth, independent of the game account.

## One login can own multiple OSRS accounts — this is not optional

**Validated against FlippingCopilot's own live dashboard** (`docs/cloud-sync-website-spec.md`
has the full reference): their `/flipping-history` page shows flips from *every linked OSRS
account interleaved in one table*, filterable down to one via an "All accounts" dropdown. This
matches reality here too — the same RuneLite install on this dev PC has already been used to
play both `Bof118` and `ColdTyres` in one session. A design where "account" (the sync/auth
identity) is 1:1 with a single OSRS display name doesn't fit that — it would force a separate
login per RSN, which is not how anyone actually plays.

So there are **two distinct levels**, not one:
- **`user`** — the login/auth identity. Owns device tokens (and later, an email). This is what
  a "device" or a website session authenticates as.
- **`osrs_account`** — one per distinct OSRS display name ever seen, belonging to exactly one
  `user`. Transaction history is scoped here, not at the `user` level, so per-account stats
  (and the plugin's existing per-account local files) stay meaningful.

A device commonly plays several `osrs_account`s over time (as here); the plugin should
auto-register a new `osrs_account` under the current `user` the first time it sees a display
name it hasn't synced before, with no separate manual step.

## Design

### 1. Auth: pairing-code device linking (no password, no email required for v1)

- First run on a device: plugin calls `POST /v1/account/register` (no display name needed yet)
  → server creates a new **user** (a server-generated UUID) and a **device token** (opaque
  random string) tied to that user. Token is stored in RuneLite config locally (mirrors how
  `credentials.properties` already holds sensitive local auth material for this dev setup —
  same trust model, same seriousness).
- Whenever the plugin observes a display name it hasn't synced for the authenticated user yet
  (this already happens locally — see `Persistance.hashDisplayName`/per-account file scoping),
  it calls `POST /v1/account/link-osrs {displayName}` (idempotent — finds-or-creates) and gets
  back an `osrsAccountId`, cached locally alongside the existing local per-account storage.
  Every `Transaction` synced afterward is tagged with that `osrsAccountId`.
- Linking a second **device** to the same **user**: server issues a short-lived, short (6-8
  char) **pairing code** on request (`POST /v1/account/pair/start` from an already-linked
  device), a second device redeems it (`POST /v1/account/pair/redeem` with the code) to get
  its own device token for the same user. Same UX pattern as pairing a TV streaming app — no
  password to type on a cramped RuneLite panel. (This is how the same user ends up able to see
  all their OSRS accounts' history from any of their devices, or the website.)
- Every subsequent sync call authenticates with `Authorization: Bearer <device token>` over
  HTTPS (Caddy already terminates TLS for `runeassist.ares-server.co.uk`).
- Later (website login) layers email/password or OAuth onto the same **user** UUID without
  touching the plugin's pairing flow — don't build that now, just don't design yourself out of
  it (i.e., keep "user", "osrs_account", and "device token" as three separate concepts from
  day one, per the website spec's auth section which already assumes this).

### 2. Sync payload and API (append-only transaction log)

- `POST /v1/account/transactions` — body `{ osrsAccountId, transactions: [ {id, type, itemId,
  price, quantity, boxId, amountSpent, timestamp}, ... ] }` (straight serialization of
  `Transaction`, batched, scoped to one `osrs_account` per call). Server upserts by `id` (the
  UUID already on every `Transaction`) — idempotent, safe to retry, safe to resend the same
  batch. Server must verify `osrsAccountId` belongs to the authenticated device's user before
  accepting.
- `GET /v1/account/transactions?osrsAccountId=&since=<ISO timestamp or cursor>` — returns
  transactions for that OSRS account newer than the cursor, for a device to catch up on what
  happened elsewhere. Omit `osrsAccountId` to fetch across every OSRS account the user owns
  (paired with a cursor per account, or just union — pick whichever's simpler when
  implementing) — needed for the website's "All accounts" view.
- Server computes nothing derived server-side in v1 (no FIFO, no flip stats) — it is a dumb,
  authenticated append-only log. Each client (plugin or, later, the website) replays it
  through the *same* FIFO logic `LocalFlipLedger` already has, so there is exactly one
  implementation of "what counts as a flip," not two that can drift.
- Sync trigger on the plugin side: piggyback on the existing transaction flow — whenever
  `GrandExchangeOfferEventHandler` produces a new `Transaction` locally, queue it (tagged with
  the current session's `osrsAccountId`); flush the queue on a short interval (e.g. every
  30-60s) or on logout, batched. On startup/login, pull anything newer than the local
  high-water mark *for that OSRS account* and feed it into `LocalFlipLedger` the same way a
  locally-observed transaction would be fed in — this is what makes "played this account on
  another PC yesterday" show up today.

### 3. Server-side storage

- New Postgres container (`runeassist-postgres`, `docker-compose.yml` addition on the
  `proxy` network, same pattern as the VPS's other per-app Postgres containers). Tables:
  - `users(id uuid pk, created_at)`
  - `osrs_accounts(id uuid pk, user_id fk, display_name, created_at)` — one row per distinct
    RSN; `display_name` can change (a real OSRS mechanic) — update in place, don't re-key
  - `devices(id uuid pk, user_id fk, token_hash, created_at, last_seen_at)` — store a hash
    of the token, not the raw token, same as any API-key design
  - `transactions(id uuid pk, osrs_account_id fk, type, item_id, price, quantity, box_id,
    amount_spent, ts, received_at)` — `id` is the client-generated UUID, enforced unique per
    `osrs_account_id` for idempotent upsert
- `ingest-server.mjs` (Node) is the natural place to add these routes — it already owns the
  Caddy-fronted domain and the Docker/compose wiring; don't stand up a second service unless
  there's a good reason to.

### 4. Website frontend (later phase, not this pass)

Once the API above exists, a website is just another authenticated client of it — log in
(whatever auth layer gets added on top of the `user` UUID), `GET /v1/account/transactions`,
replay the same FIFO logic (port `LocalFlipLedger`'s open/close algorithm to the website's
stack, or expose it as a small shared service) to show flip history/stats read-only. Don't
build this until the sync API is live and the plugin is actually using it — building the
frontend first means guessing at a contract that hasn't been exercised by a real client yet.

## Phased delivery

1. **Server**: Postgres schema (`users`/`osrs_accounts`/`devices`/`transactions`) +
   `/v1/account/register`, `/v1/account/link-osrs`, `/v1/account/pair/start`,
   `/v1/account/pair/redeem`, `/v1/account/transactions` (POST + GET) on
   `runeassist-ingest`. Auth middleware validates the bearer token against `devices`, resolves
   to a `user_id`, and checks any `osrsAccountId` in a request belongs to that user.
2. **Plugin**: on first run, silently register + store a device token (no user-facing
   friction) alongside the existing local storage; call `link-osrs` the first time each OSRS
   account is seen and cache the returned `osrsAccountId`; add a "Link another device" action
   in preferences that shows a pairing code; wire the sync queue described above into
   `GrandExchangeOfferEventHandler`/`TransactionManager`; on login, pull and replay anything
   new for that account.
3. **Website**: read-only dashboard against the same API.

## Guardrails

- Never sync derived state (FIFO positions, computed profit) — only the raw `Transaction`
  stream. This is the single most important rule here: it's what keeps multi-device sync
  simple and avoids reintroducing a cross-account/stale-state bug class in a *harder to
  detect* place (silently wrong numbers on a website, not just a phantom local suggestion).
- Store token hashes server-side, never raw tokens.
- `osrs_account.id` (not display name) is the durable key for transaction history; `user.id`
  is the durable key for auth/devices. The OSRS display name is metadata that can change (a
  real OSRS mechanic) and must never be used as a lookup key for either.
- Keep the plugin fully usable with zero cloud connectivity — sync failures should log and
  retry, never block a suggestion or a GE action. This mirrors how `FlipScorer.topFlips`
  already falls back to a local wiki scorer when Ares is unreachable — sync should degrade
  the same way (local-only until the network's back), not add a hard dependency.
