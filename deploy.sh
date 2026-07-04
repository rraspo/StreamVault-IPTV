#!/usr/bin/env bash
# Install the StreamVault-VLC beta build on a Google TV device via ADB.
# Usage: ./deploy.sh <streamer-ip>   (e.g. ./deploy.sh 192.168.0.222)
# The streamer needs ADB/network debugging enabled and the host's adb key trusted.
set -euo pipefail
IP="${1:?usage: deploy.sh <streamer-ip>}"
DIR="$(cd "$(dirname "$0")" && pwd)"
APK="$DIR/dist/streamvault-vlc-beta.apk"
[ -f "$APK" ] || { echo "No APK — run ./build.sh first"; exit 1; }

docker run --rm --network host \
  -v /home/porras/.android-adb:/root/.android \
  -v "$DIR/dist":/dist \
  alpine sh -c "
apk add --no-cache android-tools >/dev/null 2>&1
adb connect ${IP}:5555 >/dev/null 2>&1; sleep 1
# beta build installs under com.streamvault.app.beta, stable-signed (same key as the
# earlier ExoPlayer patch-fork), so install -r updates in place and keeps app config.
echo 'installing/updating beta build...'
adb install -r -g /dist/streamvault-vlc-beta.apk 2>&1 | tail -2 || {
  echo 'reinstall failed (signer/version change) - clean install'
  adb uninstall com.streamvault.app.beta 2>/dev/null | tail -1
  adb install -g /dist/streamvault-vlc-beta.apk 2>&1 | tail -2
}
echo -n 'installed: '; adb shell dumpsys package com.streamvault.app.beta 2>/dev/null | grep -m1 versionName | tr -d ' \r'; echo
adb shell monkey -p com.streamvault.app.beta -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
"
