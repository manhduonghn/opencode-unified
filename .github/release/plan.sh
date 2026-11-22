#!/usr/bin/env bash
# plan.sh: decides whether this run releases and as which version, from the
# event, the ref and the repo's tags, and writes the plan to $GITHUB_OUTPUT.
#
# Releases run from dev only. The version is the latest vX.Y.Z tag bumped by
# BUMP (patch, minor or major; the schedule always bumps patch). A scheduled
# run skips outside noon and 6 p.m. Central, when dev hasn't moved since the
# last release, or when ci.yml hasn't passed on the commit yet; a dispatch
# fails instead. The version code is the run number plus OFFSET, so it only
# goes up (Play rejects a code it has seen).
#
# Environment: EVENT, REF_NAME, BUMP, GITHUB_SHA, GITHUB_REPOSITORY,
# GITHUB_RUN_NUMBER, GH_TOKEN.
. "$(dirname "$0")/lib.sh"

# android.yml released up to version code 524 before release.yml took over.
OFFSET=1000

skip() {
  echo "skip=true" >> "$GITHUB_OUTPUT"
  echo "Skipped: $*" >> "$GITHUB_STEP_SUMMARY"
  echo "::notice::$*"
  exit 0
}
# A scheduled run skips quietly; a dispatch says why it can't release.
refuse() {
  if [ "$EVENT" = schedule ]; then skip "$*"; fi
  die "$*"
}

[ "$REF_NAME" = dev ] || die "Releases run from dev, not $REF_NAME."
case "$EVENT" in
  schedule)
    hour=$(TZ=America/Chicago date +%H)
    [ "$hour" = 12 ] || [ "$hour" = 18 ] || skip "Not a release hour in Chicago ($hour)."
    BUMP=patch
    ;;
  workflow_dispatch) ;;
  *) die "Unexpected event $EVENT." ;;
esac

latest=$(gh api --paginate "repos/$GITHUB_REPOSITORY/git/matching-refs/tags/v" --jq '.[].ref' |
  sed 's|^refs/tags/||' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1)
[ -n "$latest" ] || die "No vX.Y.Z tag to bump from."
released=$(gh api "repos/$GITHUB_REPOSITORY/commits/$latest" --jq .sha)
[ "$released" != "$GITHUB_SHA" ] || refuse "$GITHUB_SHA is already released as $latest."

status=$(gh run list --repo "$GITHUB_REPOSITORY" --workflow ci.yml --branch dev --event push \
  --commit "$GITHUB_SHA" --json conclusion --jq '.[0].conclusion' || true)
[ "$status" = success ] || refuse "ci.yml hasn't passed on $GITHUB_SHA (latest run: ${status:-none or still running})."

IFS=. read -r major minor patch <<< "${latest#v}"
case "$BUMP" in
  major) major=$((major + 1)) minor=0 patch=0 ;;
  minor) minor=$((minor + 1)) patch=0 ;;
  patch) patch=$((patch + 1)) ;;
  *) die "Unknown bump $BUMP." ;;
esac
version="$major.$minor.$patch"
code=$((GITHUB_RUN_NUMBER + OFFSET))

{
  echo "skip=false"
  echo "version=$version"
  echo "tag=v$version"
  echo "code=$code"
} >> "$GITHUB_OUTPUT"
echo "Releasing $version (version code $code) from $GITHUB_SHA, after $latest." >> "$GITHUB_STEP_SUMMARY"
