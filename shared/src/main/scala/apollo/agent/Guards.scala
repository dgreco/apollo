// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.agent

import apollo.core.*

/** Loop-safety guards for the agent turn: pure predicates over the message
  * stream that stop the model burning iterations (and tokens) on a stuck loop
  * or an unproductive empty reply. Kept side-effect free so they are trivially
  * testable; `Agent.loop` owns the small mutable counters that drive them.
  *
  * Mirrors Hermes's repetition / empty-response guards.
  */
object Guards:

  /** A stable fingerprint of an assistant turn used to detect a model looping
    * on the same action. Tool calls are the common culprit, so a turn with
    * tool uses is signed by its (name, arguments) pairs (order-preserving);
    * a plain text turn is signed by its normalized text. Returns `None` for a
    * turn that carries neither (handled by the empty-response guard instead).
    */
  def signature(message: Message): Option[String] =
    val toolUses = message.content.collect { case tu: Content.ToolUse => tu }
    if toolUses.nonEmpty then
      Some("tools " + toolUses.map(tu => s"${tu.name}${tu.arguments.trim}").mkString(""))
    else
      val text = message.content.collect { case Content.Text(t) => t }.mkString("\n").trim
      if text.isEmpty then None else Some("text " + text)

  /** Appends `sig` to the recent-signature history (most recent last), capped
    * so it never grows unbounded. `limit <= 0` disables tracking. */
  def pushSignature(history: List[String], sig: Option[String], limit: Int): List[String] =
    if limit <= 0 then Nil
    else
      sig match
        case Some(s) => (history :+ s).takeRight(limit)
        case None    => history

  /** True when the last `limit` signatures exist and are all identical — the
    * model has repeated the same action `limit` times running. `limit <= 1`
    * never fires (a single occurrence is not a repetition). */
  def isRepeating(history: List[String], limit: Int): Boolean =
    limit > 1 && history.length >= limit && history.takeRight(limit).distinct.sizeIs == 1

  /** True when the assistant produced no tool calls and no non-blank text. */
  def isEmptyResponse(message: Message): Boolean =
    val hasTool = message.content.exists(_.isInstanceOf[Content.ToolUse])
    val text    = message.content.collect { case Content.Text(t) => t }.mkString.trim
    !hasTool && text.isEmpty

  /** The corrective nudge injected as a user turn after an empty response, to
    * give the model one more chance to either act or answer. */
  val emptyResponseNudge: String =
    "Your previous response was empty. Either call a tool to make progress, or " +
      "give your final answer now."

end Guards
