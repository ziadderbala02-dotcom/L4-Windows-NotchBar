#!/usr/bin/env bash
# Takes pictures of the notch on an emulator, across camera shapes and screen sizes, so each
# build can be checked for phones we don't have. Run by .github/workflows/android.yml.
# Usage: shots.sh path/to/app.apk out_dir
set -u
APK=$1
OUT=$2
PKG=com.l4.notchlive
mkdir -p "$OUT"
adb root >/dev/null 2>&1; sleep 3
adb install -r "$APK"
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell settings put global window_animation_scale 1

CUTOUTS="none hole corner double tall waterfall"
SIZES="1080x2400:420 720x1600:320 1600x2560:360"

demo() {
  adb shell am start-foreground-service -n $PKG/.LiveService -a demo --es kind "$1" >/dev/null
}

for size in $SIZES; do
  res=${size%%:*}; dpi=${size##*:}
  adb shell wm size "$res"; adb shell wm density "$dpi"
  for cut in $CUTOUTS; do
    if [ "$cut" = none ]; then
      for o in $(adb shell cmd overlay list | grep -o 'com.android.internal.display.cutout.emulation[.a-z0-9_]*'); do adb shell cmd overlay disable "$o" >/dev/null 2>&1; done
    else
      adb shell cmd overlay enable-exclusive --category "com.android.internal.display.cutout.emulation.$cut" >/dev/null 2>&1 || continue
    fi
    sleep 3
    adb shell am force-stop $PKG
    name="${res}_${cut}"
    demo none; sleep 4
    adb exec-out screencap -p > "$OUT/${name}_1compact.png"
    demo goal; sleep 4
    adb exec-out screencap -p > "$OUT/${name}_2goal.png"
    demo info; sleep 4
    adb exec-out screencap -p > "$OUT/${name}_3tap.png"
    demo grid; sleep 4
    adb exec-out screencap -p > "$OUT/${name}_4shortcuts.png"
    adb shell am force-stop $PKG
    demo idle; sleep 3
    adb exec-out screencap -p > "$OUT/${name}_5idle.png"
  done
done
adb shell wm size reset; adb shell wm density reset
ls -la "$OUT"
