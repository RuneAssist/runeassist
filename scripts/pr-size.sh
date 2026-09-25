#!/usr/bin/env bash
# Report and bound the size of a change set. Used by CI on pull requests and
# locally before opening one:  scripts/pr-size.sh origin/main HEAD
#
# Counts, between BASE and HEAD:
#   raw lines      additions + deletions as GitHub shows them
#   real lines     the same ignoring whitespace and CR/LF changes (what a reviewer reads)
#   files          files touched
#   words          word-level tokens added + removed (ignoring whitespace)
#   eol churn      files whose diff is almost entirely line-ending / whitespace noise
#
# Exit 1 when real lines exceed the fail threshold (unless SIZE_EXEMPT=1) or when
# any file is pure line-ending churn. Thresholds are env-overridable.
#
# The RuneLite Hub reviews the cumulative diff since the commit named in
# plugin-hub's plugins/runeassist-flipping manifest, not one PR at a time. To see
# what the next Hub submission would carry:
#   scripts/pr-size.sh --hub HEAD          (fetches the published commit)
#   HUB_COMMIT=<sha> scripts/pr-size.sh --hub HEAD
set -euo pipefail

HUB_MANIFEST_URL="${HUB_MANIFEST_URL:-https://raw.githubusercontent.com/runelite/plugin-hub/master/plugins/runeassist-flipping}"
if [[ "${1:-}" == "--hub" ]]; then
  HUB_COMMIT="${HUB_COMMIT:-$(curl -fsS -m 20 "$HUB_MANIFEST_URL" | sed -n 's/^commit=//p')}"
  if [[ -z "$HUB_COMMIT" ]]; then echo "could not read the published Hub commit" >&2; exit 2; fi
  set -- "$HUB_COMMIT" "${2:-HEAD}"
  : "${PR_SIZE_TITLE:=Next Hub review (since published ${HUB_COMMIT:0:7})}"
  # Cumulative size is reported, never enforced: it is the number to watch.
  PR_SIZE_WARN_LINES="${PR_SIZE_WARN_LINES:-1000}"
  PR_SIZE_FAIL_LINES="${PR_SIZE_FAIL_LINES:-1000000}"
fi
BASE="${1:?base ref}"
HEAD="${2:-HEAD}"
WARN_LINES="${PR_SIZE_WARN_LINES:-300}"
FAIL_LINES="${PR_SIZE_FAIL_LINES:-600}"
EXEMPT="${SIZE_EXEMPT:-0}"
# Generated or vendored paths never count.
EXCLUDE=(':!*.lock' ':!*lock.json' ':!gradlew' ':!gradlew.bat' ':!gradle/wrapper/*' ':!*.png' ':!*.jar')

range="$BASE...$HEAD"
raw=$(git diff --numstat "$range" -- . "${EXCLUDE[@]}" | awk '$1!="-"{a+=$1; d+=$2} END{print a+0, d+0}')
real=$(git diff --numstat --ignore-all-space --ignore-blank-lines --ignore-cr-at-eol "$range" -- . "${EXCLUDE[@]}" | awk '$1!="-"{a+=$1; d+=$2} END{print a+0, d+0}')
files=$(git diff --name-only "$range" -- . "${EXCLUDE[@]}" | wc -l | tr -d ' ')
words=$(git diff --word-diff=porcelain --ignore-all-space --ignore-cr-at-eol "$range" -- . "${EXCLUDE[@]}" \
  | grep -E '^[+-][^+-]' | wc -w | tr -d ' ')

read -r raw_add raw_del <<<"$raw"
read -r real_add real_del <<<"$real"
raw_total=$((raw_add + raw_del))
real_total=$((real_add + real_del))

# A file whose raw diff is large but whose whitespace/EOL-insensitive diff is
# tiny has been rewritten (editor line endings, reformat), not changed.
churn=()
while IFS=$'\t' read -r a d f; do
  [[ "$a" == "-" ]] && continue
  rt=$((a + d)); [[ $rt -lt 40 ]] && continue
  rr=$(git diff --numstat --ignore-all-space --ignore-blank-lines --ignore-cr-at-eol "$range" -- "$f" | awk '{print ($1=="-"?0:$1+$2)}')
  rr=${rr:-0}
  if [[ $rr -le $((rt / 10)) ]]; then churn+=("$f (raw $rt, real $rr)"); fi
done < <(git diff --numstat "$range" -- . "${EXCLUDE[@]}")

status="ok"
[[ $real_total -gt $WARN_LINES ]] && status="warn"
[[ $real_total -gt $FAIL_LINES ]] && status="fail"
[[ ${#churn[@]} -gt 0 ]] && status="fail"

{
  echo "## ${PR_SIZE_TITLE:-PR size}"
  echo
  echo "| metric | value |"
  echo "|---|---|"
  echo "| raw lines (+/-) | $raw_total (+$raw_add / -$raw_del) |"
  echo "| real lines, ignoring whitespace and line endings | $real_total (+$real_add / -$real_del) |"
  echo "| files | $files |"
  echo "| words changed | $words |"
  echo "| thresholds | warn > $WARN_LINES, fail > $FAIL_LINES real lines |"
  echo
  if [[ ${#churn[@]} -gt 0 ]]; then
    echo "**Line-ending / whitespace rewrite detected** in:"
    for c in "${churn[@]}"; do echo "- \`$c\`"; done
    echo
    echo "Restore the original line endings (\`git checkout -- <file>\` then re-apply the edit) so reviewers see only the real change."
    echo
  fi
  case "$status" in
    ok)   echo "Size OK." ;;
    warn) if [[ -n "${HUB_COMMIT:-}" ]]; then
            echo "**Large Hub review.** Over $WARN_LINES real lines since the published commit: consider submitting to the Hub now so the next review stays small."
          else
            echo "**Large PR.** Over $WARN_LINES real lines: consider splitting so Hub review and rollback stay easy."
          fi ;;
    fail) [[ $real_total -gt $FAIL_LINES ]] && echo "**Too large.** Over $FAIL_LINES real lines. Split it, or add the \`size-exempt\` label with a reason in the description." ;;
  esac
} | tee "${PR_SIZE_REPORT:-/dev/null}"

if [[ "$status" == "fail" ]]; then
  if [[ ${#churn[@]} -eq 0 && "$EXEMPT" == "1" ]]; then echo "size-exempt label present; not failing."; exit 0; fi
  exit 1
fi
