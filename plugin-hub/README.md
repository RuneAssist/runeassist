# Plugin Hub submission draft

This directory is **not** a live [runelite/plugin-hub](https://github.com/runelite/plugin-hub) checkout. It is a paste-ready manifest plus steps so a maintainer with a Hub fork can open or update the real PR.

RuneAssist is a BSD-2 adaptation of Flipping Copilot. Hub reviewers can see git history and a similar GE overlay. The manifest does not hide that; it only publishes this plugin.

**Open Hub PR:** https://github.com/runelite/plugin-hub/pull/16024  
**Fork branch:** `RuneAssist/plugin-hub` → `runeassist-flipping`  
**Plugin id:** `runeassist-flipping`

## Manifest to paste

File name in plugin-hub: `plugins/runeassist-flipping`

Copy `plugin-hub/plugins/runeassist-flipping` from this repo. Before pushing the Hub PR:

1. Merge this plugin to `https://github.com/RuneAssist/runeassist` `main` (or another public commit you want Hub to build).
2. Replace `commit=` with the full 40-character SHA of that commit (`git rev-parse origin/main`).
3. Keep `repository=` as the public HTTPS URL ending in `.git`.
4. Keep `warning=` exactly as written (same tone as Hub plugin `flipping-copilot`). Telemetry is opt-in in the client, but `/v1/suggestion` is **not** — it sends coin stack (`capital`), held stock with cost basis, risk/timeframe settings and buy-limit usage with no gate. That is why the warning lists coin stack and held stock alongside GE data and IP.
5. `build=standard` lives in this repo’s `runelite-plugin.properties`, not in the Hub file. Do not add a custom `build.gradle` dependency unless you switch to `build=gradle` and go through Hub dependency verification.

Expected Hub file (current `main`):

```
repository=https://github.com/RuneAssist/runeassist.git
commit=01f09cedda91746e09ec141d177914c916d43af3
warning=This plugin submits your coin stack size, held Grand Exchange stock with cost basis, grand exchange offers, grand exchange transactions, and IP address to a 3rd party server not controlled or verified by the RuneLite Developers.
authors=RuneAssist
```

## Update the open Hub PR (#16024) — do this now

PR #16024 still points at pre-token-cut commit `60d8ba0…` (~255k tokens; Hub bot: `too many tokens: 255545`). After #21–#28, `main` is ~**193k** Hub tokens (under the 200k auto-review threshold). Agent tokens cannot push to `RuneAssist/plugin-hub` (403 for `cursor[bot]`); a human with fork write access must push:

```bash
git clone https://github.com/RuneAssist/plugin-hub.git
cd plugin-hub
git remote add upstream https://github.com/runelite/plugin-hub.git
git fetch upstream
git checkout -B runeassist-flipping upstream/master
# paste the four-line file from plugin-hub/plugins/runeassist-flipping in RuneAssist/runeassist
git add plugins/runeassist-flipping
git commit -m "update runeassist-flipping"
git push -f -u origin runeassist-flipping
```

Then on https://github.com/runelite/plugin-hub/pull/16024 edit the description to note:

- `commit=` is `01f09cedda91746e09ec141d177914c916d43af3` (`RuneAssist/runeassist` `main` after #28).
- Hub Java tokens previously **255545**; after dead-code cuts + server compose / drop local engine: **~193k** (under 200k) so the review bot can auto-review.
- `warning=` now also lists held Grand Exchange stock with cost basis (matches `/v1/suggestion`).
- Adapted from Flipping Copilot under BSD-2 (`LICENSE` / `THIRD_PARTY_LICENSES.md`).
- `build=standard` in `runelite-plugin.properties`.

Compare URL (after force-push):  
https://github.com/runelite/plugin-hub/compare/master...RuneAssist:plugin-hub:runeassist-flipping

## First-time Hub PR steps (if #16024 is closed)

Follow https://github.com/runelite/plugin-hub#submitting-a-plugin :

1. Fork https://github.com/runelite/plugin-hub (GitHub UI).
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
5. Open a pull request against `runelite/plugin-hub` `master` (**Compare across forks**).
6. Watch Hub CI (`.github/workflows/build.yml` and RuneLite Plugin Hub Checks). Fix plugin-repo issues, push a new plugin commit, then update `commit=` on the Hub PR. Keep a single Hub PR.

## What Hub already reads from this repo

At the `commit=` SHA, Hub clones this repository and uses `runelite-plugin.properties`:

- `displayName=RuneAssist Flipping`
- `plugins=com.runeassist.flip.controller.RuneAssistPlugin`
- `build=standard`
- optional root `icon.png` (32×32, under the 48×72 Hub limit)
