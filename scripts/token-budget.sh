#!/usr/bin/env bash
# The Plugin Hub's AI review only runs on plugins under 200k tokens of source.
# Calibrated on the maintainer's count for commit cb9ae2f on 2026-10-06:
# 200,111 tokens for 929,524 bytes of comment-free src/main/java, about 4.65
# bytes per token. (The first calibration, 4.9 bytes per token on 2026-09-30,
# was taken while the source still had comments and undercounted by 5.6%.)
# BASE_SHA, when set, lets a change that does not grow the source pass while the
# base is still over the limit, so trims can land.
set -euo pipefail
LIMIT=200000
FAIL_AT=195000
WARN_AT=190000
estimate() {
  local bytes
  bytes=$(git ls-tree -r --name-only -z "$1" -- src/main/java | grep -z '\.java$' | xargs -0 -I{} git show "$1:{}" | wc -c)
  echo $(( bytes * 200111 / 929524 ))
}
tokens=$(estimate HEAD)
echo "src/main/java: about ${tokens} tokens (Hub review limit ${LIMIT})"
if (( tokens >= FAIL_AT )); then
  if [[ -n "${BASE_SHA:-}" ]] && base=$(estimate "$BASE_SHA" 2>/dev/null) && (( tokens <= base )); then
    echo "::warning::About ${tokens} tokens (base ${base}); still over ${FAIL_AT}. Keep trimming before the next Hub re-pin."
    exit 0
  fi
  echo "::error::About ${tokens} tokens; the Hub review refuses plugins over ${LIMIT}. Trim src/main before adding more."
  exit 1
elif (( tokens >= WARN_AT )); then
  echo "::warning::About ${tokens} tokens; within $(( LIMIT - tokens )) of the Hub review limit."
fi
