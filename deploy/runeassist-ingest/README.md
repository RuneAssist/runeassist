# Deployed on Ares-Server

The ingest server runs on Ares-Server as a Docker Compose app fronted by Caddy.

- Host dir: `~/selfhost/apps/runeassist-ingest/` (Dockerfile, docker-compose.yml, .env, ingest-server.mjs, flip-scorer.mjs, flip-ledger.mjs, account-sync.mjs, website-dist/)
- Container: `runeassist-ingest` on the `proxy` network, internal port 8790
- Postgres: `runeassist-postgres` (bind `./postgres-data`; do not share another app's DB)
- Public endpoint: `https://runeassist.ares-server.co.uk/v1/ingest` (Caddy auto-TLS)
- Public flips: `https://runeassist.ares-server.co.uk/v1/flips` (wiki + client constraints; no telemetry)
- Website: `https://runeassist.com/app/` (also `https://runeassist.co.uk/app/` and `https://runeassist.ares-server.co.uk/app/`)

## Brand DNS (Cloudflare)

Caddy already serves `runeassist.com` / `runeassist.co.uk`. In each Cloudflare zone add
**proxied** A records (orange cloud) to origin `57.129.128.165`:

| Name | Type | Content | Proxy |
|------|------|---------|-------|
| `@` | A | `57.129.128.165` | Proxied |
| `www` | A | `57.129.128.165` | Proxied |

SSL/TLS mode should match the other Ares sites (typically **Full**). `www` redirects to the apex. Magic-link `APP_ORIGIN` is `https://runeassist.com`.
- Caddy route added to `~/selfhost/apps/caddy/Caddyfile` (backup saved alongside)
- Token: `~/selfhost/apps/runeassist-ingest/.env` (INGEST_TOKEN, POSTGRES_PASSWORD, Cloudflare Email Sending)
- Allowed ingest types: `ge_offer`, `ge_history`, `account_snapshot`, `xp_gain`
  (`ge_history` = completed GE-history-UI backfill, hashed RSN, `source=ge_history`)

## Redeploy after editing server/*.mjs or the website
```bash
# do not scp .env, ./data, wiki-archive, or postgres-data
npm --prefix website ci
npm --prefix website run build
npm --prefix server install --omit=dev
scp server/ingest-server.mjs server/flip-scorer.mjs server/flip-ledger.mjs server/account-sync.mjs \
    server/package.json server/package-lock.json \
    deploy/runeassist-ingest/Dockerfile deploy/runeassist-ingest/docker-compose.yml \
    Ares-Server:~/selfhost/apps/runeassist-ingest/
scp -r website/dist Ares-Server:~/selfhost/apps/runeassist-ingest/website-dist
# append POSTGRES_PASSWORD to .env if missing (never overwrite .env)
ssh Ares-Server 'cd ~/selfhost/apps/runeassist-ingest && docker compose up -d --build'
```

Env additions (append only):
```
POSTGRES_PASSWORD=<long random>
# Cloudflare Email Sending (magic-link login). Onboard runeassist.com under
# Compute → Email Service → Email Sending first (adds cf-bounce SPF/DKIM/DMARC).
# Token needs Email Sending: Edit. Paid Workers plan required for Email Sending.
CLOUDFLARE_ACCOUNT_ID=
CF_EMAIL_API_TOKEN=
MAIL_FROM=RuneAssist <noreply@runeassist.com>
# optional fallback:
# RESEND_API_KEY=re_...
APP_ORIGIN=https://runeassist.com
```

## Ops
```bash
ssh Ares-Server 'docker logs --tail 50 runeassist-ingest'
ssh Ares-Server 'curl -s https://runeassist.ares-server.co.uk/health'
ssh Ares-Server 'cd ~/selfhost/apps/runeassist-ingest && docker compose restart'
```

## Wiki price/volume archive (forecast fuel)

Polite bulk snapshots of the OSRS Wiki Real-time Prices API. This is **volume/forecast
training fuel**, not a fill model and not `ge_offer` telemetry. It does **not** live in
the ingest container and does not touch `/v1/flips` or `/v1/graph`.

- Collector: `tools/wiki-price-archive.mjs` (copied to `~/selfhost/apps/runeassist-ingest/wiki-price-archive.mjs`)
- Output: `~/selfhost/apps/runeassist-ingest/wiki-archive/wiki-{5m,latest,1h,volumes}-YYYY-MM-DD.jsonl`
- Symlink for forecast training: `~/deepseek-workspace/runeassist-forecast/data/wiki-archive`
- Timer: systemd **user** unit `wiki-price-archive.timer` (linger already on; same pattern as `forecast-quantile.service`)
- Cadence: `/5m` + `/latest` every 5 min; `/1h` + `/volumes` once per UTC hour. 2–4 bulk calls per tick. **Do not** hammer `/5m?timestamp=` — optional one-shot `--backfill` at ≥2s/request, default off.

```bash
# install / refresh the collector + timer (does not rebuild ingest)
scp tools/wiki-price-archive.mjs \
    deploy/runeassist-ingest/wiki-price-archive.service \
    deploy/runeassist-ingest/wiki-price-archive.timer \
    Ares-Server:~/selfhost/apps/runeassist-ingest/
ssh Ares-Server 'mkdir -p ~/selfhost/apps/runeassist-ingest/wiki-archive \
  ~/.config/systemd/user \
  && cp ~/selfhost/apps/runeassist-ingest/wiki-price-archive.service \
        ~/selfhost/apps/runeassist-ingest/wiki-price-archive.timer \
        ~/.config/systemd/user/ \
  && ln -sfn ~/selfhost/apps/runeassist-ingest/wiki-archive \
             ~/deepseek-workspace/runeassist-forecast/data/wiki-archive \
  && systemctl --user daemon-reload \
  && systemctl --user enable --now wiki-price-archive.timer'

# health: timer armed, latest jsonl growing
ssh Ares-Server 'systemctl --user list-timers wiki-price-archive.timer --no-pager'
ssh Ares-Server 'ls -l ~/selfhost/apps/runeassist-ingest/wiki-archive/ | tail'
ssh Ares-Server 'journalctl --user -u wiki-price-archive.service -n 20 --no-pager'

# backfill (optional, default off; ≥2s/request)
# ssh Ares-Server 'node ~/selfhost/apps/runeassist-ingest/wiki-price-archive.mjs \
#   --dir=$HOME/selfhost/apps/runeassist-ingest/wiki-archive --backfill --step=5m --from=UNIX --to=UNIX'
```
