#!/usr/bin/env bash
# Pass 1 — fresh install + screenshot capture for the visual-regression gate.
#
# This lives in its own file on purpose: reactivecircus/android-emulator-runner
# executes the workflow `script:` line-by-line through `sh -c`, which breaks any
# multiline `for/if/until` and drops shell state across lines. Running a single
# `bash file` sidesteps both problems.
#
# CAPTURE STRATEGY (decoupled from UiAutomator):
#   UiAutomator reports zero accessibility windows on some CI emulators even
#   when the UI is genuinely rendered, so the old ScreenshotRegressionTest
#   (which drove the app by tapping accessibility nodes and bracketing a hold
#   with a shell-property handshake) would fail with "windows seen: []". Instead
#   we drive the app directly through a debug intent (EXTRA_SNAP_SCREEN) that
#   jumps to a top-level screen, and capture one full-screen frame per screen.
set -u

ADB="adb -s emulator-5554"
PKG="ir.siliksama.hajmino.debug"   # bazaarDebug applicationId on CI
PKG_LENIENT="ir.siliksama.hajmino"
OUT="shots-run/CompresslyScreenshots"
BIGEXTRA="com.compressly.extra.SNAP_SCREEN"
SKIPEXTRA="com.compressly.extra.SNAP_SKIP_ONBOARDING"
ACTIVITY="com.compressly.MainActivity"

wait_boot() {
  $ADB wait-for-device || true
  until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
  $ADB shell input keyevent 82 || true
}

# Launch the app to a specific screen and capture a stable frame.
# $1 = snap route ("onboarding" | "home" | "history")
# $2 = capture file name
capture_screen() {
  local route="$1"; local file="$2"
  local skip="false"
  [ "$route" = "onboarding" ] || skip="true"
  $ADB shell am force-stop "$PKG" 2>/dev/null || true
  $ADB shell am force-stop "$PKG_LENIENT" 2>/dev/null || true
  $ADB shell pm clear "$PKG" 2>/dev/null || true
  $ADB shell pm clear "$PKG_LENIENT" 2>/dev/null || true
  # Launch, settle, then RE-focus the app (no HOME, no keyevent that can
  # trigger the task switcher) so a stray system dialog — or a different app
  # that a keyevent would have brought to front — cannot end up on screen.
  $ADB shell am start -W \
    -n "$PKG/$ACTIVITY" \
    --es "$BIGEXTRA" "$route" \
    --ez "$SKIPEXTRA" "$skip" || true
  sleep 20
  $ADB shell am start \
    -n "$PKG/$ACTIVITY" \
    --es "$BIGEXTRA" "$route" \
    --ez "$SKIPEXTRA" "$skip" || true
  sleep 5
  # Capture a burst of frames and pick the LARGEST (most content) one — a
  # dialog/splash/launcher frame is small/flat, the real app screen is bigger.
  local best=""; local bestsz=0; local i
  for i in 1 2 3; do
    local tmp="$OUT/.frame.png"
    $ADB exec-out screencap -p > "$tmp" 2>/dev/null
    local s
    s=$(wc -c < "$tmp" 2>/dev/null || echo 0)
    if [ "$s" -gt "$bestsz" ]; then bestsz=$s; cp "$tmp" "$OUT/$file"; fi
    sleep 1
  done
  rm -f "$OUT/.frame.png"
  echo "captured $file -> ${bestsz} bytes (route=$route)"
}

echo "== install bazaar debug =="
./gradlew :app:installBazaarDebug --no-daemon

echo "== wait for boot =="
wait_boot

echo "== resolve installed package =="
RESOLVED=$($ADB shell pm list packages | grep siliksama | head -1 | cut -d: -f2)
RESOLVED="${RESOLVED:-$PKG}"
echo "installed package: $RESOLVED"
# Use the resolved package for launch.
PKG="$RESOLVED"
ACT="com.compressly.MainActivity"

mkdir -p "$OUT"

echo "== capture onboarding =="
capture_screen "onboarding" "01_onboarding.png"

echo "== capture home =="
capture_screen "home" "02_home.png"

echo "== capture history =="
capture_screen "history" "03_history.png"

echo "== captured screenshots =="
ls -la "$OUT"
$ADB logcat -d -b crash -t 300 2>/dev/null | tee pass1-crash.log || true

# Fail only if we failed to produce the three screenshots.
RC=0
for f in 01_onboarding.png 02_home.png 03_history.png; do
  [ -s "$OUT/$f" ] || { echo "MISSING $f"; RC=1; }
done
echo "pass1 rc=$RC"
exit "$RC"
