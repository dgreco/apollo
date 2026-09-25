<!--
SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>

SPDX-License-Identifier: Apache-2.0
-->

## What and why

<!-- What does this change, and why? Link the issue: "Closes #123". -->

## How it was tested

<!-- Suites run, JVM and/or Native, manual checks. -->

## Checklist

- [ ] Tests added or updated, and `sbt "agentJVM/testOnly *"` passes
- [ ] No JVM-only APIs in `shared/` (Native stays green)
- [ ] New files carry an SPDX header (`reuse lint` passes)
- [ ] `CHANGELOG.md` updated under `[Unreleased]` if users will notice
