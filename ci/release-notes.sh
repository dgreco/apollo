#!/bin/sh

# SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
#
# SPDX-License-Identifier: Apache-2.0

# Prints the CHANGELOG.md section for a release tag, used as the release notes
# on both GitLab and GitHub.
#
# Usage: ci/release-notes.sh v0.2.0
#
# CHANGELOG.md follows keepachangelog.com, so the section for tag v0.2.0 starts
# at "## [0.2.0]" and runs up to the next "## [" heading. A tag with no section
# fails the release rather than publishing empty notes.
set -eu

tag="${1:?usage - release-notes.sh <tag>}"
version="${tag#v}"

notes=$(awk -v v="$version" '
  index($0, "## [" v "]") == 1 { on = 1; next }
  on && /^## \[/               { exit }
  on                           { print }
' CHANGELOG.md)

if [ -z "$(printf '%s' "$notes" | tr -d '[:space:]')" ]; then
  echo "ERROR - CHANGELOG.md has no '## [$version]' section for tag $tag" >&2
  exit 1
fi

printf '%s\n' "$notes"
