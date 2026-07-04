#!/usr/bin/env bash
# Build the StreamVault-VLC *beta* APK in a containerized Android toolchain.
# beta = non-debuggable (fast UI), non-minified (no R8 crash risk), release-signed.
# JDK 17 + Android SDK 36 + Gradle 8.12 (AGP 8.10.1). SDK/Gradle caches persist on the host.
# Signing: keystore/ holds the stable release key; mounted into /src only for the build,
# so the repo checkout never contains the secret. Losing keystore/ = losing config on next update.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
[ -f "$DIR/keystore/streamvault.jks" ] || { echo "Missing keystore/streamvault.jks — cannot sign beta build"; exit 1; }
mkdir -p "$DIR/dist" /home/porras/.cache/android-sdk /home/porras/.gradle

docker run --rm \
  -v "$DIR":/src -w /src \
  -v "$DIR/keystore/streamvault.jks":/src/streamvault.jks:ro \
  -v "$DIR/keystore/keystore.properties":/src/keystore.properties:ro \
  -v /home/porras/.cache/android-sdk:/opt/android-sdk \
  -v /home/porras/.gradle:/root/.gradle \
  eclipse-temurin:17-jdk bash -c '
set -e
export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  apt-get update -qq && apt-get install -y -qq wget unzip >/dev/null 2>&1
  mkdir -p "$ANDROID_HOME/cmdline-tools"; cd /tmp
  wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O ct.zip
  unzip -q ct.zip -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKM" --licenses >/dev/null 2>&1 || true
"$SDKM" "platform-tools" "platforms;android-36" "build-tools;36.0.0" >/dev/null 2>&1
cd /src; chmod +x gradlew
./gradlew :app:assembleBeta --no-daemon -x test -x lint --console=plain
'

cp "$DIR/app/build/outputs/apk/beta/app-beta.apk" "$DIR/dist/streamvault-vlc-beta.apk"
echo "Built: $DIR/dist/streamvault-vlc-beta.apk"
