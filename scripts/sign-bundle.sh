#!/usr/bin/env bash
# Signs a release App Bundle (.aab) for Google Play with the Appunti key (used as upload key).
# Play re-signs the APKs it delivers with the app signing key registered in Play App Signing.
#
# Usage: scripts/sign-bundle.sh <unsigned.aab> <signed.aab>
# Env:   APPUNTI_KEYSTORE, APPUNTI_KEYSTORE_PASSWORD, APPUNTI_KEY_ALIAS
set -euo pipefail

IN="$1"; OUT="$2"
jarsigner -keystore "$APPUNTI_KEYSTORE" -storepass:env APPUNTI_KEYSTORE_PASSWORD \
  -sigalg SHA256withRSA -digestalg SHA-256 -signedjar "$OUT" "$IN" "$APPUNTI_KEY_ALIAS" >/dev/null
jarsigner -verify "$OUT" >/dev/null
keytool -printcert -jarfile "$OUT" | grep 'SHA256:'
