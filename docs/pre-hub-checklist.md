# Before a Plugin Hub submission

A Hub review takes days and a maintainer's time. Nothing goes to the Hub until
every line below is ticked. Small fixes wait on `main` and ship together with
the next update that is worth a review.

## Automatic, on every pull request

| Check | What it catches |
|---|---|
| `rules` | Hub reviewer rules and packaging (docs/hub-rules.md) |
| `build` | Compiles and tests against the Hub's RuneLite version on Java 11 |
| `startup` | Plugin errors on a brand new install (`scripts/startup-smoke.sh`) |
| `size` | Pull requests too large to review |
| `ui-snapshots` artifact | Images of panel pieces at the real panel width |

Any pull request that adds or changes a panel, card or header adds it to
`UiSnapshotTest` and shows the rendered image in the pull request.

## By hand, before the Hub pull request is opened

Run the real client with the plugin from `main`:

```
./gradlew runClient
```

Then, on a fresh RuneLite profile and on your normal one:

- [ ] Panel opens; nothing is cut off, overlapping or pushed sideways.
- [ ] Welcome card: all three buttons visible; "Got it" hides it and it stays hidden after a restart.
- [ ] Settings: gear opens and closes it; every heading and control is readable.
- [ ] Log in, open the Grand Exchange: a card appears; buy, sell, skip and pause work.
- [ ] Existing data is still there (flip history, profiles, blocklist).
- [ ] RuneLite's log has no errors from `com.runeassist` (Help, Open logs folder).

## What caused this checklist

Version 1.0.0 (29 September 2026) shipped a welcome card whose buttons ran off
the panel and a settings heading hidden behind the gear. Both had passing unit
tests. Neither had been looked at in a real client.
