#!/usr/bin/env bash
# sign-bundle.sh <unsigned.aab> <signed.aab>: signs the bundle with the Play
# upload key and checks the signature. Play App Signing holds the app signing
# key; this key only proves the upload came from us.
#
# Environment: ANDROID_UPLOAD_KEYSTORE_BASE64 (a PKCS12 keystore),
# ANDROID_UPLOAD_KEYSTORE_PASSWORD (store and key), OC_UPLOAD_KEY_ALIAS.
. "$(dirname "$0")/lib.sh"

[ $# -eq 2 ] || die "Usage: sign-bundle.sh <unsigned.aab> <signed.aab>"
: "${ANDROID_UPLOAD_KEYSTORE_BASE64:?}" "${ANDROID_UPLOAD_KEYSTORE_PASSWORD:?}" "${OC_UPLOAD_KEY_ALIAS:?}"
jarsigner=${JAVA_HOME:+$JAVA_HOME/bin/}jarsigner

keystore=$(umask 077 && mktemp)
trap 'rm -f "$keystore"' EXIT
base64 -d <<< "$ANDROID_UPLOAD_KEYSTORE_BASE64" > "$keystore"
unset ANDROID_UPLOAD_KEYSTORE_BASE64

"$jarsigner" -keystore "$keystore" -storetype PKCS12 \
  -storepass:env ANDROID_UPLOAD_KEYSTORE_PASSWORD -keypass:env ANDROID_UPLOAD_KEYSTORE_PASSWORD \
  -digestalg SHA-256 -signedjar "$2" "$1" "$OC_UPLOAD_KEY_ALIAS" > /dev/null
# The upload certificate is self-signed, which -strict would reject.
"$jarsigner" -verify "$2" | grep -q '^jar verified\.' || die "The signed bundle doesn't verify."
echo "Signed $2 with the upload key."
