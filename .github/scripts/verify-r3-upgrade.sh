#!/usr/bin/env bash
# Isolated CI emulator only. No real-user data or device is used.
set -euo pipefail
PKG=me.rerere.rikkahub.fork
OUT="$PWD/upgrade-results"
mkdir -p "$OUT"
NEW=$(find "$PWD/upgrade-apk" -name '*universal*.apk' -print -quit)
test -n "$NEW"
adb wait-for-device
adb root
adb wait-for-device
test "$(adb shell getprop ro.debuggable | tr -d '\r')" = 1
adb install -r "$PWD/r2.apk" | tee "$OUT/old-install.txt"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > "$OUT/old-launch.txt" 2>&1
sleep 5
adb shell am force-stop "$PKG"
adb shell "mkdir -p /data/user/0/$PKG/files; echo r3-upgrade-preserved > /data/user/0/$PKG/files/.r3-upgrade-marker"
adb shell dumpsys package "$PKG" | grep -E 'versionCode=|versionName=' > "$OUT/before.txt"
grep -q 'versionCode=3000049' "$OUT/before.txt"
adb install -r "$NEW" | tee "$OUT/install.txt"
grep -q 'Success' "$OUT/install.txt"
adb shell dumpsys package "$PKG" | grep -E 'versionCode=|versionName=' > "$OUT/after.txt"
grep -q 'versionCode=3000050' "$OUT/after.txt"
grep -q 'versionName=3.1.1-r3' "$OUT/after.txt"
adb shell "cat /data/user/0/$PKG/files/.r3-upgrade-marker" | tr -d '\r' > "$OUT/marker.txt"
grep -qx r3-upgrade-preserved "$OUT/marker.txt"
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > "$OUT/new-launch.txt" 2>&1
sleep 10
adb shell pidof "$PKG" | tee "$OUT/pid.txt"
test -s "$OUT/pid.txt"
adb exec-out screencap -p > "$OUT/first-launch.png"
adb shell uiautomator dump /sdcard/r3-window.xml > /dev/null
adb pull /sdcard/r3-window.xml "$OUT/window.xml"
adb logcat -d -s AndroidRuntime:E > "$OUT/android-runtime.txt"
if grep -q 'FATAL EXCEPTION' "$OUT/android-runtime.txt"; then cat "$OUT/android-runtime.txt"; exit 1; fi
printf '3.1.1-r2 -> 3.1.1-r3 signed upgrade: install, version, sandbox marker and cold launch passed.\n' | tee "$OUT/result.txt"
