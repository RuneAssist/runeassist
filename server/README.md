# RuneAssist ingest server

The thin **data-flywheel** backend: it collects opt-in, anonymised telemetry that the
plugin uploads and appends it to per-day JSONL files. It serves **no predictions** — it is
pure collection, the prerequisite for training the flip model later.

It is deliberately minimal: **zero npm dependencies** (bare `node:http`), JSONL storage in
the **same schema** the plugin writes locally, so `tools/flip-v2-train.mjs` can train on the
collected data directly.

## What it stores (and doesn't)

- **Stores:** `ge_offer`, `account_snapshot`, `xp_gain` records — each with a **hashed** RSN
  (`acct`), never a real username.
- **Never stores:** the `advice` record. The plugin does not upload it, so players' raw chat
  questions never reach the server.

## Run locally

```bash
INGEST_TOKEN=pick-a-long-secret node server/ingest-server.mjs
```

Then point the plugin at it: RuneAssist config → Privacy →
- **Contribute anonymous data** = on
- **Contribution endpoint** = `http://127.0.0.1:8790/v1/ingest`
- **Contribution token** = the same `INGEST_TOKEN`

Verify:

```bash
curl -s localhost:8790/health
curl -s -H "Authorization: Bearer $INGEST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '[{"v":1,"type":"ge_offer","ts":1,"acct":"deadbeef","item_id":561,"price":150,"state":"BOUGHT","total_quantity":100,"quantity_sold":100,"spent":15000}]' \
  localhost:8790/v1/ingest          # -> {"stored":1}
curl -s -H "Authorization: Bearer $INGEST_TOKEN" localhost:8790/v1/stats
```

## Config (env)

| Var | Default | Meaning |
|---|---|---|
| `PORT` | `8790` | Listen port |
| `INGEST_TOKEN` | *(required)* | Shared Bearer secret; server refuses to start without it |
| `DATA_DIR` | `./server/data` | Where JSONL files are written |
| `MAX_BODY_BYTES` | `2000000` | Max request body |
| `MAX_BATCH` | `500` | Max records per request |

## Endpoints

- `GET /health` → `{ok:true}`
- `POST /v1/ingest` → Bearer auth; body = JSON array of records; appends → `{stored:N}`
- `GET /v1/stats` → Bearer auth; today's per-type record counts

## Deploying

Run behind a TLS reverse proxy (Caddy/nginx) so the plugin can use `https://…/v1/ingest`.
`data/` is git-ignored. This is a single-node, append-only starting point; migrate to a
real datastore (Postgres/S3) before it grows. **Do not** enable this as a default —
uploading is off unless the user sets the endpoint themselves.

## Roadmap (later phases, not built yet)

1. **This server** — collection only. ← you are here
2. Per-user tokens + cross-device flip-history sync (the paid-tier hook).
3. Train + serve the forecasting model once enough data accrues.
