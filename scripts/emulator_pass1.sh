#!/usr/bin/env bash
# Pass 1 — fresh install + screenshot capture for the visual regression gate.
#
# This lives in its own file on purpose: reactivecircus/android-emulator-runner
# executes the workflow `script:` line-by-line through `sh -c`, which breaks any
# multiline `for/if/until` and drops shell state (GRADLE_RC, T*_PID, ...) across
# lines. Running a single `bash file` sidesteps both problems entirely.
set -u

ADB="adb -s emulator-5554"

echo "== wait for device + full boot =="
$ADB wait-for-device || true
until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
$ADB shell input keyevent 82 || true

echo "== install bazaar debug =="
./gradlew :app:installBazaarDebug --no-daemon

echo "== resolve app package =="
APP_PKG=""
for i in $(seq 1 24); do
  APP_PKG=$($ADB shell pm list packages | grep siliksama | head -1 | cut -d: -f2)
  [ -n "$APP_PKG" ] && break
  sleep 5
done
APP_PKG="${APP_PKG:-NONE}"
echo "installed package: $APP_PKG"

if [ "$APP_PKG" != "NONE" ]; then
  echo "== warmup launch (component) =="
  # Component launch matches the .debug applicationId the connected tests
  # target; a bare `am start -p` fails with "Activity not started" on the
  # `.debug` app, which leaves the screenshot tests with an empty UI.
  $ADB shell am start -W -n "$APP_PKG/com.compressly.MainActivity" || true
  sleep 120
  if $ADB shell ps -A 2>/dev/null | grep -q "$APP_PKG"; then
    echo "warmup: app survived its first launch"
  else
    echo "warmup: app process died after first launch - crash log:"
    $ADB logcat -d -b crash -t 200 || true
  fi
  $ADB shell am force-stop "$APP_PKG" || true
else
  echo "warmup: package never appeared"
  $ADB shell pm list packages || true
fi

echo "== capture frames =="
mkdir -p shots-run/caps shots-run/CompresslyScreenshots
: > pass1-console.log
(tail -f pass1-console.log) & TAIL_PID=$!

(
  while :; do
    ts=$(date +%s%3N)
    f="shots-run/caps/frame-$ts.png"
    if $ADB exec-out screencap -p > "$f" 2>/dev/null; then
      $ADB shell "echo $ts > /data/local/tmp/compressly_frame_ts" 2>/dev/null
      echo "COMPRESSLY-FRAME $ts $f" >> pass1-console.log
    fi
    sleep 1
  done
) & CAP_PID=$!

echo "== run screenshot regression test =="
./gradlew :app:connectedBazaarDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.compressly.ScreenshotRegressionTest \
  --no-daemon >> pass1-console.log 2>&1
GRADLE_RC=$?

kill "$CAP_PID" "$TAIL_PID" 2>/dev/null
wait "$CAP_PID" "$TAIL_PID" 2>/dev/null

echo "== collect result =="
XML=$(find app/build/outputs/androidTest-results -name 'TEST-*.xml' 2>/dev/null | head -1)
echo "test xml: ${XML:-none}"
grep -o 'COMPRESSLY-WINDOW [^<]*' "$XML" 2>/dev/null | head -6
python3 scripts/pick_frames.py "$XML" shots-run/caps shots-run/CompresslyScreenshots
PICK_RC=$?
$ADB logcat -d -b crash -t 300 2>/dev/null | tee pass1-crash.log || true

echo "== captured screenshots =="
ls -la shots-run/CompresslyScreenshots || true

R=0
[ "$GRADLE_RC" -ne 0 ] && R=1
[ "$PICK_RC" -ne 0 ] && R=1
echo "pass1 rc=$R (gradle=$GRADLE_RC pick=$PICK_RC)"
exit "$R"
