#!/usr/bin/env bash
# H3 (BENCHMARK-HARNESS): runs the macrobenchmark module if this test module
# exposes a connected task, and says so honestly when it does not.
#
# Why a script: the emulator runner sends the `script:` input to `sh -c` ONE LINE
# AT A TIME, so a multi-line `if` block in the workflow is executed line by line
# and dies with "Syntax error: end of file unexpected (expecting fi)".
set -uo pipefail

TASKS=$(./gradlew :benchmark:tasks --all --no-daemon --quiet || true)
TASK=$(printf '%s\n' "$TASKS" | grep -Eo 'connected[A-Za-z]*AndroidTest' | head -1 || true)

if [ -z "$TASK" ]; then
  echo "::notice::no connected benchmark task in :benchmark — the harness is configured and compiled by the build job; the numbers in docs/BENCHMARK.md still have to come from a physical device."
  exit 0
fi

echo "running :benchmark:$TASK"
./gradlew ":benchmark:$TASK" --no-daemon --stacktrace
