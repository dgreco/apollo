package apollo.provider

/** Failures surfaced by a provider call. `Http` carries the provider's own
  * error body (auth failures, quota, invalid request); `Network` is
  * transport-level; `Protocol` means the response didn't match the wire
  * contract we expected.
  */
enum ProviderError extends Exception:
  case Http(status: Int, body: String)
  case Network(message: String)
  case Protocol(message: String)

  override def getMessage: String = this match
    case Http(status, body)  => s"HTTP $status: ${body.take(2000)}"
    case Network(message)    => s"network error: $message"
    case Protocol(message)   => s"protocol error: $message"

  /** Worth retrying? Mirrors the upstream harness: 408/429/5xx and transport drops retry;
    * other 4xx (auth, bad request) fail fast.
    */
  def retryable: Boolean = this match
    case Http(status, _) => status == 408 || status == 429 || status >= 500
    case Network(_)      => true
    case Protocol(_)     => false
