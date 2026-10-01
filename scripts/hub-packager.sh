#!/usr/bin/env bash
# Build this commit with the Plugin Hub's own packager, the way a Hub PR would.
# It clones plugin-hub, points plugins/runeassist-flipping at COMMIT (which must be
# pushed to GitHub, because the packager clones it from there), and runs the
# packager over that one-commit range. Needs Java 11 on PATH, like the Hub.
#
#   scripts/hub-packager.sh <commit> [<work-dir>]
set -euo pipefail
COMMIT=$(git rev-parse --verify "${1:?commit}^{commit}")
WORK="${2:-$(mktemp -d)}"
BUNDLE_URL="https://github.com/runelite/plugin-hub-tooling/releases/download/v4/bundle.tar.zst"
BUNDLE_SHA256="627c97f0ae8b86d59dc3e9c666ab75a7c88ed58ec6320967eabc172b36c3dcd5"
mkdir -p "$WORK"
cd "$WORK"
if [ ! -f package.jar ]; then
  curl --location --fail --retry 4 --max-time 60 --output bundle.tar.zst "$BUNDLE_URL"
  echo "$BUNDLE_SHA256  bundle.tar.zst" | sha256sum -c -
  tar xf bundle.tar.zst
fi
rm -rf plugin-hub
git clone -q --depth 1 https://github.com/runelite/plugin-hub.git plugin-hub
[ -d api ] || ./prepare.sh
cd plugin-hub
BASE=$(git rev-parse HEAD)
sed -i "s/^commit=.*/commit=$COMMIT/" plugins/runeassist-flipping
git -c user.name=ci -c user.email=ci@localhost commit -q -am "test runeassist-flipping $COMMIT"
HEAD_=$(git rev-parse HEAD)
cd ..
rm -rf /tmp/jars /tmp/manifest_diff
status=0
PACKAGE_IS_PR=true PACKAGE_COMMIT_RANGE="$BASE...$HEAD_" \
  java -XX:+UseParallelGC -cp package.jar net.runelite.pluginhub.packager.Packager > packager.log 2>&1 || status=$?
grep -E "runeassist-flipping|warning:|error:|disallowed|not allowed" packager.log | grep -v "^Cloning" || true
if [ -f /tmp/jars/runeassist-flipping.log ]; then cat /tmp/jars/runeassist-flipping.log; fi
if [ "$status" -ne 0 ] || ! grep -q "runeassist-flipping: done" packager.log; then
  echo "::error::The Plugin Hub packager rejected this commit; see the log above."
  tail -60 packager.log
  exit 1
fi
echo "Plugin Hub packager: built OK"
