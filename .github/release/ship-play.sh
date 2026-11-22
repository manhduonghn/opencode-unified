#!/usr/bin/env bash
# ship-play.sh <bundle> <mapping>: uploads the signed bundle to Google Play
# internal testing, then puts the same build on open testing (beta) when
# PLAY_OPEN_TESTING is true. Open testing needs the store listing, content
# rating, data safety form and privacy policy (docs/releasing.md).
#
# Environment: PLAY_SERVICE_ACCOUNT_JSON, OC_VERSION, OC_VERSION_CODE,
# PLAY_OPEN_TESTING.
. "$(dirname "$0")/lib.sh"

[ $# -eq 2 ] || die "Usage: ship-play.sh <bundle> <mapping>"
package=ai.opencode.mobilecode
name="$OC_VERSION ($OC_VERSION_CODE)"
promote="$(dirname "$0")/play-promote.sh"

bash "$promote" "$package" internal "$OC_VERSION_CODE" "$name" "$1" "$2"
if [ "${PLAY_OPEN_TESTING:-}" = true ]; then
  bash "$promote" "$package" beta "$OC_VERSION_CODE" "$name"
else
  echo "::notice::PLAY_OPEN_TESTING isn't true; the build stays on internal testing only."
fi
