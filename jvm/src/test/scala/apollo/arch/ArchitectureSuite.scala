package apollo.arch

import com.tngtech.archunit.core.domain.JavaClass.Predicates.{resideInAPackage, resideOutsideOfPackages}
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.{ClassFileImporter, ImportOption, Location}
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.{classes, fields, noClasses}
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.GeneralCodingRules
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import scala.jdk.CollectionConverters.*

/** The executable form of `ARCH.md`.
  *
  * ArchUnit reads JVM bytecode, so these run on the JVM cross-build only — but
  * that build compiles the whole of `shared/` plus the three JVM halves of the
  * platform seam, i.e. every production class apollo has except the Native
  * `Platform*` trio. A rule that holds here holds for the Native binary too,
  * and the portability rule below is precisely what keeps that true.
  *
  * Rules are stated as data (the layer table) wherever they are really a
  * table, so the architecture can be read off this file rather than
  * reconstructed from a pile of assertions.
  */
object Apollo:

  /** The archive (jar or directory) holding the compiled production classes,
    * located via a class we know is production. sbt 2 packages each output
    * into a content-addressed jar under `~/.../sbt/v2/cas/`, so "is this a
    * test class?" cannot be answered from the path shape — but it can be
    * answered by asking which archive a class came from.
    */
  private val productionArchive: String =
    val codeSource = classOf[apollo.core.Message].getProtectionDomain.getCodeSource
    assert(codeSource != null, "cannot locate apollo's compiled classes")
    codeSource.getLocation.getPath

  /** Production classes only, so no test suite — these included — is ever
    * judged by rules meant for the shipped code. */
  val production: JavaClasses =
    val imported = new ClassFileImporter()
      .withImportOption(new ImportOption:
        def includes(location: Location): Boolean = location.contains(productionArchive))
      .importPackages("apollo")
    assert(imported.size > 500, s"imported only ${imported.size} classes from $productionArchive")
    imported

  /** Everything, tests included — only the naming convention needs this. */
  val withTests: JavaClasses = new ClassFileImporter().importPackages("apollo")

  // ---------------------------------------------------------------------
  // The layer table
  // ---------------------------------------------------------------------

  /** One package, and the packages it is allowed to reach.
    *
    * `mayAccess` is a whitelist: anything not listed is a violation, so adding
    * a dependency edge is a deliberate, reviewable act. The list is exhaustive
    * and ordered bottom-up; a package may always use itself.
    */
  final case class Layer(name: String, pkg: String, mayAccess: List[String])

  val layers: List[Layer] = List(
    // --- kernel: pure data and pure functions, no apollo dependencies ----
    Layer("Util", "apollo.util", Nil),
    Layer("Core", "apollo.core", Nil),

    // --- infrastructure: one external concern each ----------------------
    Layer("Config", "apollo.config", List("Util")),
    Layer("Http", "apollo.http", List("Util")),

    // --- capabilities: independent of one another -----------------------
    Layer("Cron", "apollo.cron", List("Config", "Util")),
    Layer("Lsp", "apollo.lsp", List("Config", "Util")),
    Layer("Session", "apollo.session", List("Config", "Core", "Util")),
    Layer("Skills", "apollo.skills", List("Config", "Http", "Util")),
    Layer("Browser", "apollo.browser", List("Config", "Http", "Util")),
    Layer("Obs", "apollo.obs", List("Config", "Http", "Util")),
    Layer("Provider", "apollo.provider", List("Config", "Core", "Http", "Util")),

    // --- the tool plane -------------------------------------------------
    Layer("Tools", "apollo.tools",
      List("Browser", "Config", "Core", "Cron", "Http", "Lsp", "Skills", "Util")),
    Layer("Mcp", "apollo.mcp", List("Config", "Core", "Http", "Tools", "Util")),

    // --- the conversation loop ------------------------------------------
    Layer("Agent", "apollo.agent",
      List("Config", "Core", "Http", "Obs", "Provider", "Session", "Skills", "Tools", "Util")),

    // --- delivery surfaces ----------------------------------------------
    Layer("Gateway", "apollo.gateway",
      List("Agent", "Config", "Core", "Cron", "Http", "Mcp", "Provider", "Session", "Skills",
        "Tools", "Util")),
    Layer("Acp", "apollo.acp", List("Agent", "Config", "Gateway", "Provider", "Util")),

    // --- composition root ------------------------------------------------
    Layer("Cli", "apollo.cli",
      List("Acp", "Agent", "Browser", "Config", "Core", "Cron", "Gateway", "Http", "Lsp", "Mcp",
        "Obs", "Provider", "Session", "Skills", "Tools", "Util")),
    Layer("Main", "apollo", List("Cli"))
  )

  /** Packages that make up the kernel: pure, effect-free, apollo-free. */
  val kernel: List[String] = List("apollo.core", "apollo.util")

  /** The platform seam — the only classes allowed to touch JVM-only APIs.
    * Matches `apollo.<pkg>.Platform*` and anything nested inside it. */
  val platformSeam = ".*\\.Platform[^.]*"

  /** Kyo's effect entry points, as they appear in bytecode. A class that calls
    * `Sync.defer`, `Async.sleep`, `Abort.run`, … carries a reference to the
    * corresponding `kyo.*` module; a class that merely mentions the effect in
    * a type signature does not, which is exactly the distinction we want. */
  val kyoEffects =
    "kyo\\.(Sync|Async|Abort|Scope|Fiber|Clock|Console|Log|Command|Process|HttpClient|Queue|" +
      "Channel|Meter|Random|Stream|Var|Env|Emit|Poll|Barrier|Latch|Atomic[A-Za-z]*)(\\$.*)?"

  /** JVM-only APIs with no (or unreliable) Scala Native javalib support. */
  val jvmOnlyApis: List[String] = List(
    "java.security..", "javax.crypto..", "javax.net..", "javax.naming..",
    "java.sql..", "org.sqlite..", "org.jline..",
    "java.awt..", "javax.swing..", "java.lang.reflect..", "java.beans..", "java.rmi.."
  )

  extension (rule: ArchRule) def verify(): Unit = rule.check(production)

end Apollo

import Apollo.*

/** The dependency direction: what may reach what, and nothing circular. */
class LayeringSuite extends munit.FunSuite:

  test("packages depend only on the layers they are declared to depend on") {
    val declared = layers.foldLeft(layeredArchitecture().consideringOnlyDependenciesInLayers()) {
      (arch, layer) => arch.layer(layer.name).definedBy(layer.pkg)
    }
    layers
      .foldLeft(declared) { (arch, layer) =>
        // A layer may always use itself; everything else is the whitelist.
        arch.whereLayer(layer.name).mayOnlyAccessLayers((layer.name :: layer.mayAccess)*)
      }
      .as("apollo's layering (see ARCH.md, C4 level 2)")
      .verify()
  }

  test("every production class belongs to a declared layer") {
    // Catches a new top-level package that nobody placed in the table — which
    // would otherwise silently escape every layering rule above.
    val declaredPackages = layers.map(_.pkg).toSet
    val orphans = production.asScala.iterator
      .map(_.getPackageName)
      .filter(_.startsWith("apollo"))
      .filterNot(declaredPackages.contains)
      .toList
      .distinct
      .sorted
    assertEquals(orphans, Nil,
      s"packages missing from Apollo.layers: ${orphans.mkString(", ")}")
  }

  test("no package cycles") {
    slices()
      .matching("apollo.(*)..")
      .should()
      .beFreeOfCycles()
      .verify()
  }

  test("the kernel depends on nothing else in apollo") {
    // Stated separately from the layer table because it is the invariant that
    // makes `apollo.core` and `apollo.util` safe to use from anywhere.
    noClasses()
      .that().resideInAnyPackage(kernel*)
      .should().dependOnClassesThat(
        resideInAPackage("apollo..").and(resideOutsideOfPackages(kernel*)))
      .because("the kernel is the bottom of the stack: pure data and pure functions")
      .verify()
  }

end LayeringSuite

/** Functional-programming invariants: an immutable domain and a pure kernel. */
class PuritySuite extends munit.FunSuite:

  test("the kernel is free of Kyo effects") {
    // `apollo.core` and `apollo.util` are values and total functions. Anything
    // that needs Sync/Async/Abort belongs one layer up, where it can be
    // composed into an effect the caller controls.
    noClasses()
      .that().resideInAnyPackage(kernel*)
      .should().dependOnClassesThat().haveNameMatching(kyoEffects)
      .because("the kernel must stay testable without running an effect")
      .verify()
  }

  test("the domain model has no mutable fields") {
    // `$lzy`/bitmap fields are the compiler's own storage for `derives Schema`
    // givens on the companions, not domain state.
    fields()
      .that().areDeclaredInClassesThat().resideInAPackage("apollo.core")
      .and().areNotStatic()
      .and().haveNameNotMatching(".*(\\$lzy|bitmap).*")
      .should().beFinal()
      .because("apollo.core is the conversation model: values, never state")
      .verify()
  }

  test("the domain model uses no mutable or Java collections") {
    noClasses()
      .that().resideInAPackage("apollo.core")
      .should().dependOnClassesThat()
      .resideInAnyPackage("scala.collection.mutable..", "java.util.concurrent..")
      .because("a Message must be safe to share across fibers and to serialize as-is")
      .verify()
  }

  test("nothing throws a generic exception") {
    // Failure is a value here: `Abort[E]` / `Result[E, A]`. Where an exception
    // really is the mechanism (PlatformEmail's IMAP protocol errors), it gets
    // its own type so the catch can't swallow a bug by accident.
    GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS.verify()
  }

end PuritySuite

/** Kyo conventions: effects instead of threads, `kyo.Console` instead of stdout. */
class KyoStyleSuite extends munit.FunSuite:

  test("no scala.concurrent") {
    // Kyo owns concurrency. A Future/Await here would sit outside the fiber
    // scheduler, outside interrupts, and outside `Async.raceFirst`.
    noClasses()
      .should().dependOnClassesThat().resideInAnyPackage("scala.concurrent..")
      .because("concurrency is Kyo's: Async, Fiber, Meter, Async.raceFirst")
      .verify()
  }

  test("no thread-blocking sleeps outside the shutdown path") {
    // `Async.sleep` yields the fiber; `Thread.sleep` pins a carrier thread.
    // McpClient.destroyNow is the documented exception: a JVM/Native shutdown
    // hook cannot run an effect, so it waits for the child the blocking way.
    noClasses()
      .that().haveNameNotMatching("apollo\\.mcp\\.McpClient(\\$.*)?")
      .should().callMethod(classOf[Thread], "sleep", classOf[Long])
      .because("use Async.sleep so the fiber yields and stays interruptible")
      .verify()
  }

  test("only the terminal surfaces write to stdout directly") {
    // Everything else goes through kyo.Console (rendered output) or
    // apollo.obs.ObsLog (structured logs), so the gateway and the REPL can
    // route the same code's output to different places. The two exceptions own
    // a stream outright: the CLI owns the terminal, and the ACP server's stdout
    // *is* the JSON-RPC wire to the editor.
    val stdoutOwners = List("apollo.cli", "apollo.acp")

    noClasses()
      .that().resideOutsideOfPackages(stdoutOwners*)
      .should(GeneralCodingRules.ACCESS_STANDARD_STREAMS)
      .because("kyo.Console and apollo.obs.ObsLog are the output seams")
      .verify()

    noClasses()
      .that().resideOutsideOfPackages(stdoutOwners*)
      .should().dependOnClassesThat().haveFullyQualifiedName("scala.Console$")
      .because("println is the same escape hatch by another name")
      .verify()
  }

  test("only the composition root terminates the process") {
    noClasses()
      .that().resideOutsideOfPackages("apollo", "apollo.cli")
      .should().callMethod(classOf[System], "exit", classOf[Int])
      .because("a library package that exits cannot be reused by the gateway")
      .verify()
  }

  test("no java.util.logging") {
    GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.verify()
  }

  test("no legacy date and time classes") {
    // java.time only — Date/Calendar are mutable and Native-unfriendly.
    GeneralCodingRules.OLD_DATE_AND_TIME_CLASSES_SHOULD_NOT_BE_USED.verify()
  }

end KyoStyleSuite

/** The seams: adapters are reached through their port, never directly. */
class SeamSuite extends munit.FunSuite:

  test("provider wire adapters are reached only through WireTransport") {
    // `WireTransport.forMode(apiMode)` is the single dispatch point; importing
    // AnthropicTransport from the agent would hard-wire one protocol.
    noClasses()
      .that().resideOutsideOfPackage("apollo.provider")
      .should().dependOnClassesThat()
      .haveNameMatching("apollo\\.provider\\.(Anthropic|Bedrock|ChatCompletions|Responses)Transport(\\$.*)?")
      .because("WireTransport.forMode is the seam between the loop and a protocol")
      .verify()
  }

  test("built-in tool handlers are reached only through the registry") {
    // ToolRegistry.dispatch is the one place a tool name becomes a call, so
    // built-ins and dynamic MCP entries stay indistinguishable to the loop.
    noClasses()
      .that().resideOutsideOfPackage("apollo.tools")
      .should().dependOnClassesThat()
      .haveNameMatching("apollo\\.tools\\.(FileTools|TerminalTools|WebTools|SmallTools|ImageGen|" +
        "VideoGen|VoiceTools|BrowserTool|ComputerUse|KanbanTool|LspTool|CronTool)(\\$.*)?")
      .because("ToolRegistry.dispatch is the seam; handlers are registry data")
      .verify()
  }

  test("chat connectors are reached only through the gateway") {
    // `Gateway.run` gathers the connectors; adding a platform must not ripple
    // anywhere else. `SessionHub` is deliberately not in this list — it is the
    // reusable "one serialized agent per conversation" service that the ACP
    // server shares with the gateway.
    noClasses()
      .that().resideOutsideOfPackage("apollo.gateway")
      .should().dependOnClassesThat()
      .haveNameMatching("apollo\\.gateway\\.(Telegram|Discord|Slack|Matrix|WhatsApp|Sms|Teams|" +
        "IMessage|Email|ApiServer)(\\$.*)?")
      .because("Gateway.run wires the connectors; nothing else should know they exist")
      .verify()
  }

  test("ports are interfaces") {
    // The abstractions that invert a dependency (a tool asking the human, the
    // OAuth flow asking for a pasted code, a protocol adapter) must stay
    // abstract — a concrete class here would drag its implementation along.
    classes()
      .that().haveNameMatching(
        "apollo\\.(tools\\.(ToolUi|DelegateRunner|VisionRunner)|mcp\\.CodePrompt|" +
          "provider\\.WireTransport|cli\\.LineEditor)")
      .should().beInterfaces()
      .because("a port exists to be implemented elsewhere")
      .verify()
  }

end SeamSuite

/** What keeps the Scala Native binary building and behaving. */
class PortabilitySuite extends munit.FunSuite:

  test("only the platform seam touches JVM-only APIs") {
    // The shared codebase compiles for Native too, where java.security,
    // java.sql, JLine and friends are absent or unreliable. Everything that
    // needs them lives behind `Platform*`, which has a Native twin.
    noClasses()
      .that().haveNameNotMatching(platformSeam)
      .should().dependOnClassesThat().resideInAnyPackage(jvmOnlyApis*)
      .because("apollo.cli.PlatformEditor / session.PlatformSearch / " +
        "gateway.PlatformEmail are the only platform-split classes (ARCH.md)")
      .verify()
  }

  test("the platform seam is split consistently across both builds") {
    // Every `Platform*` compiled here must have a Native counterpart, or the
    // Native build breaks at link time rather than in a test.
    val seam = production.asScala.iterator
      .map(_.getName)
      .filter(_.matches(platformSeam))
      .map(n => n.substring(n.lastIndexOf('.') + 1).takeWhile(c => c != '$'))
      .toSet
      .toList
      .sorted
    val nativeRoot = java.nio.file.Paths.get("native/src/main/scala/apollo")
    assert(java.nio.file.Files.isDirectory(nativeRoot), s"missing $nativeRoot (wrong cwd?)")
    val walk = java.nio.file.Files.walk(nativeRoot)
    val nativeNames =
      try
        walk.iterator.asScala
          .map(_.getFileName.toString)
          .filter(_.endsWith(".scala"))
          .map(_.stripSuffix(".scala"))
          .filter(_.startsWith("Platform"))
          .toSet
      finally walk.close()
    assertEquals(seam.filterNot(nativeNames.contains), Nil,
      s"JVM-only Platform classes with no Native twin (native has: ${nativeNames.toList.sorted})")
  }

end PortabilitySuite

/** Conventions that keep the codebase navigable. */
class ConventionsSuite extends munit.FunSuite:

  test("test classes are named *Suite") {
    classes()
      .that().areAssignableTo(classOf[munit.Suite])
      .and().areNotInterfaces()
      .and().areNotAnonymousClasses()
      .should().haveSimpleNameEndingWith("Suite")
      .because("munit discovers them by name; a *Test would silently not run")
      .check(withTests)
  }

end ConventionsSuite
