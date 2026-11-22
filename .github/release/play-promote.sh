#!/usr/bin/env bash
# Puts a bundle on a Google Play track: uploads it first when given a bundle and its R8 mapping
# (CI's internal testing upload), otherwise promotes one Play already has, e.g. the build CI just
# shipped to internal testing onto open testing (`beta`), like iOS's External TestFlight group.
#   PLAY_SERVICE_ACCOUNT_JSON='{…}' .github/release/play-promote.sh <package> <track> <versionCode> <release name> [<bundle> <mapping>]
# Play keeps every uploaded bundle, so promoting only edits the track; uploading the same
# versionCode again would be rejected. Fails, changing nothing, while Google is reviewing earlier
# changes. Needs curl, jq and openssl. Never prints the key or the access token.
set -euo pipefail

if [[ $# -ne 4 && $# -ne 6 ]]; then
  echo "usage: $0 <package> <track> <versionCode> <release name> [<bundle> <mapping>]" >&2
  exit 64
fi
package=$1 track=$2 version_code=$3 release_name=$4 bundle=${5:-} mapping=${6:-}
api="https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$package"
upload_api="https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$package"

: "${PLAY_SERVICE_ACCOUNT_JSON:?PLAY_SERVICE_ACCOUNT_JSON is not set}"
client_email=$(jq -r '.client_email // empty' <<<"$PLAY_SERVICE_ACCOUNT_JSON")
token_uri=$(jq -r '.token_uri // "https://oauth2.googleapis.com/token"' <<<"$PLAY_SERVICE_ACCOUNT_JSON")
if [[ -z "$client_email" ]]; then
  echo "PLAY_SERVICE_ACCOUNT_JSON has no client_email" >&2
  exit 1
fi

key_file=$(umask 077 && mktemp)
trap 'rm -f "$key_file"' EXIT
jq -r '.private_key' <<<"$PLAY_SERVICE_ACCOUNT_JSON" >"$key_file"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# Service-account OAuth: a JWT signed with the account's key, exchanged for an access token.
now=$(date +%s)
header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
claims=$(jq -cjn --arg iss "$client_email" --arg aud "$token_uri" --argjson iat "$now" '{
  iss: $iss,
  scope: "https://www.googleapis.com/auth/androidpublisher",
  aud: $aud,
  iat: $iat,
  exp: ($iat + 600)
}' | b64url)
signature=$(printf '%s.%s' "$header" "$claims" | openssl dgst -sha256 -sign "$key_file" -binary | b64url)

# call <url> <curl args…>: prints the response body, or fails with the URL and Google's error so
# the reason shows up in the log (never the request, which carries the signed assertion or token).
call() {
  local url=$1 response status
  shift
  response=$(curl -sS -w '\n%{http_code}' "$@" "$url")
  status=${response##*$'\n'}
  response=${response%$'\n'*}
  if [[ "$status" != 2* ]]; then
    echo "HTTP $status from $url" >&2
    echo "$response" >&2
    return 1
  fi
  printf '%s' "$response"
}

token=$(call "$token_uri" -X POST \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer' \
  --data-urlencode "assertion=$header.$claims.$signature" | jq -r '.access_token')
auth=(-H "Authorization: Bearer $token")

# put_on_track <status>: one edit that uploads the bundle (if given), sets the track's release and
# commits it. Each step returns on failure itself: `set -e` doesn't apply inside a function called
# as an `if` test.
put_on_track() {
  local edit release
  edit=$(call "$api/edits" -X POST "${auth[@]}" -H 'Content-Type: application/json' -d '{}' | jq -r '.id') || return 1
  if [[ -n "$bundle" ]]; then
    call "$upload_api/edits/$edit/bundles?uploadType=media" -X POST "${auth[@]}" \
      -H 'Content-Type: application/octet-stream' --data-binary "@$bundle" >/dev/null || return 1
    call "$upload_api/edits/$edit/apks/$version_code/deobfuscationFiles/proguard?uploadType=media" -X POST \
      "${auth[@]}" -H 'Content-Type: application/octet-stream' --data-binary "@$mapping" >/dev/null || return 1
  fi
  release=$(jq -cn --arg track "$track" --arg name "$release_name" --arg code "$version_code" --arg status "$1" '{
    track: $track,
    releases: [{name: $name, versionCodes: [$code], status: $status}]
  }') || return 1
  call "$api/edits/$edit/tracks/$track" -X PUT "${auth[@]}" -H 'Content-Type: application/json' \
    -d "$release" >/dev/null || return 1
  # Play's default commit cancels a review in progress and starts it again from the beginning.
  call "$api/edits/$edit:commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW" -X POST "${auth[@]}" >/dev/null || return 1
}

errors=$(mktemp)
trap 'rm -f "$key_file" "$errors"' EXIT
if put_on_track completed 2>"$errors"; then
  echo "Released $release_name (versionCode $version_code) to the $track track."
elif grep -q 'Only releases with status draft may be created on draft app' "$errors"; then
  # Until Google has reviewed the app once, Play only takes draft releases on non-internal
  # tracks. Leave this one as the track's draft for someone to send for review in Play Console.
  put_on_track draft
  echo "::warning::$release_name (versionCode $version_code) is a draft on the $track track: the app hasn't passed its first review, so send it for review in Play Console (Publishing overview)."
elif grep -q 'CHANGES_ALREADY_IN_REVIEW' "$errors"; then
  echo "::error::Google is still reviewing earlier changes, so nothing was sent: $release_name (versionCode $version_code) isn't on the $track track. Try again once Play Console's Publishing overview shows no changes in review."
  exit 1
else
  cat "$errors" >&2
  exit 1
fi
