#!/bin/sh

# SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
#
# SPDX-License-Identifier: Apache-2.0

# Writes the two forge-flavoured READMEs from one source of prose.
#
# GitHub renders `.github/README.md` in preference to the root one; GitLab
# renders only the root one. That is the only way the same badge row can differ
# by where it is being read, and it has to: each forge's copy must show that
# forge's own pipeline and open the coverage report that forge published, never
# the other's. (A GitHub reader shown GitLab badges gets "project not found"
# while the GitLab project is private, and a link they cannot open.)
#
# One body, two flavours. What differs is the badge block between the markers,
# plus the `../` every relative link needs in the GitHub copy - it lives one
# directory down, so `LICENSE` there would mean `.github/LICENSE`.
#
#   ci/readme-sync.sh           rewrite both files (edit README.md, then run this)
#   ci/readme-sync.sh --check   fail if either is stale (what both pipelines run)
#
# Same scheme as dgreco/jclaw's scripts/readme-sync.sh.
set -eu
export LC_ALL=C

root="$(cd "$(dirname "$0")/.." && pwd)"
source_file="$root/README.md"
github_file="$root/.github/README.md"
check=""
[ "${1:-}" = "--check" ] && check=1

GITLAB="https://gitlab.davidgreco.it/dgreco/apollo"
GITHUB="https://github.com/dgreco/apollo"

# The coverage reports, one per forge, each published by that forge's own
# pipeline. GitHub: the scoverage HTML report on Pages (`coverage-pages` in
# .github/workflows/ci.yml), whose badge.json feeds the shields.io badge. GitLab:
# a wiki page (`coverage-wiki` in .gitlab-ci.yml), because an instance without
# Pages will not display an HTML artifact in a browser at all; the badge number
# itself is GitLab's own, from the test job's `coverage:` regex. If Pages is
# ever enabled there, point GITLAB_REPORT at it - the `pages` job already
# publishes and un-skips itself.
GITHUB_REPORT="https://dgreco.github.io/apollo/"
GITLAB_REPORT="$GITLAB/-/wikis/Coverage"

badges() {   # $1 = github | gitlab
  if [ "$1" = github ]; then
    echo "[![build](https://img.shields.io/github/actions/workflow/status/dgreco/apollo/ci.yml?branch=main&label=build&logo=github)]($GITHUB/actions/workflows/ci.yml?query=branch%3Amain)"
    echo "[![coverage](https://img.shields.io/endpoint?url=https%3A%2F%2Fdgreco.github.io%2Fapollo%2Fbadge.json)]($GITHUB_REPORT)"
  else
    echo "[![pipeline]($GITLAB/badges/main/pipeline.svg)]($GITLAB/-/pipelines?ref=main)"
    echo "[![coverage]($GITLAB/badges/main/coverage.svg)]($GITLAB_REPORT)"
  fi
  cat <<'EOF'
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![REUSE compliant](https://img.shields.io/badge/REUSE-compliant-brightgreen)](https://reuse.software)
[![Scala 3.9](https://img.shields.io/badge/scala-3.9-DC322F?logo=scala&logoColor=white)](https://www.scala-lang.org)
[![Platforms: JVM | Native](https://img.shields.io/badge/platforms-JVM%20%7C%20Native-informational)](#building)
EOF
}

# Replaces whatever sits between the markers with this forge's badges.
flavour() {  # $1 = forge, reads the source on stdin
  # Through the environment, not -v: awk's -v does not take embedded newlines.
  BADGES="$(badges "$1")" awk '
    /<!-- BADGES:START -->/ { print; print ENVIRON["BADGES"]; skip = 1; next }
    /<!-- BADGES:END -->/   { skip = 0 }
    !skip { print }
  '
}

# `.github/README.md` sits one directory below the paths the body was written
# against. perl, not sed: leaving absolute links, anchors and mailto alone
# needs a negative lookahead, which neither BSD nor GNU sed has.
descend() {
  perl -pe 's{\]\((?!https?://|#|\.\./|mailto:)(?:\./)?}{](../}g'
}

if ! grep -q '<!-- BADGES:START -->' "$source_file"; then
  echo "readme-sync: README.md has no <!-- BADGES:START --> marker" >&2
  exit 1
fi

gitlab_out="$(flavour gitlab < "$source_file")"
github_out="$(printf '%s\n' "<!-- Generated from ../README.md by ci/readme-sync.sh. Edit that one. -->" \
              && flavour github < "$source_file" | descend)"

if [ -n "$check" ]; then
  status=0
  printf '%s\n' "$gitlab_out" | diff -q - "$source_file" > /dev/null \
    || { echo "readme-sync: README.md is stale - run ci/readme-sync.sh" >&2; status=1; }
  printf '%s\n' "$github_out" | diff -q - "$github_file" > /dev/null 2>&1 \
    || { echo "readme-sync: .github/README.md is stale - run ci/readme-sync.sh" >&2; status=1; }
  [ $status -eq 0 ] && echo "readme-sync: both READMEs are current"
  exit $status
fi

mkdir -p "$(dirname "$github_file")"
printf '%s\n' "$gitlab_out" > "$source_file"
printf '%s\n' "$github_out" > "$github_file"
echo "readme-sync: wrote README.md (GitLab) and .github/README.md (GitHub)"
