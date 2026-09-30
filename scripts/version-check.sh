#!/usr/bin/env bash
# The plugin version is written in three places; they must agree.
set -euo pipefail
cd "$(dirname "$0")/.."
gradle=$(sed -n "s/^version = '\(.*\)'$/\1/p" build.gradle)
props=$(sed -n 's/^version=\(.*\)$/\1/p' runelite-plugin.properties)
java=$(sed -n 's/.*VERSION = "\(.*\)";/\1/p' src/main/java/com/runeassist/flip/util/Version.java)
echo "build.gradle=$gradle runelite-plugin.properties=$props Version.java=$java"
if [[ -z "$gradle" || "$gradle" != "$props" || "$gradle" != "$java" ]]; then
  echo "version mismatch: keep build.gradle, runelite-plugin.properties and Version.java equal" >&2
  exit 1
fi
