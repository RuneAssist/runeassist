# RuneAssist Flipping

RuneLite plugin for Grand Exchange flipping. Suggestions come from a server-ranked candidate list plus an on-device engine with held-cost tracking. Opt-in, pseudonymous telemetry contribution and cloud history sync are off by default (Configuration → Privacy).

Based on [Flipping Copilot](https://github.com/cbrewitt/flipping-copilot), used under the BSD 2-Clause License (see `LICENSE` and `THIRD_PARTY_LICENSES.md`).

Sideload the built jar, or wait for Plugin Hub publishing. Plugin class: `com.runeassist.flip.controller.RuneAssistPlugin`.

Plugin Hub maintainers: see `plugin-hub/README.md` for the manifest draft and submission steps. Installing from the Hub shows a warning before install: "This plugin submits your grand exchange offers, grand exchange transactions, and IP address to a 3rd party server not controlled or verified by the RuneLite Developers."

## Data sent to servers

- **(default-on)** `POST https://runeassist.ares-server.co.uk/v1/flips` — capital, timeframe, risk level, free GE slots, per-item remaining/used buy limits, blocked and skipped item ids, and your IP (implicit in any HTTP request). This ranks flip candidates; there is no account-identifying data (no RSN) in the request.
- **(default-on)** `GET https://runeassist.ares-server.co.uk/v1/graph` — price graph data for the item you're viewing.
- **(opt-in, Configuration → Privacy)** Telemetry ("Contribute anonymous data") — uploads GE offers, completed GE history and flip-panel decisions under a pseudonymous account hash (SHA-256 of your RSN). Never sends chat, bank contents, or your RSN in plain text.
- **(opt-in, Configuration → Privacy)** Cloud sync ("Cloud sync flip history") — links this client to a RuneAssist cloud account and uploads your GE transactions and RSN so history syncs across devices.
- **(on demand)** Bug reports ("Report a bug" in Preferences) — sends your report text, RSN, and an optional screenshot (opt-in checkbox, off by default) only after you confirm the dialog, which discloses where the data goes.

Local-only data directories written by this plugin (never uploaded unless the toggles above are on): `~/.runelite/runeassist-flip/` (suggestion/held-cost state) and `~/.runelite/runeassist/telemetry/` (telemetry JSONL, written locally regardless of the telemetry toggle so a later opt-in can upload history).

## Relationship to Flipping Copilot

RuneAssist Flipping is a BSD-2 derivative of [Flipping Copilot](https://github.com/cbrewitt/flipping-copilot) (see `LICENSE` and `THIRD_PARTY_LICENSES.md`). It is a separate plugin, not a replacement, because it uses a different backend (its own server, not Flipping Copilot's) and its own on-device suggestion engine. Both plugins can be installed at once; if Plugin Hub's Flipping Copilot is enabled, RuneAssist yields (shows a "Turn off Plugin Hub Flipping Copilot" wait state instead of competing suggestions) — see `HubPluginConflict`.

## Build

Requires JDK 11+.

```
./gradlew jar
```

On Windows: `gradlew.bat jar`. The jar is written to `build/libs/`.

## License

BSD 2-Clause. Copyright holders of the original Flipping Copilot plugin are listed in `LICENSE`. RuneAssist modifications are provided under the same license.
