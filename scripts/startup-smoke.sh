#!/usr/bin/env bash
# Starts a real RuneLite client with this checkout of the plugin on a fresh,
# empty profile and fails if the plugin logs an error while starting.
# Needs no display: RuneLite's own window fails headless, the plugin still loads.
# Usage: scripts/startup-smoke.sh [seconds]   (default 40)
set -u
cd "$(dirname "$0")/.."
seconds="${1:-40}"
home="$(mktemp -d)"
log="$home/client.log"
trap 'rm -rf "$home"' EXIT
cat > "$home/cp.gradle" <<'GRADLE'
allprojects { tasks.register('printTestCp') { doLast { println 'CP=' + project.sourceSets.test.runtimeClasspath.asPath } } }
GRADLE
cp="$(./gradlew -q -I "$home/cp.gradle" testClasses printTestCp ${RUNELITE_VERSION:+-PruneLiteVersion=$RUNELITE_VERSION} | sed -n 's/^CP=//p')"
[ -n "$cp" ] || { echo "could not resolve the test classpath" >&2; exit 2; }
timeout "$seconds" java -ea -Djava.awt.headless=true -Duser.home="$home" -cp "$cp" \
  com.runeassist.flip.dev.RuneAssistDevClient --developer-mode > "$log" 2>&1
grep -q "c\.r\.f\.\|com\.runeassist" "$log" || { echo "the plugin never loaded; log tail:" >&2; tail -20 "$log" >&2; exit 2; }
# Plugin loggers are abbreviated to c.r.f.<package>.<Class> in RuneLite's log format.
errors="$(grep -E "(ERROR|WARN) +(c\.r\.f\.|com\.runeassist)" "$log" | sed -E 's/^[0-9-]+ [0-9:]+ UTC //' | sort | uniq -c | sort -rn)"
if [ -n "$errors" ]; then
  echo "Plugin errors during startup on a fresh profile:" >&2
  echo "$errors" >&2
  exit 1
fi
echo "Startup smoke OK: plugin loaded on a fresh profile with no errors or warnings."
