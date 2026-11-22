#!/usr/bin/env bash
# publish-github-release.sh: tags the released commit and creates its GitHub
# release. Release runs it last, after Play took the build, so a failed
# release spends no version; tags trigger nothing. A rerun accepts a tag that
# already names the commit.
#
# Environment: OC_RELEASE_TAG, GITHUB_SHA, GITHUB_REPOSITORY, GH_TOKEN.
. "$(dirname "$0")/lib.sh"

tag=$OC_RELEASE_TAG
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "Unexpected tag $tag."

existing=$(gh api "repos/$GITHUB_REPOSITORY/git/matching-refs/tags/$tag" \
  --jq ".[] | select(.ref == \"refs/tags/$tag\") | .object.sha")
if [ -z "$existing" ]; then
  gh api "repos/$GITHUB_REPOSITORY/git/refs" -f ref="refs/tags/$tag" -f sha="$GITHUB_SHA" > /dev/null
elif [ "$existing" != "$GITHUB_SHA" ]; then
  die "Tag $tag already names $existing, not $GITHUB_SHA."
fi

if ! gh release view "$tag" -R "$GITHUB_REPOSITORY" > /dev/null 2>&1; then
  gh release create "$tag" -R "$GITHUB_REPOSITORY" --verify-tag --title "$tag" --generate-notes --latest
fi
echo "https://github.com/$GITHUB_REPOSITORY/releases/tag/$tag" >> "$GITHUB_STEP_SUMMARY"
