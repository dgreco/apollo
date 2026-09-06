package apollo.tools

import apollo.config.Fs
import kyo.*

/** A local kanban board: cards in status columns over a tab-separated file
  * (`~/.apollo/kanban.txt`, `col\tid\ttext` per line), shared by the REPL
  * `/kanban` command and the agent-facing `kanban` tool (Hermes exposes the
  * board to the agent as a dispatch board; apollo keeps it local, single file). */
object KanbanBoard:

  /** Status columns (Hermes-aligned: todo → doing → blocked/review → done). */
  val columns: List[String] = List("todo", "doing", "blocked", "review", "done")

  final case class Card(id: String, col: String, text: String)

  // --- pure ----------------------------------------------------------------

  def renderFile(cards: List[Card]): String =
    cards.map(c => s"${c.col}\t${c.id}\t${c.text.replace('\t', ' ').replace('\n', ' ')}").mkString("\n")

  def parse(s: String): List[Card] =
    s.linesIterator.map(_.trim).filter(_.nonEmpty).flatMap { line =>
      line.split("\t", 3) match
        case Array(col, id, text) => Some(Card(id, col, text))
        case _                    => None
    }.toList

  def renderBoard(cards: List[Card]): String =
    columns.map { col =>
      val items = cards.filter(_.col == col)
      val body  = if items.isEmpty then "  (empty)" else items.map(c => s"  [${c.id}] ${c.text}").mkString("\n")
      s"$col:\n$body"
    }.mkString("\n")

  def add(cards: List[Card], id: String, col: String, text: String): List[Card] =
    cards :+ Card(id, col, text)

  /** Move a card to `col`; None when no such card. */
  def move(cards: List[Card], id: String, col: String): Option[List[Card]] =
    if cards.exists(_.id == id) then Some(cards.map(c => if c.id == id then c.copy(col = col) else c)) else None

  def remove(cards: List[Card], id: String): Option[List[Card]] =
    if cards.exists(_.id == id) then Some(cards.filterNot(_.id == id)) else None

  // --- storage -------------------------------------------------------------

  def boardFile(paths: apollo.config.ApolloPaths): java.nio.file.Path = paths.home.resolve("kanban.txt")

  def load(paths: apollo.config.ApolloPaths): List[Card] < Sync =
    Fs.readString(boardFile(paths)).map(s => parse(s.getOrElse("")))

  def save(paths: apollo.config.ApolloPaths, cards: List[Card]): Unit < Sync =
    Fs.writeString(boardFile(paths), renderFile(cards))
end KanbanBoard
