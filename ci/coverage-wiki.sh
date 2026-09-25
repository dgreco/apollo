#!/bin/sh

# SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
#
# SPDX-License-Identifier: Apache-2.0

# Publishes the coverage report as the GitLab wiki page the README's GitLab
# coverage badge links to (ci/readme-sync.sh). GitLab only.
#
# A wiki page, because this GitLab instance has no Pages: without the Pages
# daemon it will not display an HTML artifact in a browser at all ("the source
# could not be displayed because it is stored as a job artifact"), but it
# renders wiki Markdown natively at a stable URL. GitHub's counterpart is the
# `coverage-pages` job, which publishes the full HTML report to GitHub Pages.
#
# Needs WIKI_TOKEN: CI_JOB_TOKEN cannot write a wiki, so this takes a project
# access token with the write_repository scope AND the Developer role (Guest and
# Reporter are refused with a 403 however the scopes are ticked). Without it the
# job says so and exits 0 - the report is still attached to the job.
#
# Input: coverage/scoverage.xml from test:jvm. Output: Coverage.md (artifact).
# Same scheme as dgreco/jclaw's coverage-wiki job.
set -eu

python3 ci/coverage-summary.py markdown coverage/scoverage.xml "Coverage" > Coverage.md

if [ -z "${WIKI_TOKEN:-}" ]; then
  echo "coverage-wiki: not published - no WIKI_TOKEN set."
  echo
  echo "  CI_JOB_TOKEN cannot write a wiki, so this needs a project access token:"
  echo "    Settings > Access tokens      - role Developer, scope write_repository"
  echo "    Settings > CI/CD > Variables  - add it as WIKI_TOKEN, masked"
  echo
  echo "  The report itself is attached to this job as Coverage.md."
  exit 0
fi

# The token stays out of the URL and out of argv: a credential helper hands it
# to git only when git asks, so it never appears in the error messages git
# prints about the remote - which are exactly what this job needs to show.
remote="https://${CI_SERVER_HOST}/${CI_PROJECT_PATH}.wiki.git"
export WIKI_TOKEN
# Single quotes on purpose: git's shell expands $WIKI_TOKEN when it runs the helper.
# shellcheck disable=SC2016
helper='!f() { echo username=oauth2; echo "password=$WIKI_TOKEN"; }; f'

# The runner's DNS intermittently cannot resolve the GitLab host (see
# GET_SOURCES_ATTEMPTS in .gitlab-ci.yml). Those variables cover the runner's
# own fetches; a clone issued from a script has to retry for itself.
retry() {
  attempts="$1"; shift; i=1
  while :; do
    if "$@"; then return 0; fi
    if [ "$i" -ge "$attempts" ]; then return 1; fi
    echo "  attempt $i failed, retrying in $((i * 5))s"
    sleep $((i * 5)); i=$((i + 1))
  done
}
clone_wiki() { rm -rf wiki; git -c credential.helper="$helper" clone --quiet "$remote" wiki 2> clone.err; }
push_wiki()  { git -C wiki push origin "HEAD:$branch"; }

if retry 4 clone_wiki; then
  branch="$(git -C wiki symbolic-ref --short HEAD 2> /dev/null || echo main)"
elif grep -qi 'could not resolve host\|temporary failure in name resolution' clone.err; then
  # The DNS flake, four times over - reported and forgiven rather than dressed
  # up as a broken build. The next pipeline will publish.
  echo "coverage-wiki: the GitLab host would not resolve from this runner, after 4 tries."
  sed 's/^/    /' clone.err
  echo "  Coverage.md is attached to this job; the next pipeline will publish it."
  exit 0
elif grep -q '403' clone.err; then
  # Refused, not absent. Pushing cannot fix a permission.
  echo "coverage-wiki: WIKI_TOKEN is not allowed to read this wiki. git said:"
  sed 's/^/    /' clone.err
  echo
  echo "  The token needs BOTH scope write_repository AND role Developer or above."
  exit 1
else
  # Absent rather than refused: a wiki has no git repository until it has a
  # page, and GitLab creates one on the first push.
  echo "coverage-wiki: no wiki repository yet. git said:"
  sed 's/^/    /' clone.err
  echo "  If that is a 404, this is the first page and the push below creates the wiki."
  rm -rf wiki && mkdir -p wiki && git -C wiki init --quiet --initial-branch=main
  branch=main
fi

cp Coverage.md wiki/Coverage.md
git -C wiki config user.email "ci@${CI_SERVER_HOST}"
git -C wiki config user.name "apollo CI"
git -C wiki config credential.helper "$helper"
git -C wiki remote remove origin 2> /dev/null || true
git -C wiki remote add origin "$remote"
git -C wiki add Coverage.md
if git -C wiki diff --cached --quiet; then
  echo "coverage-wiki: unchanged since the last pipeline"
else
  git -C wiki commit --quiet -m "Coverage for ${CI_COMMIT_SHORT_SHA}"
  retry 4 push_wiki
  echo "coverage-wiki: published to ${CI_PROJECT_URL}/-/wikis/Coverage"
fi
