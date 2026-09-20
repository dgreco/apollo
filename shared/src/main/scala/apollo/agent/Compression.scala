package apollo.agent

import apollo.config.ApolloConfig
import apollo.core.*
import apollo.http.HttpError
import apollo.provider.*
import apollo.session.SessionStore
import kyo.*

/** Context compression, following the upstream two-phase design:
  *
  *   1. Phase 1 (no LLM): old tool results outside the protected tail are
  *      cleared to a stub — most of the win, zero risk.
  *   2. Phase 2/3: the un-protected middle is summarized via one auxiliary
  *      model call and replaced with a structured summary message. The head
  *      (`protect_first_n` non-system messages), the recent tail
  *      (`protect_last_n`) and at least `min_tail_user_messages` real user
  *      messages always survive verbatim.
  */
object Compression:

  private val pruneMinChars = 200
  private val stub          = "[Old tool output cleared to save context space]"

  def pruneOldToolResults(messages: List[Message], protectLastN: Int): List[Message] =
    val cutoff = (messages.length - protectLastN).max(0)
    messages.zipWithIndex.map { (m, i) =>
      if i >= cutoff || m.role != Role.Tool then m
      else
        m.copy(content = m.content.map {
          case tr: Content.ToolResult if tr.output.length > pruneMinChars =>
            tr.copy(output = stub)
          case other => other
        })
    }

  def summarizeMiddle(
      messages: List[Message],
      config: ApolloConfig,
      runtime: ResolvedRuntime,
      sessionId: String,
      store: SessionStore
  ): List[Message] < (Sync & Async) =
    val headCount = boundaryHead(messages, config.protectFirstN)
    val tailStart = boundaryTail(messages, config.protectLastN, config.minTailUserMessages)
    if tailStart <= headCount + 2 then
      // Nothing meaningful to summarize; phase-1 pruning must suffice.
      messages
    else
      val head   = messages.take(headCount)
      val middle = messages.slice(headCount, tailStart)
      val tail   = messages.drop(tailStart)
      auxRuntime(config, runtime).map { aux =>
        summarize(middle, aux).map { summary =>
          val summaryMsg = Message.user(
            s"""[Conversation history compressed. Summary of the elided middle section:]
               |$summary
               |[End of summary. The conversation continues below.]""".stripMargin
          )
          val rebuilt = Alternation.repair(head ++ List(summaryMsg) ++ dropLeadingToolResults(tail))
          store.rewriteTranscript(sessionId, rebuilt).andThen(rebuilt)
        }
      }
  end summarizeMiddle

  /** Resolves the model used for the compression summary: the configured
    * `auxiliary.compression.provider/model` (a cheap side model) when set and
    * resolvable, otherwise the main runtime. Previously the aux config was
    * parsed and ignored, so compaction always paid the main model's price.
    */
  private[agent] def auxRuntime(config: ApolloConfig, main: ResolvedRuntime): ResolvedRuntime < (Sync & Async) =
    config.auxiliary("compression") match
      case (provider, Present(model)) =>
        Abort.run[ResolveError](
          Runtime.resolve(config, RuntimeOverrides(model = Present(model), provider = provider))
        ).map {
          case Result.Success(rt) => rt
          case _                  => main // aux provider unresolvable → main model
        }
      case _ => main

  /** First N non-system messages are protected (system prompt is separate). */
  private def boundaryHead(messages: List[Message], protectFirstN: Int): Int =
    protectFirstN.min(messages.length)

  /** Tail boundary: protect the last N messages AND guarantee the last
    * `minUsers` real user messages survive; then align backward past
    * consecutive tool results to the owning assistant message.
    */
  private def boundaryTail(messages: List[Message], protectLastN: Int, minUsers: Int): Int =
    var idx = (messages.length - protectLastN).max(0)
    // Guarantee minUsers real user messages in the tail (wins over the count).
    val userIdxs = messages.zipWithIndex.collect {
      case (m, i) if m.role == Role.User => i
    }
    userIdxs.takeRight(minUsers).headOption.foreach(u => idx = idx.min(u))
    // Never cut between an assistant tool call and its results.
    while idx > 0 && messages.lift(idx).exists(_.role == Role.Tool) do idx -= 1
    idx.max(0)

  private def dropLeadingToolResults(tail: List[Message]): List[Message] =
    tail.dropWhile(_.role == Role.Tool)

  private def summarize(
      middle: List[Message],
      runtime: ResolvedRuntime
  ): String < (Sync & Async) =
    val transcript = middle.map(render).mkString("\n").take(160_000)
    val prompt =
      s"""Summarize this conversation section for context compression. Structure the summary as:
         |Goal / Constraints & Preferences / Progress (Done, In Progress, Blocked) / Key Decisions /
         |Relevant Files / Next Steps / Critical Context. Preserve exact identifiers (paths, IDs,
         |error strings, URLs) verbatim — never paraphrase them.
         |
         |<section>
         |$transcript
         |</section>""".stripMargin
    val request = TurnRequest(
      runtime = runtime.copy(streaming = false, reasoning = Absent, maxTokens = Present(4096)),
      systemPrompt = "You are a precise summarizer for agent-conversation compression.",
      messages = List(Message.user(prompt)),
      tools = Nil
    )
    WireTransport.forMode(runtime.apiMode) match
      case Result.Success(transport) =>
        Abort.run[HttpError](transport.streamTurn(request)(_ => ())).map {
          case Result.Success(resp) =>
            resp.message.content.collect { case Content.Text(t) => t }.mkString("\n") match
              case ""   => fallbackSummary(middle)
              case text => text
          case _ => fallbackSummary(middle) // degraded summary beats silent loss
        }
      case _ => fallbackSummary(middle)
  end summarize

  /** Mechanical fallback when the summarizer call fails: quote every user
    * message verbatim so intent is never silently lost.
    */
  private def fallbackSummary(middle: List[Message]): String =
    val users = middle.filter(_.role == Role.User).flatMap(_.content.collect { case Content.Text(t) => t })
    ("(Summarizer unavailable; user messages from the elided section, verbatim:)"
      :: users.map(u => s"- ${u.take(500)}")).mkString("\n")

  private def render(m: Message): String =
    val parts = m.content.map {
      case Content.Text(t)              => t.take(2000)
      case Content.Thinking(_, _)       => "(thinking)"
      case Content.ToolUse(_, name, a)  => s"[tool call: $name(${a.take(300)})]"
      case Content.ToolResult(_, o, _)  => s"[tool result: ${o.take(500)}]"
      case Content.Image(_, _)          => "[image]"
    }
    s"${m.role}: ${parts.mkString(" ")}"
end Compression
