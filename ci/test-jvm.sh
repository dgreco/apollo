#!/bin/sh

# SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
#
# SPDX-License-Identifier: Apache-2.0

# Runs the JVM test suite under scoverage and collects the reports.
#
# Shared by GitLab CI (.gitlab-ci.yml) and GitHub Actions
# (.github/workflows/ci.yml) so both forges run exactly the same thing.
#
# Outputs (stable paths, independent of the Scala version in target/):
#   jvm-test.log            full sbt output
#   coverage/cobertura.xml  Cobertura report (GitLab MR coverage)
#   coverage/scoverage.xml  scoverage XML report (coverage wiki page, badge.json)
#   coverage/html/          browsable HTML report (GitHub Pages)
#   test-reports/           JUnit XML, one file per suite
#
# It also prints a single "Coverage: NN.NN%" line; GitLab's `coverage:` regex
# reads it to drive the coverage badge.
#
# Local use: `ci/test-jvm.sh`.
set -eu

log=jvm-test.log

# A fresh scoverage data directory for every run. The instrumenting compiler
# writes its statement metadata (scoverage.coverage) there as a side effect,
# and the instrumented classes append measurements to it at test time. sbt 2's
# ActionCache replays the compiled classes but not that side effect, so a
# cache hit (a second pipeline on the same commit, via GitLab's cached .sbt/)
# left classes pointing at a directory that no longer existed and every suite
# died with FileNotFoundException .../scoverage-data/... (pipeline 389). The
# directory is baked into the compiler options, so a unique path is a cache
# miss by construction: coverage always recompiles, and always has its data.
data_dir="$PWD/target/scoverage-data-$(date +%s)-$$"

# Bare `test` == testQuick; `testOnly *` forces the whole suite to run. `tee`
# would mask sbt's exit code (no pipefail in POSIX sh), so it is captured in a
# side file: a failing coverageReport after green tests must still fail the job.
status_file=$(mktemp)
{ sbt "set agentJVM / coverageDataDir := file(\"$data_dir\"); coverage; agentJVM/testOnly *; agentJVM/coverageReport" 2>&1; echo $? > "$status_file"; } | tee "$log"
status=$(cat "$status_file")
rm -f "$status_file"

# Collect the JUnit reports even when the run failed: they are what shows WHICH
# test broke in the GitLab MR widget.
rm -rf test-reports coverage
mkdir -p test-reports coverage
find target/out/jvm -path '*/test-reports/*.xml' -exec cp {} test-reports/ \;

if [ "$status" -ne 0 ]; then
  echo "ERROR - sbt exited with status $status (see $log)" >&2
  exit "$status"
fi

# Asserts a non-zero test count (see the script's own header for why).
ci/assert-tests-ran.sh "$log"

# sbt-scoverage writes its reports under coverageDataDir, i.e. this run's
# $data_dir. Only look there: any other coverage-report/ under target/ is a
# stale leftover from an earlier run.
cp "$data_dir/coverage-report/cobertura.xml" coverage/cobertura.xml
cp "$data_dir/scoverage-report/scoverage.xml" coverage/scoverage.xml
cp -R "$data_dir/scoverage-report" coverage/html

# scoverage prints "[info] Statement coverage.: 68.83%"; re-emit it without the
# sbt prefix/colour so the forge-side regex is trivial.
esc=$(printf '\033')
pct=$(sed "s/${esc}\[[0-9;]*[a-zA-Z]//g" "$log" | grep -oE 'Statement coverage\.*: [0-9]+\.[0-9]+%' | tail -1 | grep -oE '[0-9]+\.[0-9]+')
echo "Coverage: ${pct}%"
