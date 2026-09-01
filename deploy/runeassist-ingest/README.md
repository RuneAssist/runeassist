# Deployed on Ares-Server

The ingest server runs on Ares-Server as a Docker Compose app fronted by Caddy.

- Host dir: `~/selfhost/apps/runeassist-ingest/` (Dockerfile, docker-compose.yml, .env, ingest-server.mjs)
- Container: `runeassist-ingest` on the `proxy` network, internal port 8790
- Public endpoint: `https://runeassist.ares-server.co.uk/v1/ingest` (Caddy auto-TLS)
- Caddy route added to `~/selfhost/apps/caddy/Caddyfile` (backup saved alongside)
- Token: `~/selfhost/apps/runeassist-ingest/.env` (INGEST_TOKEN)

## Redeploy after editing server/ingest-server.mjs
```bash
scp server/ingest-server.mjs Ares-Server:~/selfhost/apps/runeassist-ingest/
ssh Ares-Server 'cd ~/selfhost/apps/runeassist-ingest && docker compose up -d --build'
```

## Ops
```bash
ssh Ares-Server 'docker logs --tail 50 runeassist-ingest'
ssh Ares-Server 'curl -s https://runeassist.ares-server.co.uk/health'
ssh Ares-Server 'cd ~/selfhost/apps/runeassist-ingest && docker compose restart'
```
