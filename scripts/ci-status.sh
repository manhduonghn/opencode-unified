#!/usr/bin/env bash
# One-shot GitHub Actions check: no polling loop, no background process.
# Usage: scripts/ci-status.sh [run-id]   (defaults to the newest run)
set -euo pipefail

: "${GH_TOKEN:?GH_TOKEN is required}"
REPO="${REPO:-manhduonghn/opencode-unified}"
RUN="${1:-}"
H=(-H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github+json")

if [[ -z "$RUN" ]]; then
  RUN=$(curl -sS "${H[@]}" "https://api.github.com/repos/$REPO/actions/runs?per_page=1" \
    | grep -m1 '"id"' | tr -dc '0-9')
fi

JSON=$(curl -sS "${H[@]}" "https://api.github.com/repos/$REPO/actions/runs/$RUN")
STATUS=$(printf '%s' "$JSON" | grep -m1 '"status":' | tr -dc 'a-z')
CONCLUSION=$(printf '%s' "$JSON" | grep -m1 '"conclusion":' | tr -dc 'a-z')
echo "run=$RUN status=$STATUS conclusion=${CONCLUSION:-pending}"

if [[ "$STATUS" == "completed" ]]; then
  JID=$(curl -sS "${H[@]}" "https://api.github.com/repos/$REPO/actions/runs/$RUN/jobs" \
    | grep -m1 '"id"' | tr -dc '0-9')
  LOG="${TMPDIR:-/data/data/com.termux/files/usr/tmp/opencode-ref}/ci-$RUN.log"
  curl -sSL "${H[@]}" "https://api.github.com/repos/$REPO/actions/jobs/$JID/logs" -o "$LOG"
  echo "log=$LOG"
  grep -nE 'e: file|error:|FAILED|FAILURE|Lint found|##\[error\]|Execution failed' "$LOG" | tail -40 || true
fi
