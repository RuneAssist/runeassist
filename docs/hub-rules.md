# Plugin Hub rules

The RuneLite Plugin Hub reviews every update by hand and rejects a fixed set of
things; its packager also rebuilds every plugin against a pinned RuneLite
version and fails the build on manifest or API problems. A bounce costs days,
so `scripts/hub_rules.py` runs on every pull request (workflow "Plugin Hub
rules") and mirrors both.

Run it locally before opening a PR:

```
python3 scripts/hub_rules.py
./gradlew build -PruneLiteVersion=$(curl -fsS https://raw.githubusercontent.com/runelite/plugin-hub/master/runelite.version)
```

## Reviewer rules

Collected from review comments on `runelite/plugin-hub` pull requests. Each is
enforced on `src/main`; the first two also on tests.

| Rule | Not allowed | Use instead |
|---|---|---|
| thread-interrupt | `Thread.interrupt()`, `Thread.currentThread().interrupt()` | let the task finish; `Semaphore.acquireUninterruptibly`, `Future.cancel(false)` |
| shutdown-now | `ExecutorService.shutdownNow()` (interrupts workers) | `shutdown()` |
| thread-sleep | `Thread.sleep` | schedule on the injected `ScheduledExecutorService` |
| reflection | `java.lang.reflect`, `Class.forName`, `setAccessible`, Gson `TypeToken` | typed classes, `JsonObject`/`JsonArray` |
| file-io | `java.io.File`, `java.nio.file.Files/Paths`, `FileReader` etc., `RuneLite.RUNELITE_DIR` | `net.runelite.client.util.Filepath` from `Plugin.getPluginDirectory()` (rule introduced with RuneLite 1.13.0, September 2026) |
| getenv | `System.getenv` | a JVM system property |
| awt-desktop / linkbrowser-open | `java.awt.Desktop`, `LinkBrowser.open` | `LinkBrowser.browse` (http/https only) |
| keyboard-focus-manager | `KeyboardFocusManager` | RuneLite `KeyManager` |
| system-io | `System.out/err`, `printStackTrace` | the slf4j logger |
| javax-sound | `javax.sound` | `net.runelite.client.audio.AudioPlayer` |
| runtime-subprocess | `Runtime.getRuntime`, `ProcessBuilder`, `availableProcessors`, `System.exit` | nothing; not permitted |
| client-actions | `client.menuAction`, `hopToWorld` | nothing; not permitted |
| discord-internals / account-internals | RuneLite's Discord and account/session classes | nothing; not permitted |
| runelite-package | code under `package net.runelite` | your own package |

## Packager rules

From `runelite/plugin-hub-tooling` (`disallowed-apis.txt` and `Plugin.java`).

| Rule | Detail |
|---|---|
| new-gson / new-okhttp | never construct `Gson`, `GsonBuilder` or `OkHttpClient`; `@Inject` the client's and call `newBuilder()` |
| widget-info / client-getvar | `WidgetInfo`, `WidgetID`, `client.getVar` are terminally deprecated |
| manifest | `runelite-plugin.properties` must set displayName, description, author, plugins, build; only tags, support and version may be added |
| licence | `LICENSE` present, BSD 2-Clause |
| icon | `icon.png` at most 256KiB and 48x72 px |
| build | compiles on Java 11 against the RuneLite version in `runelite/plugin-hub` `runelite.version` |

## Baseline

`scripts/hub-rules-baseline.txt` lists violations that existed when the check
was introduced, one count per file and rule. A count may only go down. When you
remove some, run `python3 scripts/hub_rules.py --update-baseline` and commit
the smaller file. Nothing new may be added anywhere.
