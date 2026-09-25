<!--
SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>

SPDX-License-Identifier: Apache-2.0
-->

# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

A release's section doubles as its release notes on GitLab and GitHub
(`ci/release-notes.sh`), so a `vX.Y.Z` tag needs a matching `## [X.Y.Z]` entry.

## [Unreleased]

### Added

- Published as open source under the Apache License 2.0, with SPDX headers on
  every file (REUSE-compliant, checked in CI).
- Identical CI/CD on GitLab and GitHub: license check, JVM tests with scoverage
  coverage, docker compose integration, Native link + tests, and tag-driven
  releases with the assembly jar and the Linux native binary.
- Contributing guide, code of conduct, security policy, and issue / merge
  request templates for both forges.
