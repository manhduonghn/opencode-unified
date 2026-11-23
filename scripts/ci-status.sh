#!/usr/bin/env bash
# One-shot GitHub Actions check: no polling loop, no background process.
# Usage: bash scripts/ci-status.sh [run-id]   (defaults to the newest run)
set -euo pipefail

: "${GH_TOKEN:?GH_TOKEN is required}"
REPO="${REPO:-manhduonghn/opencode-unified}"
RUN="${1:-}"
H=(-H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github+json")

if [[ -z "$RUN" ]]; then
  # Filter by workflow: /actions/runs also returns release/dev-latest runs, which are not the
  # build this script is meant to report on.
  RUNS=$(curl -sS "${H[@]}" "https://api.github.com/repos/$REPO/actions/workflows/ci.yml/runs?per_page=1")
  RUN=$(printf '%s' "$RUNS" | grep '"id"' | sed -n '1p' | tr -dc '0-9')
fi

JSON=$(curl -sS "${H[@]}" "https://api.github.com/repos/$REPO/actions/runs/$RUN")
STATUS=$(printf '%s' "$JSON" | grep '"status":' | sed -n '1p' | awk -F'"' '{print $4}')
CONCLUSION=$(printf '%s' "$JSON" | grep '"conclusion":' | sed -n '1p' | awk -F'"' '{print $4}')
echo "run=$RUN status=$STATUS conclusion=${CONCLUSION:-pending}"

if [[ "$STATUS" == "completed" ]]; then
  JOBS=$(curl -sS "${H[@]}" "https://api.github.com/repos/$REPO/actions/runs/$RUN/jobs")
  JID=$(printf '%s' "$JOBS" | grep '"id"' | sed -n '1p' | tr -dc '0-9')
  LOG="${TMPDIR:-/data/data/com.termux/files/usr/tmp/opencode-ref}/ci-$RUN.log"
  curl -sSL "${H[@]}" "https://api.github.com/repos/$REPO/actions/jobs/$JID/logs" -o "$LOG"
  echo "log=$LOG"
  grep -nE 'e: file|error:|FAILED|FAILURE|Lint found|##\[error\]|Execution failed' "$LOG" | tail -40 || true
fi
