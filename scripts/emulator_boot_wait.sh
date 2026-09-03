#!/usr/bin/env bash
# Wait for the emulator to fully boot, then run the given gradle test filter.
# Keeping this in a file (not the workflow `script:`) avoids the
# reactivecircus line-by-line `sh -c` problem with multiline loops.
set -u
ADB="adb -s emulator-5554"
$ADB wait-for-device || true
until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
$ADB shell input keyevent 82 || true
# Clear app data for a deterministic journey.
$ADB shell pm clear ir.siliksama.hajmino 2>/dev/null || \
  $ADB shell pm clear ir.siliksama.hajmino.debug 2>/dev/null || true
echo "boot complete; installing + running: $*"
./gradlew :app:installBazaarDebug --no-daemon || true
exec ./gradlew --no-configuration-cache :app:connectedBazaarDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class="$1" --no-daemon
