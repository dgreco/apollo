// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.http

/** Failures surfaced by an HTTP call. `Status` carries the server's own error
  * body (auth failures, quota, invalid request); `Network` is transport-level;
  * `Protocol` means the response didn't match the wire contract we expected.
  *
  * This is the `Abort` type of every [[Transport]] entry point, so it is what a
  * provider turn, an MCP token exchange, a Telegram poll and a CDP call all
  * fail with — it describes the transport, never the caller's domain.
  */
enum HttpError extends Exception:
  case Status(status: Int, body: String)
  case Network(message: String)
  case Protocol(message: String)

  override def getMessage: String = this match
    case Status(status, body) => s"HTTP $status: ${body.take(2000)}"
    case Network(message)     => s"network error: $message"
    case Protocol(message)    => s"protocol error: $message"

  /** Worth retrying? Mirrors the upstream harness: 408/429/5xx and transport drops retry;
    * other 4xx (auth, bad request) fail fast.
    */
  def retryable: Boolean = this match
    case Status(status, _) => status == 408 || status == 429 || status >= 500
    case Network(_)        => true
    case Protocol(_)       => false
