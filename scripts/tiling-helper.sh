#!/bin/sh
# Fork: starts the tiling desktop's helper on a device over adb. It runs as the shell user, which may create the
# displays the tiles are and send them input (tiling/.../helper/HelperMain.kt); the launcher itself may not.
# Run it again after a reboot and after installing a new build. ANDROID_SERIAL picks the device.
# Usage: scripts/tiling-helper.sh [stop]
set -e
pkg=com.gladimdim.sdlauncher
main=dev.gladimdim.tiling.helper.HelperMain
# The brackets keep pkill from matching, and killing, the shell that runs it.
adb shell "pkill -f 'tiling.helper.[H]elperMain' || true"
[ "$1" = stop ] && exit 0
apk=$(adb shell pm path "$pkg" | head -1 | cut -d: -f2 | tr -d '\r')
[ -n "$apk" ] || { echo "$pkg is not installed" >&2; exit 1; }
adb shell "CLASSPATH=$apk setsid app_process /system/bin $main $pkg >/data/local/tmp/tiling-helper.log 2>&1 </dev/null &"
sleep 1
adb shell "cat /data/local/tmp/tiling-helper.log"
