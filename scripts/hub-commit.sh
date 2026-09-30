#!/usr/bin/env bash
# Build the commit the Plugin Hub pins from a main commit.
#
# The Hub packages src/main only, and its review reads the diff between the commit it
# has published and the new one. Tests, CI and scripts never reach a player, so the Hub
# commit keeps those paths as the published commit has them and takes everything else
# from main. Reviewers then see plugin code, the build files, the properties and the
# README, nothing else. main stays the branch to develop, test and clone from.
#
#   scripts/hub-commit.sh <main-commit> [<published-commit>]
#
# The published commit defaults to the one in plugin-hub's manifest. The result is a
# merge commit with main first and the published commit second, printed on stdout, on
# branch hub/<date>. Push that branch and pin its commit.
set -euo pipefail
cd "$(dirname "$0")/.."
MAIN_COMMIT=$(git rev-parse --verify "${1:?main commit}^{commit}")
MANIFEST_URL="${HUB_MANIFEST_URL:-https://raw.githubusercontent.com/runelite/plugin-hub/master/plugins/runeassist-flipping}"
PUBLISHED="${2:-$(curl -fsS -m 20 "$MANIFEST_URL" | sed -n 's/^commit=//p')}"
PUBLISHED=$(git rev-parse --verify "${PUBLISHED}^{commit}")
FROZEN_PATHS=(src/test .github scripts)

export GIT_INDEX_FILE
GIT_INDEX_FILE=$(mktemp)
trap 'rm -f "$GIT_INDEX_FILE"' EXIT
git read-tree "$MAIN_COMMIT"
for p in "${FROZEN_PATHS[@]}"; do
  git rm -r -q --cached --ignore-unmatch "$p" >/dev/null
  if git cat-file -e "${PUBLISHED}:${p}" 2>/dev/null; then
    git read-tree --prefix="$p/" "${PUBLISHED}:${p}"
  fi
done
TREE=$(git write-tree)
BRANCH="hub/$(date -u +%Y%m%d)"
MSG="Plugin Hub commit for $(git rev-parse --short "$MAIN_COMMIT")

Plugin code, build files, properties and README as on main at
$(git rev-parse --short "$MAIN_COMMIT"); tests, CI and scripts as the Hub has them
(scripts/hub-commit.sh). Second parent is the published commit so the Hub can
diff from it."
COMMIT=$(git -c user.name=RuneAssist -c user.email=tom@runeassist.com commit-tree "$TREE" -p "$MAIN_COMMIT" -p "$PUBLISHED" -m "$MSG")
git branch -f "$BRANCH" "$COMMIT"
echo "$COMMIT"
