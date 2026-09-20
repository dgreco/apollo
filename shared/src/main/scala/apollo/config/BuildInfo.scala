package apollo.config

/** Static build metadata. Lives at the bottom of the stack because the banner,
  * the MCP `clientInfo` handshake and the gateway's startup line all need it,
  * and none of them should have to reach up into the CLI for a constant.
  */
object BuildInfo:
  val version: String = "0.1.0"
