#!/usr/bin/env bash
# H3 (BENCHMARK-HARNESS): runs the macrobenchmark module if this test module
# exposes a connected task, and says so honestly when it does not.
#
# Why a script: the emulator runner sends the `script:` input to `sh -c` ONE LINE
# AT A TIME, so a multi-line `if` block in the workflow is executed line by line
# and dies with "Syntax error: end of file unexpected (expecting fi)".
set -uo pipefail

TASKS=$(./gradlew :benchmark:tasks --all --no-daemon --quiet || true)
# Prefer a *benchmark* variant: a plain connectedAndroidTest would measure the
# debuggable debug APK, and macrobenchmark refuses to report on it.
TASK=$(printf '%s\n' "$TASKS" | grep -Eo 'connected[A-Za-z]*Benchmark[A-Za-z]*AndroidTest' | head -1 || true)
if [ -z "$TASK" ]; then
  TASK=$(printf '%s\n' "$TASKS" | grep -Eo 'connected[A-Za-z]*AndroidTest' | head -1 || true)
fi

if [ -z "$TASK" ]; then
  echo "::notice::no connected benchmark task in :benchmark — the harness is configured and compiled by the build job; the numbers in docs/BENCHMARK.md still have to come from a physical device."
  exit 0
fi

echo "running :benchmark:$TASK"
# The EMULATOR suppression lives in benchmark/build.gradle.kts (defaultConfig)
# so configuration caching keeps working; DEBUGGABLE is intentionally not
# suppressed there.
if ./gradlew ":benchmark:$TASK" --no-daemon --stacktrace; then
  exit 0
fi

# A failed benchmark run on this emulator has so far only surfaced as
# "Process crashed" with no stack, so capture the device side of the story in
# the job log (the emulator still exists at this point).
echo "::group::logcat (crash buffer)"
adb logcat -d -b crash -t 300 || true
echo "::endgroup::"
echo "::group::logcat (app + benchmark)"
adb logcat -d -t 800 | grep -iE "siliksama|benchmark|AndroidRuntime|FATAL|beginning of" || true
echo "::endgroup::"
exit 1
