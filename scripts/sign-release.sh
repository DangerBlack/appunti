#!/usr/bin/env bash
# Signs a release APK with the Appunti release key using APK Signature Scheme v3 key rotation.
# signing/lineage.bin proves that the release key replaces the key used for the first
# builds, so new APKs install as updates over old ones (Android 9+; the app needs 10+).
#
# Usage: scripts/sign-release.sh <unsigned.apk> <signed.apk>
# Env:   APPUNTI_KEYSTORE, APPUNTI_KEYSTORE_PASSWORD, APPUNTI_KEY_ALIAS
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IN="$1"; OUT="$2"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"

"$BT/zipalign" -f -p 4 "$IN" "$OUT.aligned"
"$BT/apksigner" sign \
  --ks "$APPUNTI_KEYSTORE" --ks-pass env:APPUNTI_KEYSTORE_PASSWORD --ks-key-alias "$APPUNTI_KEY_ALIAS" \
  --lineage "$ROOT/signing/lineage.bin" --rotation-min-sdk-version 28 \
  --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true \
  --out "$OUT" "$OUT.aligned"
rm -f "$OUT.aligned" "$OUT.idsig"
"$BT/apksigner" verify --print-certs "$OUT" | grep 'SHA-256'
