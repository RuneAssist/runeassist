# RuneAssist Flipping

RuneLite plugin for Grand Exchange flipping. Suggestions come from a server-ranked candidate list plus an on-device engine with held-cost tracking. Opt-in, pseudonymous telemetry contribution and cloud history sync are off by default (Configuration → Privacy).

Based on [Flipping Copilot](https://github.com/cbrewitt/flipping-copilot), used under the BSD 2-Clause License (see `LICENSE` and `THIRD_PARTY_LICENSES.md`).

Sideload the built jar, or wait for Plugin Hub publishing. Plugin class: `com.runeassist.flip.controller.RuneAssistPlugin`.

Plugin Hub maintainers: see `plugin-hub/README.md` for the manifest draft and submission steps.

## Build

Requires JDK 11+.

```
./gradlew jar
```

On Windows: `gradlew.bat jar`. The jar is written to `build/libs/`.

## License

BSD 2-Clause. Copyright holders of the original Flipping Copilot plugin are listed in `LICENSE`. RuneAssist modifications are provided under the same license.
