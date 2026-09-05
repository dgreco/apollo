package apollo.tools

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain, Yaml}
import kyo.*

/** Terminal-backend command construction: local `sh -c` vs docker
  * `docker exec`, config plumbing, and the missing-container guard.
  */
class TerminalBackendSuite extends munit.FunSuite:

  private val wd = java.nio.file.Paths.get("/work")

  private def config(yaml: String, env: Map[String, String] = Map.empty): ApolloConfig =
    ApolloConfig(
      if yaml.isEmpty then Absent
      else Present(Yaml.parse(yaml).getOrElse(throw new AssertionError("yaml"))),
      EnvChain(env), ApolloPaths(java.nio.file.Paths.get("/tmp/term-test")))

  private def argv(cfg: ApolloConfig): List[String] =
    TerminalTools.backendCommand("echo hi", wd, cfg) match
      case Right(cmd) => cmd.args.toList
      case Left(err)  => fail(s"expected command, got: $err")

  test("local backend runs sh -c") {
    assertEquals(argv(config("")), List("sh", "-c", "echo hi"))
    assertEquals(argv(config("terminal: {backend: local}")), List("sh", "-c", "echo hi"))
  }

  test("docker backend execs into the configured container") {
    val cfg = config(
      """terminal:
        |  backend: docker
        |  docker:
        |    container: mybox
        |""".stripMargin)
    assertEquals(argv(cfg), List("docker", "exec", "mybox", "sh", "-c", "echo hi"))
  }

  test("docker backend adds workdir, env, and extra args in a stable order") {
    val cfg = config(
      """terminal:
        |  backend: docker
        |  docker:
        |    container: box
        |    workdir: /app
        |    env: {FOO: bar, ABC: xyz}
        |    extra_args: ["--user", "1000"]
        |""".stripMargin)
    assertEquals(argv(cfg), List(
      "docker", "exec", "--user", "1000", "-w", "/app",
      "-e", "ABC=xyz", "-e", "FOO=bar", // env sorted by key for determinism
      "box", "sh", "-c", "echo hi"))
  }

  test("container from TERMINAL_DOCKER_CONTAINER env; env values expand ${VAR}") {
    val cfg = config(
      """terminal:
        |  backend: docker
        |  docker:
        |    env: {TOKEN: "${MY_TOKEN}"}
        |""".stripMargin, env = Map("TERMINAL_DOCKER_CONTAINER" -> "envbox", "MY_TOKEN" -> "s3cr3t"))
    assertEquals(argv(cfg), List(
      "docker", "exec", "-e", "TOKEN=s3cr3t", "envbox", "sh", "-c", "echo hi"))
  }

  test("ssh backend runs ssh with the target and BatchMode") {
    val cfg = config(
      """terminal:
        |  backend: ssh
        |  ssh: {host: box.example.com, user: deploy}
        |""".stripMargin)
    assertEquals(argv(cfg), List(
      "ssh", "-T", "-o", "BatchMode=yes", "deploy@box.example.com", "sh", "-c", "echo hi"))
  }

  test("ssh backend adds port, key, workdir (cd), and extra args") {
    val cfg = config(
      """terminal:
        |  backend: ssh
        |  ssh:
        |    host: h
        |    user: u
        |    port: 2222
        |    key_path: /keys/id
        |    workdir: /srv/app
        |    extra_args: ["-o", "StrictHostKeyChecking=no"]
        |""".stripMargin)
    assertEquals(argv(cfg), List(
      "ssh", "-T", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no",
      "-p", "2222", "-i", "/keys/id", "u@h", "sh", "-c", "cd '/srv/app' && echo hi"))
  }

  test("ssh backend without a host is a clear error") {
    TerminalTools.backendCommand("echo hi", wd, config("terminal: {backend: ssh}")) match
      case Left(err) => assert(err.contains("terminal.ssh.host is not set"), err)
      case Right(_)  => fail("expected an error")
  }

  test("ssh host from TERMINAL_SSH_HOST env; no user → bare host") {
    val cfg = config("terminal: {backend: ssh}", env = Map("TERMINAL_SSH_HOST" -> "envhost"))
    assertEquals(argv(cfg), List("ssh", "-T", "-o", "BatchMode=yes", "envhost", "sh", "-c", "echo hi"))
  }

  test("docker backend without a container is a clear error") {
    TerminalTools.backendCommand("echo hi", wd, config("terminal: {backend: docker}")) match
      case Left(err) => assert(err.contains("terminal.docker.container is not set"), err)
      case Right(_)  => fail("expected an error")
  }

  test("unknown backend is rejected") {
    TerminalTools.backendCommand("echo hi", wd, config("terminal: {backend: modal}")) match
      case Left(err) => assert(err.contains("unsupported terminal.backend 'modal'"), err)
      case Right(_)  => fail("expected an error")
  }
end TerminalBackendSuite
