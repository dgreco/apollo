#!/bin/sh
# Guard against a vacuous test run.
#
# sbt 2's ActionCache can serve an EMPTY `Test/compile` output. The test binary
# then contains no test classes at all, and the run prints
#   [info] Passed: Total 0, Failed 0, Errors 0, Passed 0
#   [success] elapsed time: 12 s
# which is green, exits 0, and has executed nothing. That was the real state of
# apollo's Native suite locally in Sept 2026 — for days. So a test job is only
# trustworthy if it also asserts that the test count is NOT zero.
#
# Usage: ci/assert-tests-ran.sh <sbt-output-log>
set -eu

log="${1:?usage - assert-tests-ran.sh <sbt-output-log>}"

# SBT_OPTS sets -Dsbt.color=true in CI, and sbt puts the escapes INSIDE the
# brackets: "[success]" is emitted as "[" ESC"[32m" "success" ESC"[0m" "]", so
# the literal string never appears in the log and a naive grep for it always
# fails. Strip ANSI escapes before matching. ESC comes from printf so this
# works with both GNU sed (CI) and BSD sed (macOS).
esc=$(printf '\033')
clean="${TMPDIR:-/tmp}/assert-tests-ran.$$.log"
trap 'rm -f "$clean"' EXIT
sed "s/${esc}\[[0-9;]*[a-zA-Z]//g" "$log" > "$clean"

# Checked before the [success] grep: the Native job runs `nativeLink` and the
# suite in one sbt invocation, so a link success can leave a [success] line in
# the log even when the tests that followed it failed.
if grep -qE 'Failed: Total [0-9]+, Failed [1-9]' "$clean"; then
  echo "ERROR - the suite reported test failures (see $log)" >&2
  exit 1
fi

if ! grep -q '\[success\]' "$clean"; then
  echo "ERROR - sbt did not report success (see $log)" >&2
  exit 1
fi

if ! grep -qE 'Passed: Total [1-9][0-9]*,' "$clean"; then
  echo "ERROR - the suite ran 0 tests but still reported success." >&2
  echo "        This is a stale sbt ActionCache serving an empty Test/compile," >&2
  echo "        not a passing build. Fix - sbt shutdown, then remove" >&2
  echo "        ~/Library/Caches/sbt/v2/ac (or \$CI_PROJECT_DIR/.sbt) and the" >&2
  echo "        project's test-zinc / test-classes.sbtdir.zip, and re-run." >&2
  exit 1
fi

count=$(grep -oE 'Passed: Total [0-9]+' "$clean" | tail -1 | awk '{print $3}')
echo "OK - $count tests ran"
