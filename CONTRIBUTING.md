<!--
SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>

SPDX-License-Identifier: Apache-2.0
-->

# Contributing to apollo

Thanks for your interest! apollo lives on two forges with the same code and the
same CI:

- **GitLab** (primary): <https://gitlab.davidgreco.it/dgreco/apollo>
- **GitHub** (mirror): <https://github.com/dgreco/apollo>

Issues and merge/pull requests are welcome on either.

By participating you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Before you start

- For anything beyond a small fix, open an issue first so we can agree on the
  approach.
- apollo is a clone of [Hermes](https://github.com/NousResearch/hermes-agent).
  When a feature's scope is ambiguous, the Hermes behaviour is the reference —
  see [`PARITY.md`](PARITY.md) and [`REPL_PARITY.md`](REPL_PARITY.md).
- [`ARCH.md`](ARCH.md) explains how the code fits together.

## Development setup

See [Requirements](README.md#requirements) in the README: JDK 25+, sbt 2.0.8,
and for the native binary OpenSSL 3 and a Clang/LLVM toolchain.

```bash
sbt agentJVM/compile
sbt "agentJVM/testOnly *"          # full JVM suite (bare `test` is testQuick)
sbt "agentNative/testOnly *"       # the suite on Scala Native
ci/test-jvm.sh                     # exactly what CI runs, with coverage → coverage/html/index.html
```

## Making a change

1. Branch from `main`.
2. Keep the change focused; match the style and comment density of the code
   around it.
3. Add or update tests. Both targets matter: Native is a first-class platform,
   so avoid JVM-only APIs in `shared/` (see the Native notes in `ARCH.md`).
4. Every new file needs an SPDX header — copy one from a neighbouring file:

   ```scala
   // SPDX-FileCopyrightText: 2026 Your Name <you@example.com>
   //
   // SPDX-License-Identifier: Apache-2.0
   ```

   Or let the [REUSE tool](https://reuse.software) add it:
   `reuse annotate --copyright "Your Name <you@example.com>" --license Apache-2.0 <files>`.
   Check with `reuse lint` — CI runs the same check.
5. Add a line under `## [Unreleased]` in [`CHANGELOG.md`](CHANGELOG.md) if
   users will notice the change.
6. Open a merge request (GitLab) or pull request (GitHub). CI must be green.

## Commit messages

Use a short imperative subject with an area prefix, as in the history:
`parity: …`, `ci: …`, `mcp: …`, `gateway: …`. Explain the *why* in the body.

## Licensing of contributions

apollo is licensed under the [Apache License 2.0](LICENSE). Unless you state
otherwise, any contribution you intentionally submit is licensed under the same
terms, as described in section 5 of the license, with no additional terms or
conditions.

## CI

`.gitlab-ci.yml` and `.github/workflows/ci.yml` are the same pipeline; each job
is a script in [`ci/`](ci/), so a script behaves identically on both forges.
When you add or remove a job, update **both** files.

Badges differ per forge: GitHub renders `.github/README.md`, GitLab the root
`README.md`. Both are generated from the root one by `ci/readme-sync.sh` — edit
`README.md`, run the script, commit both; CI fails if they drift.

Each forge's coverage badge opens the report its own pipeline published on
`main`: GitHub's `coverage-pages` job deploys the scoverage HTML report (and the
`badge.json` the badge reads) to <https://dgreco.github.io/apollo/>; GitLab's
`coverage-wiki` job writes it to the project wiki's *Coverage* page. The latter
needs a `WIKI_TOKEN` CI/CD variable — a project access token with role
Developer and scope `write_repository` — and only logs a notice without one.

## Releasing (maintainers)

1. Move the `[Unreleased]` entries in `CHANGELOG.md` under a new
   `## [x.y.z] - YYYY-MM-DD` heading, and set `version` in `build.sbt`.
2. Tag `vx.y.z` and push the tag to both forges. Each pipeline builds the
   assembly jar and the Linux native binary and publishes a release, with that
   CHANGELOG section as the notes.
