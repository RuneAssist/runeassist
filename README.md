# RuneAssist Flipping

**Grand Exchange flipping that fits how you play.** RuneAssist watches the market and your GE slots, then tells you what to buy, what price to list at, when to sell, and when to cut a loss. Set how often you come back to check your offers (5 minutes, 30 minutes, 2 hours, 8 hours) and it picks flips that suit that rhythm.

[![RuneAssist dashboard](https://raw.githubusercontent.com/RuneAssist/runeassist/main/docs/img/dashboard.png)](https://runeassist.com)

## What you get

- **One card at a time.** Buy this, at this price, this many. Sell what you hold when it's worth it. Abort an offer that isn't filling. No spreadsheets.
- **Honest sizing.** Every suggestion is sized to your coin stack, your free slots and the item's buy limit, with the 2% GE tax already taken off the profit it shows you.
- **It learns from real fills.** Suggestions are ranked on live OSRS wiki prices plus the outcomes of every RuneAssist player's flips, so items that only look profitable on paper drop out.
- **Held-stock tracking.** It knows what you bought and for how much, across logins, so sell cards use your real cost, not a guess.
- **Cut a loss on purpose.** From the web dashboard you can tell the plugin to sell a stuck position at the current price, even below cost.
- **Dump alerts, F2P mode, block list, risk level** and a profit overlay on your GE offers.

## Get started in two minutes

1. Install from the Plugin Hub and open the Grand Exchange. The side panel wakes up and shows your first card.
2. Optional: pair the plugin with [runeassist.com](https://runeassist.com) (Preferences → Get pairing code) for a dashboard of your flips, profit graphs and open positions on any device.
3. Optional: [join the Discord](https://discord.gg/3CPGh9GPaT) for help, flip chat, dump alerts, release notes and profit-tier roles.

## Privacy, in one paragraph

The plugin sends the server what it needs to make a suggestion: your coin stack, your live GE offers, the stock it is tracking with its cost, and the quantities of those tracked items in your inventory. It never sends your bank, your chat, your location or your full inventory. Suggestion outcomes are recorded under a pseudonymous account hash to improve the model; turn that off under Configuration → Privacy. The full list of every request is below.

RuneAssist is a BSD-2 derivative of [Flipping Copilot](https://github.com/cbrewitt/flipping-copilot) (see `LICENSE` and `THIRD_PARTY_LICENSES.md`). If Flipping Copilot is also enabled, RuneAssist steps aside automatically; run one or the other.

---

## Data sent to servers

- **(default-on, while the GE is open and suggestions are not paused)** `POST https://runeassist.com/v1/suggestion` — capital, live GE offers, held stock with avg buy, current inventory quantities limited to already tracked held item IDs, risk/timeframe, buy-limit usage, blocked/skipped ids, and IP. Returns a typed suggestion (ABORT/MODIFY/SELL/BUY/WAIT). No full inventory, bank contents or location coordinates are sent. The request remains transient unless training contribution is enabled. Soft-fails to a WAIT card if unreachable.
- **(default-on, market ranking / helpers)** `POST https://runeassist.com/v1/flips` — capital, timeframe, risk level, free GE slots, per-item remaining/used buy limits, blocked and skipped item ids, and IP. Ranks flip candidates server-side (also used by tools/tests); no RSN.
- **(default-on)** `GET https://runeassist.com/v1/graph` — price graph data for the item you're viewing.
- **(when Dump alerts prefs are on + GE open)** `POST https://runeassist.com/v1/dump-alerts` — long-lived stream of buy-side dump suggestions (length-prefixed JSON). Filters use your dump min-profit / F2P / blocklist prefs; no RSN.
- **(after device register + OSRS account link)** Flip history — GE transactions upload to your RuneAssist account; Recent Flips restore via `client-flips-delta`. Linking/auth enables history (Preferences); there is no separate cloud-sync setting.
- **(default-on; opt out under Configuration → Privacy)** Training contribution — authenticated suggestion responses receive a lifecycle UUID. RuneAssist retains a minimized suggestion record, live GE offer snapshots, immediate placed/partial/completed/cancelled/repriced transitions, and later action/fill attribution under a pseudonymous account hash. Offer records include the RuneAssist lifecycle UUID when applicable and are otherwise marked external. Replaced recommendations are closed as superseded instead of being mistaken for rejections. It never sends chat or bank contents through this channel.
- **(on demand)** Bug reports ("Report a bug" in Preferences) — sends your report text, RSN, and an optional screenshot (opt-in checkbox, off by default) only after you confirm the dialog, which discloses where the data goes.

Local data directory: `~/.runelite/runeassist-flip/` (suggestion/held-cost state and the durable unacked GE transaction queue pending server acknowledgement).

Suggestions automatically show a quiet **Away** state when the GE is closed and refresh when it reopens. Manual pause remains independent for each account. Offer observation, personal history and enabled training/cloud-sync channels keep running while away or paused. Already issued decant instructions can remain visible while visiting the decanter; no new trade prompts are fetched while the GE is closed.

If Plugin Hub Flipping Copilot is also enabled, RuneAssist yields (see `HubPluginConflict`).

## Build

Requires JDK 11+. Plugin Hub maintainers: see `plugin-hub/README.md` for the manifest and submission notes; the Hub install warning covers coin stack, held stock with cost basis, GE offers/transactions, and IP.

```
./gradlew jar
```

On Windows: `gradlew.bat jar`. The jar is written to `build/libs/`.

Before opening a pull request, run `python3 scripts/hub_rules.py`: it checks the code against the Plugin Hub's review rules (no thread interrupts, no reflection, file access through `Filepath`, and so on) and the same checks run in CI. The rules and their replacements are listed in [docs/hub-rules.md](docs/hub-rules.md).

## License

BSD 2-Clause. Copyright holders of the original Flipping Copilot plugin are listed in `LICENSE`. RuneAssist modifications are provided under the same license.
