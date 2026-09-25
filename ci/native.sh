#!/bin/sh

# SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
#
# SPDX-License-Identifier: Apache-2.0

# Links the Scala Native binary and runs the Native test suite.
#
# Shared by GitLab CI and GitHub Actions; both run it as root inside the same
# sbtscala/scala-sbt image, so the apt step below is identical on each forge.
#
# Environment:
#   NATIVE_JVMOPTS  optional, whitespace-separated JVM options that replace
#                   .jvmopts for this run (the working copy is a throwaway CI
#                   clone). Each forge sizes it to its own runner - the Native
#                   link is memory-bound, see the comments where it is set.
#
# Output: dist/apollo-linux-<arch>, the linked binary.
set -eu

if [ -n "${NATIVE_JVMOPTS:-}" ]; then
  # Word splitting is intended: one option per line in .jvmopts.
  # shellcheck disable=SC2086
  printf '%s\n' $NATIVE_JVMOPTS > .jvmopts
  echo "Using .jvmopts: $(tr '\n' ' ' < .jvmopts)"
fi

# Scala Native links a C/LLVM toolchain plus three native libraries:
#  * libssl-dev       - kyo-http's OpenSSL 3 TLS shim.
#  * liburing-dev     - kyo-net statically links -luring (-Wl,-Bstatic -luring)
#    for its Linux io_uring backend, and its C shim #includes <liburing.h>.
#    Without the dev package the shim compiles to an empty translation unit and
#    every kyo_uring_*/io_uring_* symbol is undefined at link time.
#  * libstdc++-11-dev - boringssl's whole-archive pulls in -lstdc++.
apt-get update -qq
apt-get install -y -qq clang llvm libstdc++-11-dev zlib1g-dev libssl-dev liburing-dev

log=native-test.log

# One quoted string with `;` - sbt 2.0.8 CONCATENATES separate argument strings
# into a single command line instead of running them in sequence, so
# `sbt cmd1 "cmd2"` would silently never run cmd2. Linking and testing in one
# invocation also pays the Native compile once.
status_file=$(mktemp)
{ sbt "agentNative/nativeLink; agentNative/testOnly *" 2>&1; echo $? > "$status_file"; } | tee "$log"
status=$(cat "$status_file")
rm -f "$status_file"

if [ "$status" -ne 0 ]; then
  # The heap is sized to a narrow window on the GitLab runner, so "how much was
  # actually free" is the first thing anyone debugging a failure here will want.
  echo "=== memory at failure ==="
  free -h || true
  exit "$status"
fi

# A vacuous Native run reports "Total 0" and still exits green.
ci/assert-tests-ran.sh "$log"

binary=$(find target/out -path '*/native0.5/*/apollo/apollo' -type f | head -1)
mkdir -p dist
cp "$binary" "dist/apollo-linux-$(uname -m)"
ls -l dist
