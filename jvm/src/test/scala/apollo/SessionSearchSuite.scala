// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.session

import apollo.config.ApolloPaths
import apollo.core.{Message, Usage}
import kyo.*

/** JVM SQLite FTS5 session search: indexing, ranked matching, incremental
  * re-index on transcript growth, and the scan-fallback contract.
  */
class SessionSearchSuite extends munit.FunSuite:

  private def run[A](v: A < Sync): A =
    import AllowUnsafe.embrace.danger
    Sync.Unsafe.evalOrThrow(v)

  private def newStore(): (SessionStore, ApolloPaths) =
    val home = java.nio.file.Files.createTempDirectory("apollo-search-home")
    (new SessionStore(ApolloPaths(home)), ApolloPaths(home))

  private def seed(store: SessionStore, id: String, title: String, texts: List[String]): Unit =
    run(store.create(SessionMeta(id, Present(title), "cli", "m", "p", 0.0, Absent, "/", 0, 0, Usage.zero)))
    texts.foreach(t => run(store.appendMessage(id, Message.user(t))))

  test("FTS5 finds a session by content and returns its id + title") {
    val (store, _) = newStore()
    seed(store, "s1", "Kyo notes", List("the fiber scheduler preempts cooperatively"))
    seed(store, "s2", "Rust notes", List("ownership and borrow checking"))
    val out = run(store.search("scheduler", 10))
    assert(out.contains("[s1]"), out)
    assert(out.contains("Kyo notes"), out)
    assert(!out.contains("[s2]"), out)
  }

  test("multiple terms are ANDed") {
    val (store, _) = newStore()
    seed(store, "a", "A", List("alpha beta gamma"))
    seed(store, "b", "B", List("alpha delta"))
    val both = run(store.search("alpha beta", 10))
    assert(both.contains("[a]"), both)
    assert(!both.contains("[b]"), both)
  }

  test("no match returns the sentinel") {
    val (store, _) = newStore()
    seed(store, "s1", "T", List("hello world"))
    assertEquals(run(store.search("nonexistentword", 10)), "no matching sessions")
  }

  test("index picks up messages appended after the first search") {
    val (store, _) = newStore()
    seed(store, "s1", "Growing", List("first message about apples"))
    assertEquals(run(store.search("bananas", 10)), "no matching sessions")
    run(store.appendMessage("s1", Message.user("second message about bananas")))
    val out = run(store.search("bananas", 10))
    assert(out.contains("[s1]"), out)
  }

  test("the search db is created under scala-state") {
    val (store, paths) = newStore()
    seed(store, "s1", "T", List("indexed content here"))
    run(store.search("content", 10))
    assert(java.nio.file.Files.exists(paths.home.resolve("scala-state").resolve("search.db")))
  }

  test("query with FTS special characters does not crash") {
    val (store, _) = newStore()
    seed(store, "s1", "T", List("a normal sentence"))
    // Quotes/parens would be FTS5 syntax; terms are quoted, so this is safe.
    val out = run(store.search("\"normal (sentence)\"", 10))
    assert(out.contains("[s1]") || out == "no matching sessions", out)
  }
end SessionSearchSuite
