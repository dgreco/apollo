<!--
SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>

SPDX-License-Identifier: Apache-2.0
-->

# Security policy

## Supported versions

apollo is pre-1.0. Only the latest release and `main` receive security fixes.

## Reporting a vulnerability

**Please do not open a public issue.** Report it privately by one of:

- a GitHub [private security advisory](https://github.com/dgreco/apollo/security/advisories/new),
- a GitLab issue marked **confidential** on
  <https://gitlab.davidgreco.it/dgreco/apollo/-/issues>, or
- email to <greco@acm.org>.

Include what an attacker can do, the steps to reproduce, and the version or
commit affected. You should get an acknowledgement within a week. Once a fix is
released, the advisory is published and you are credited unless you prefer
otherwise.

apollo runs model-chosen shell commands and file edits, and it holds provider
credentials, so issues in the approval flow, sandbox backends, credential
handling, the gateway's authorization checks, or MCP OAuth are especially
welcome.
