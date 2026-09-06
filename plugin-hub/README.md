# Plugin Hub submission draft

This directory is **not** a live [runelite/plugin-hub](https://github.com/runelite/plugin-hub) checkout. It is a paste-ready manifest plus steps so a maintainer with a Hub fork can open the real PR.

RuneAssist is a BSD-2 adaptation of Flipping Copilot (see `LICENSE` / `THIRD_PARTY_LICENSES.md`). Hub reviewers can see git history and a similar GE overlay; the manifest only publishes this plugin.

## Manifest to paste

File name in plugin-hub: `plugins/runeassist-flipping`

Copy `plugin-hub/plugins/runeassist-flipping` from this repo. Before opening the Hub PR:

1. Merge this plugin to `https://github.com/RuneAssist/runeassist` `main` (or another public commit you want Hub to build).
2. Ensure `commit=` is the full 40-character SHA of the plugin tree Hub should build (`git rev-parse origin/main` after feature merges). The draft currently pins `2a9dc27c907b051540c5ac06ba2ef5d94ad2501f` (`origin/main` tip after Scorer v2 compose prefs; Hub diet + `ui/graph` kept). Re-bump after further `src/` changes.
3. Keep `repository=` as the public HTTPS URL ending in `.git`.
4. Keep `warning=` exactly as written. `/v1/suggestion` sends coin stack (`capital`), held stock with cost basis, risk/timeframe settings and buy-limit usage with no gate (local telemetry was removed). That is why the warning lists coin stack and held stock alongside GE data and IP.
5. `build=standard` lives in this repo’s `runelite-plugin.properties`, not in the Hub file. Do not add a custom `build.gradle` dependency unless you switch to `build=gradle` and go through Hub dependency verification.

**Hub review size (approx):** RuneLite's bot budget is **200k tokens including their prompt**. This repo's estimate is `utf8_bytes(src/main/java/**/*.java) / 5` ≈ **182k** at `2a9dc27` (908005 bytes / 5). `cl100k_base` on the same Java is ≈ **188k** (code-only).

Expected Hub file:

```
repository=https://github.com/RuneAssist/runeassist.git
commit=2a9dc27c907b051540c5ac06ba2ef5d94ad2501f
warning=This plugin submits your coin stack size, held Grand Exchange stock with cost basis, grand exchange offers, grand exchange transactions, and IP address to a 3rd party server not controlled or verified by the RuneLite Developers.
authors=RuneAssist
```

## Exact Hub PR steps

Follow https://github.com/runelite/plugin-hub#submitting-a-plugin :

1. Fork https://github.com/runelite/plugin-hub (GitHub UI; this environment cannot open that PR — no `RuneAssist/plugin-hub` fork and Hub 403s agent tokens).
2. Clone your fork. Add upstream if needed:
   ```
   git remote add upstream https://github.com/runelite/plugin-hub.git
   git fetch upstream
   git checkout -B runeassist-flipping upstream/master
   ```
3. Create `plugins/runeassist-flipping` with the four lines above (`repository`, `commit`, `warning`, `authors`).
4. Commit and push:
   ```
   git add plugins/runeassist-flipping
   git commit -m "Add runeassist-flipping"
   git push -u origin runeassist-flipping
   ```
5. Open a pull request against `runelite/plugin-hub` `master` (**Compare across forks**). Description sketch:
   - RuneAssist Flipping is a Grand Exchange assistant: server compose (`/v1/suggestion`), held-cost tracking, Ares market data (`/v1/flips`, graph, quotes).
   - Adapted from Flipping Copilot under BSD-2 (`LICENSE` / `THIRD_PARTY_LICENSES.md` in the plugin repo).
   - Hub `warning=` covers coin stack, held stock, GE data + IP to a third party (local telemetry removed in Hub diet).
   - `build=standard` in `runelite-plugin.properties`.
6. Watch Hub CI (`.github/workflows/build.yml` and RuneLite Plugin Hub Checks). Fix plugin-repo issues, push a new plugin commit, then update `commit=` on the Hub PR. Keep a single Hub PR.

## After Hub merge (updates)

```
git fetch upstream
git checkout -B runeassist-flipping upstream/master
# set commit= to the new plugin SHA
git add plugins/runeassist-flipping
git commit -m "update runeassist-flipping"
git push -f -u origin runeassist-flipping
```

Then open (or update) the Hub PR from that branch.

## What Hub already reads from this repo

At the `commit=` SHA, Hub clones this repository and uses `runelite-plugin.properties`:

- `displayName=RuneAssist Flipping`
- `plugins=com.runeassist.flip.controller.RuneAssistPlugin`
- `warning=` (same GE + IP third-party text)
- `build=standard`
- optional root `icon.png` (32×32, under the 48×72 Hub limit)
