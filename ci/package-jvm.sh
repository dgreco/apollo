#!/bin/sh

# SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
#
# SPDX-License-Identifier: Apache-2.0

# Builds the self-contained JVM jar (sbt-assembly) for a release.
#
# Shared by GitLab CI and GitHub Actions.
#
# Output: dist/apollo.jar - run with `java -jar apollo.jar` (JDK 25+).
set -eu

sbt agentJVM/assembly

jar=$(find target/out/jvm -name apollo.jar -type f | head -1)
mkdir -p dist
cp "$jar" dist/apollo.jar
ls -l dist
