<!--
SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>

SPDX-License-Identifier: Apache-2.0
-->

# AGENTS.md

Guidance for AI coding agents (Claude Code, Codex, Cursor, …) working in this
repository. Humans should start with [`CONTRIBUTING.md`](CONTRIBUTING.md); this
file is the same contract, plus the traps that have actually bitten agents here.

## What apollo is

A Scala 3 + [Kyo](https://getkyo.io) clone of NousResearch's
[Hermes agent](https://github.com/NousResearch/hermes-agent), cross-built for
the **JVM and Scala Native** from one shared codebase.

The core contract: **a real Hermes `config.yaml` / `.env` / home directory
loads unchanged.** Keys for subsystems apollo does not implement are parsed and
ignored, never rejected. `UpstreamConfigCompatSuite` loads the upstream
`cli-config.yaml.example` fixture and *is* that contract.

| Read this | When |
|---|---|
| [`ARCH.md`](ARCH.md) | before touching more than one package: layering, seams, how a turn flows |
| [`PARITY.md`](PARITY.md) | before adding a feature: what is built, what is deliberately not, and why |
| [`REPL_PARITY.md`](REPL_PARITY.md) | before touching slash commands in `apollo.cli` |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | commit style, licensing, CI, releases |

## Toolchain

- **JDK 25+ is required**, not 21: Kyo 1.0.0-RC6's macros are Java 25
  bytecode and run at compile time (JDK 21 fails with
  `UnsupportedClassVersionError … 69.0`).
- sbt 2.0.8, Scala 3.9.0, munit. Native also needs Clang/LLVM and OpenSSL 3.
- `.jvmopts` holds `-Xmx8G` for the Native link. Don't lower it locally; CI
  overrides it per runner via `NATIVE_JVMOPTS` (see `ci/native.sh`).

## Commands

```bash
sbt -batch "agentJVM/compile"
sbt -batch "agentJVM/testOnly *"                  # full JVM suite
sbt -batch "agentJVM/testOnly apollo.mcp.*"       # one package
sbt -batch "agentJVM/testOnly *NudgesSuite"       # one suite
sbt -batch "agentNative/nativeLink; agentNative/testOnly *"   # Native link + suite
ci/test-jvm.sh                                    # exactly what CI runs, with coverage
```

Three sbt 2 behaviours make a broken run look green. Know them:

1. **Bare `test` is `testQuick`.** It skips unchanged suites and can print
   `No tests to run`. Use `testOnly *` to force a full run.
2. **Separate arguments get concatenated.** `sbt "cmd1" "cmd2"` runs *one*
   command line, `cmd1 cmd2`, so `cmd2` never runs and the exit code is 0.
   Chain commands with `;` inside **one** quoted string, as above.
3. **The ActionCache can serve stale or empty outputs.** Symptoms: Native
   `Passed: Total 0` with `[success]`, a link error "Unreachable symbols …
   referenced from <OldClass>" after a rename, or scoverage
   `FileNotFoundException …/scoverage-data/…`. `clean` alone does **not** fix
   it while the sbt server is running:

   ```bash
   sbt -batch shutdown
   rm -rf ~/Library/Caches/sbt/v2/ac \
          target/out/native0.5/scala-3.9.0/apollo/{test-zinc,test-classes.sbtdir.zip,apollo-test}
   sbt -batch "agentNative/Test/compile"   # must print "compiling N Scala sources"
   ```

**Always read the test total.** A "Total 0" run is a failure, not a pass.
`ci/assert-tests-ran.sh <log>` checks this, and CI runs it after every suite.

## Layout

```
shared/src/main/scala/apollo/   # all but three production classes; compiles for JVM AND Native
jvm/src/main/…  native/src/main/…   # the platform seam: Platform{Editor,Search,Email} only
shared/src/test/                # munit suites, run on both platforms
jvm/src/test/                   # JVM-only suites (ArchUnit, java.security cross-checks)
ci/                             # every CI job is a script here, shared by both forges
```

## Architecture rules (enforced, not advisory)

`jvm/src/test/scala/apollo/arch/ArchitectureSuite.scala` checks the compiled
bytecode with ArchUnit. If it fails, fix the design, not the rule. Changing a
rule needs a deliberate reason and matching edits to `ARCH.md`.

- **Layering is a whitelist.** `Apollo.layers` lists each package and exactly
  what it may reach; a new package must be added to the table or the suite
  fails. No cycles. When a lower layer needs something from a higher one, add a
  **port trait owned by the consumer** and bind it in `apollo.cli` (see
  `ToolUi`, `CodePrompt`, `DelegateRunner`, `VisionRunner`).
- **Pure kernel.** `apollo.core` and `apollo.util` use no Kyo effects and no
  other apollo package. `apollo.core` is immutable: final fields, no mutable or
  `java.util.concurrent` collections.
- **Seams.** Wire adapters are reached only via `WireTransport.forMode`,
  built-in tools only via `ToolRegistry.dispatch`, chat connectors only from
  `Gateway.run`.
- **Output.** Only `apollo.cli` and `apollo.acp` write to stdout (ACP's stdout
  *is* its JSON-RPC wire). Everything else uses `kyo.Console` or
  `apollo.obs.ObsLog`. Only `apollo` and `apollo.cli` call `System.exit`.
- **Tests are named `*Suite`.** munit discovers them by name, so a `*Test`
  class silently never runs.

## Code style

Match the file you are in. The house idioms:

- **Kyo effects throughout.** Write signatures as `A < (Sync & Async)`. Failure
  is a value (`Abort[E]` / `Result`), never a generic exception. Use `Maybe`
  (`Present`/`Absent`), not `Option`, in Kyo-facing APIs.
- **No `scala.concurrent`** and no `Thread.sleep`. Use `Async.sleep`,
  `Fiber`, `Async.raceFirst` and `Meter`, so work stays interruptible.
- **JSON.** Externally owned wire formats (providers, MCP) are built and parsed
  as `Structure.Value` via `apollo.util.Jx`. Types apollo owns
  `derives Schema`.
- **Pure core, thin effectful shell.** Put the decision logic in a pure
  function and unit-test it (e.g. `Nudges.tick`, `ReplCommands.*`,
  `Banner`). The effectful caller only wires it up.
- **Cite upstream.** When mirroring Hermes, name the upstream file/function in
  the Scaladoc (``upstream `config.py::_deep_merge` ``) so drift can be
  audited later.
- Scala 3 indentation syntax, 2 spaces, `-Wunused:imports` is on. Keep
  comment density close to the surrounding code: explain *why*, not *what*.

### Kyo gotcha

An `if`/`match` whose branches are `X < S` and plain `()` infers a union and
loses `.andThen`. Make both branches pending (`Sync.defer(())`).

## Scala Native: keep `shared/` portable

Native is a first-class target. CI links it and runs the full suite on every
pipeline. In `shared/` **never** use:

- `java.security`, `javax.crypto`, `java.sql`, JLine, reflection, `java.awt`.
  Use `apollo.util.Crypto` (pure-Scala SHA-256, HMAC, base64, PKCE) instead.
- `java.time` formatters, `String.format` with `%,d`, or `java.util.Formatter`.
  Format by hand, as `StatusBar` does.

If a feature truly needs a JVM-only API, it goes behind a `Platform*` class
with a twin in **both** `jvm/src/main` and `native/src/main`. The suite
checks the twin exists. Cross-checks against JVM APIs (e.g. `MessageDigest`
vs `Crypto`) belong in `jvm/src/test` only.

Known to work on Native: `java.net.ServerSocket`, `URLEncoder`/`URLDecoder`,
kyo-http client and server (including websockets), `getResourceAsStream`
(resources are embedded).

**Terminal escape codes.** Write ANSI as the named constants (`Esc`, `Dim`,
`Rst`, …), never as raw ESC bytes or `\u001b` literals. Edit tools can turn the
escape into a real control byte, so the match fails or corrupts the file.
Check with `perl -ne 'print "$.\n" if /[\x00-\x08\x0e-\x1f]/' <file>`.

## Testing

- Every behaviour change ships with a test. Both platforms run `shared/src/test`.
- **End-to-end without mocks of our own code:** start a real localhost HTTP
  server that speaks the provider protocol (see `NudgeIntegrationSuite`,
  `AgentSteerSuite`) and drive a real `Agent` against it. kyo-http also has a
  websocket server for connector E2E tests (Discord, Slack).
- `Test / parallelExecution := false`: the MCP manager, tool registry and
  some connectors are process-global. A suite that touches them must call
  that object's `resetState()` in `beforeEach` (`McpManager`, `Discord`,
  `Slack`, `Teams`). Add a `resetState()` to any new process-global object.
- A test's wait-until-settled condition must be at least as strong as its
  assertions, or it will flake under load.
- In a test config whose model resolves via `providers:`, set
  `model: {streaming: false}` unless the mock speaks SSE (streaming defaults
  to true).
- Interactive TTY behaviour (line editor, live menus, async input) can't be
  verified headlessly. Test the pure parts, and name what still needs a manual
  terminal check in your report and in `REPL_PARITY.md`.

## Hermes parity

- **Ambiguous scope? Do what Hermes does.** Look at the upstream
  implementation and match its surface and behaviour. Don't invent a variant
  or stop to ask which one. Ask only when Hermes is silent or the platforms
  can't reconcile.
- A Hermes PR description states the intended contract well enough to mirror
  without reading the Python: `gh api repos/NousResearch/hermes-agent/pulls/<n>`.
  GitHub rate-limits rapid raw fetches of that repo (429), so pace them.
- Separate **drift** (behaviour apollo has and upstream changed → fix it) from
  **new upstream features** (→ record in `PARITY.md`, don't build silently).
  The re-audit recipe is in `PARITY.md` § *Method*.
- **Don't stub.** If something can't be built for real, leave it unbuilt and
  record why in `PARITY.md` / `REPL_PARITY.md`. Don't add a command that
  pretends.
- An `ApolloConfig` accessor with no production reference is a
  parse-and-ignore knob. That is fine, but it should be a known one.

## Docs

- Mermaid in `ARCH.md` is validated by parsing, not by eye. Avoid `;` inside
  `sequenceDiagram` messages/notes, reserved words (`alt`, `loop`, `end`,
  `note`, …) as participant ids, and `<…>` inside C4 labels.
- `README.md` is the source; `.github/README.md` is generated. After editing
  the README run `ci/readme-sync.sh` and commit both. CI runs `--check`.
- User-visible changes get a line under `## [Unreleased]` in `CHANGELOG.md`.

## Licensing (REUSE)

Every new file needs an SPDX header. Copy it from a neighbour (`//` for Scala,
`#` for shell/YAML/sbt, `<!-- -->` for Markdown). Files that can't carry a
comment go in `REUSE.toml`. CI runs `reuse lint`.

## CI

`.gitlab-ci.yml` (GitLab, primary) and `.github/workflows/ci.yml` (GitHub
mirror) are the **same pipeline**. Every job is a script in `ci/`. Adding,
removing or renaming a job means editing **both** files. Stages: REUSE lint +
README sync → compile → JVM tests with coverage → docker compose integration →
Native link + test → publish coverage → package/release on `v*` tags.

A GitLab failure at `get_sources` ("Could not resolve host") is a known
runner DNS flake, not your change. Retry the job. Don't add a blanket
`retry:` rule, because it would also hide real failures.

## Git workflow

- Subject line: imperative, with an area prefix, as in the history (`parity:`,
  `ci:`, `mcp:`, `gateway:`, `arch:`, `native:`, `docs:`). Put the *why* in the body.
- Multi-item work (a parity realignment, a feature group) goes on its own
  branch, not `main`.
- Before calling work done: `agentJVM/testOnly *` green with a non-zero
  total, the Native build still links if you touched `shared/`, and
  `ArchitectureSuite` passes.
