#!/bin/sh

# SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
#
# SPDX-License-Identifier: Apache-2.0

# Brings up docker-compose.yml and proves the stack is reachable - the shape a
# docker-backed integration test (e.g. apollo's `terminal.backend: docker`)
# builds on.
#
# Shared by GitLab CI (Docker-in-Docker) and GitHub Actions (the runner's own
# Docker daemon); only how the daemon is reached differs between the two.
set -eu

docker info > /dev/null
docker compose version   # fails fast if the compose plugin is missing

trap 'docker compose down -v || true' EXIT

docker compose up -d
docker compose ps
docker compose exec -T sandbox sh -c 'echo "hello from docker compose"'
