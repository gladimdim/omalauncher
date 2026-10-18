#!/bin/sh
# Fork: builds SD Launcher and installs it on a phone over adb, next to Omalauncher (com.gladimdim.sdlauncher).
# Usage: scripts/sd-deploy.sh [release]   ANDROID_SERIAL picks the phone when several are connected.
# A first install on a Xiaomi asks to confirm "Install via USB" on the phone.
set -e
cd "$(dirname "$0")/.."
variant=${1:-debug}
case "$variant" in
    debug) task=assembleDebug ;;
    release) task=assembleRelease ;;
    *) echo "usage: $0 [debug|release]" >&2; exit 2 ;;
esac
[ -f app/src/main/jniLibs/arm64-v8a/libproot.so ] || ./scripts/fetch-proot.sh
./gradlew ":app:$task"
adb install -r "app/build/outputs/apk/$variant/app-$variant.apk"
