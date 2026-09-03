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

# $1 is a comma-separated list of test classes. Run each class in its OWN
# gradle/connected invocation: when several classes run in one instrumentation
# invocation, AGP aborts the whole batch with "Failed to instantiate test
# runner class" if a single class fails to init (R8/jar-merge quirk), instead
# of just reporting that one class. One-at-a-time keeps the others green and
# surfaces the real per-class result.
RC=0
IFS=','; for cls in $1; do
  [ -z "$cls" ] && continue
  echo "=== running $cls ==="
  ./gradlew --no-configuration-cache :app:connectedBazaarDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$cls" --no-daemon || RC=1
done
exit "$RC"
