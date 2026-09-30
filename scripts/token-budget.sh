#!/usr/bin/env bash
# The Plugin Hub's AI review only runs on plugins under 200k tokens of source.
# On 2026-09-30 a maintainer counted 200,610 tokens for 984,036 bytes of
# src/main/java, about 4.9 bytes per token. This estimates the count the same
# way and fails well before the limit, so there is room for the next change.
set -euo pipefail
LIMIT=200000
FAIL_AT=195000
WARN_AT=190000
bytes=$(git ls-files -z 'src/main/java/*.java' | xargs -0 cat | wc -c)
tokens=$(( bytes * 200610 / 984036 ))
echo "src/main/java: ${bytes} bytes, about ${tokens} tokens (Hub review limit ${LIMIT})"
if (( tokens >= FAIL_AT )); then
  echo "::error::About ${tokens} tokens; the Hub review refuses plugins over ${LIMIT}. Trim src/main before adding more."
  exit 1
elif (( tokens >= WARN_AT )); then
  echo "::warning::About ${tokens} tokens; within $(( LIMIT - tokens )) of the Hub review limit."
fi
