# RuneAssist Flipping

RuneLite plugin for Grand Exchange flipping. Suggestions come from Ares server composition (`POST /v1/suggestion`) with held-cost tracking. Opt-in, pseudonymous telemetry is off by default (Configuration → Privacy).

BSD-2 derivative of [Flipping Copilot](https://github.com/cbrewitt/flipping-copilot) — see `LICENSE` and `THIRD_PARTY_LICENSES.md`.

Sideload the built jar, or wait for Plugin Hub publishing. Plugin class: `com.runeassist.flip.controller.RuneAssistPlugin`.

Plugin Hub maintainers: see `plugin-hub/README.md` for the manifest draft and submission steps. Installing from the Hub shows a warning before install covering coin stack, held stock with cost basis, GE offers/transactions, and IP.

## Data sent to servers

- **(default-on)** `POST https://runeassist.com/v1/suggestion` — capital, live GE offers, held stock with avg buy, risk/timeframe, buy-limit usage, blocked/skipped ids, and IP. Returns a typed suggestion (ABORT/MODIFY/SELL/BUY/WAIT). Soft-fails to a WAIT card if unreachable.
- **(default-on, market ranking / helpers)** `POST https://runeassist.com/v1/flips` — capital, timeframe, risk level, free GE slots, per-item remaining/used buy limits, blocked and skipped item ids, and IP. Ranks flip candidates server-side (also used by tools/tests); no RSN.
- **(default-on)** `GET https://runeassist.com/v1/graph` — price graph data for the item you're viewing.
- **(when Dump alerts prefs are on + GE open)** `POST https://runeassist.com/v1/dump-alerts` — long-lived stream of buy-side dump suggestions (length-prefixed JSON). Filters use your dump min-profit / F2P / blocklist prefs; no RSN.
- **(after device register + OSRS account link)** Flip history — GE transactions upload to your RuneAssist account; Recent Flips restore via `client-flips-delta`. Linking/auth enables history (Preferences); there is no separate cloud-sync setting.
- **(opt-in, Configuration → Privacy)** Telemetry ("Contribute anonymous data") — uploads GE offers, completed GE history and flip-panel decisions under a pseudonymous account hash (SHA-256 of your RSN). Never sends chat, bank contents, or your RSN in plain text.
- **(on demand)** Bug reports ("Report a bug" in Preferences) — sends your report text, RSN, and an optional screenshot (opt-in checkbox, off by default) only after you confirm the dialog, which discloses where the data goes.

Local data directories: `~/.runelite/runeassist-flip/` (suggestion/held-cost state and unacked GE transaction queue pending upload) and `~/.runelite/runeassist/telemetry/` (telemetry JSONL, written locally regardless of the telemetry toggle so a later opt-in can upload history).

If Plugin Hub Flipping Copilot is also enabled, RuneAssist yields (see `HubPluginConflict`).

## Build

Requires JDK 11+.

```
./gradlew jar
```

On Windows: `gradlew.bat jar`. The jar is written to `build/libs/`.

## License

BSD 2-Clause. Copyright holders of the original Flipping Copilot plugin are listed in `LICENSE`. RuneAssist modifications are provided under the same license.
