#!/usr/bin/env bash
# Downloads the libXray Android AAR used by sleepygram.
#
# The AAR is not kept in git: it is a 51 MB prebuilt binary that is published
# with every libXray release. This script pins the exact release and checks
# its SHA-256 so a tampered or corrupted download never reaches a build.
set -euo pipefail

LIBXRAY_VERSION="${LIBXRAY_VERSION:-v26.11.9}"
LIBXRAY_SHA256="${LIBXRAY_SHA256:-4ddb0608ffee6e642ab9cda056c37df0b420a6b1e2be7292de33a1a3f3ca7a9a}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="$ROOT/TMessagesProj/libs"
DEST="$DEST_DIR/libXray.aar"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [[ -f "$DEST" ]]; then
    if echo "$LIBXRAY_SHA256  $DEST" | sha256sum -c --status -; then
        echo "libXray.aar already present and verified"
        exit 0
    fi
    echo "libXray.aar present but checksum mismatch, re-downloading" >&2
fi

URL="https://github.com/XTLS/libXray/releases/download/${LIBXRAY_VERSION}/libxray-android.zip"
echo "Downloading libXray ${LIBXRAY_VERSION}..."
curl -fsSL --retry 5 --retry-delay 2 -o "$TMP/libxray-android.zip" "$URL"
unzip -q -o "$TMP/libxray-android.zip" -d "$TMP"

echo "${LIBXRAY_SHA256}  ${TMP}/libxray-android/libXray.aar" | sha256sum -c --status - || {
    echo "libXray.aar checksum mismatch" >&2
    exit 1
}

mkdir -p "$DEST_DIR"
cp "$TMP/libxray-android/libXray.aar" "$DEST"
echo "libXray.aar installed to $DEST"
