// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.session

import apollo.core.Content
import kyo.*

/** JVM session search backed by SQLite FTS5 (via sqlite-jdbc, which bundles
  * a native SQLite with FTS5 compiled in). The index at
  * `scala-state/search.db` is synced lazily on each search — any session
  * whose transcript has grown since it was last indexed is (re)indexed — so
  * repeat searches are fast without touching the hot append path. Any failure
  * (driver missing, disk issue) degrades to the pure-Scala scan, so search
  * never breaks a session.
  */
object PlatformSearch:

  def search(store: SessionStore, query: String, limit: Int): String < Sync =
    val terms = query.toLowerCase.split("\\s+").filter(_.nonEmpty).toList
    if terms.isEmpty then "no matching sessions"
    else
      store.list.map { metas =>
        collectToIndex(store, metas).map { rows =>
          Sync.defer(fts(store.searchDbFile, metas, rows, terms, limit)).map {
            case Some(result) => result
            case None         => store.scanSearch(query, limit) // FTS unavailable → scan
          }
        }
      }

  /** For each session, its full concatenated text and current message count,
    * so the index can skip sessions it has already fully indexed.
    */
  private def collectToIndex(
      store: SessionStore, metas: List[SessionMeta]
  ): List[(String, String, Int)] < Sync =
    Kyo.foreach(metas.take(500)) { meta =>
      store.loadTranscript(meta.id).map { messages =>
        val text = messages.flatMap(_.content.collect { case Content.Text(t) => t }).mkString("\n")
        (meta.id, text, messages.length)
      }
    }.map(_.toList)

  /** Runs the FTS5 sync + query. Returns None on any SQLite failure so the
    * caller falls back to the scan. Pure blocking JDBC, wrapped by the caller
    * in `Sync.defer`.
    */
  private def fts(
      dbFile: java.nio.file.Path,
      metas: List[SessionMeta],
      rows: List[(String, String, Int)],
      terms: List[String],
      limit: Int
  ): Option[String] =
    try
      java.nio.file.Files.createDirectories(dbFile.getParent)
      Class.forName("org.sqlite.JDBC")
      val conn = java.sql.DriverManager.getConnection(s"jdbc:sqlite:${dbFile}")
      try
        exec(conn, "CREATE VIRTUAL TABLE IF NOT EXISTS messages USING fts5(" +
          "session_id UNINDEXED, title UNINDEXED, body)")
        exec(conn, "CREATE TABLE IF NOT EXISTS indexed(session_id TEXT PRIMARY KEY, msg_count INTEGER)")

        val titles = metas.map(m => m.id -> m.title.getOrElse("(untitled)")).toMap
        syncIndex(conn, rows, titles)

        // FTS5 query: each term as a quoted token, implicitly ANDed.
        val matchExpr = terms.map(t => "\"" + t.replace("\"", "\"\"") + "\"").mkString(" ")
        val ps = conn.prepareStatement(
          "SELECT session_id, title, snippet(messages, 2, '', '', '…', 12) AS snip " +
            "FROM messages WHERE messages MATCH ? ORDER BY rank LIMIT ?")
        ps.setString(1, matchExpr)
        ps.setInt(2, limit)
        val rs = ps.executeQuery()
        val out = scala.collection.mutable.ListBuffer.empty[String]
        while rs.next() do
          val sid   = rs.getString("session_id")
          val title = rs.getString("title")
          val snip  = Option(rs.getString("snip")).getOrElse("").replace('\n', ' ').take(200)
          out += s"[$sid] $title: $snip"
        ps.close()
        Some(if out.isEmpty then "no matching sessions" else out.mkString("\n"))
      finally conn.close()
    catch case _: Throwable => None

  /** Insert rows for sessions whose message count grew since last indexed. */
  private def syncIndex(
      conn: java.sql.Connection,
      rows: List[(String, String, Int)],
      titles: Map[String, String]
  ): Unit =
    val known =
      val m = scala.collection.mutable.Map.empty[String, Int]
      val rs = conn.createStatement().executeQuery("SELECT session_id, msg_count FROM indexed")
      while rs.next() do m(rs.getString(1)) = rs.getInt(2)
      m
    conn.setAutoCommit(false)
    try {
      val del  = conn.prepareStatement("DELETE FROM messages WHERE session_id = ?")
      val ins  = conn.prepareStatement("INSERT INTO messages(session_id, title, body) VALUES (?, ?, ?)")
      val mark = conn.prepareStatement("INSERT OR REPLACE INTO indexed(session_id, msg_count) VALUES (?, ?)")
      rows.foreach { (sid, text, count) =>
        if known.getOrElse(sid, -1) != count then
          del.setString(1, sid)
          del.executeUpdate()
          ins.setString(1, sid)
          ins.setString(2, titles.getOrElse(sid, ""))
          ins.setString(3, text)
          ins.executeUpdate()
          mark.setString(1, sid)
          mark.setInt(2, count)
          mark.executeUpdate()
      }
      del.close()
      ins.close()
      mark.close()
      conn.commit()
    } catch {
      case e: Throwable =>
        conn.rollback()
        throw e
    } finally conn.setAutoCommit(true)

  private def exec(conn: java.sql.Connection, sql: String): Unit =
    val st = conn.createStatement(); st.execute(sql); st.close()
end PlatformSearch
