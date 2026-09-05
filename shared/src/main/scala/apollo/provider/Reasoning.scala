package apollo.provider

import kyo.*

/** Reasoning-effort vocabulary and clamping, mirroring the upstream harness's
  * `agent/reasoning_effort.py`.
  *
  * The internal representation is `ReasoningConfig(enabled, effort)`. Each
  * provider then maps it onto its own wire shape (top-level
  * `reasoning_effort`, `extra_body.reasoning`, `thinking: {type: ...}`,
  * Anthropic adaptive/budget thinking, ...).
  */
object Reasoning:

  /** Low → high. `ultra` is the upstream harness-internal: no wire accepts it directly. */
  val ladder: Vector[String] =
    Vector("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

  val openAiCompatWireEfforts: Set[String] =
    Set("none", "minimal", "low", "medium", "high", "xhigh", "max")

  final case class Config(enabled: Boolean, effort: String)

  /** Parses the `agent.reasoning_effort` config value. "none" disables. */
  def fromConfigValue(value: String): Maybe[Config] =
    val v = value.trim.toLowerCase
    if v.isEmpty then Absent
    else if v == "none" then Present(Config(enabled = false, effort = "none"))
    else if ladder.contains(v) then Present(Config(enabled = true, effort = v))
    else Absent

  /** the upstream harness clamp semantics: explicit overrides first; pass through when
    * supported or when the level isn't on the ladder (bespoke provider
    * vocabularies); otherwise the nearest WEAKER supported level — a clamp
    * never escalates cost — falling back to the weakest supported level.
    * `"none"` is never chosen as a degradation target for an enabled ask.
    */
  def clamp(
      effort: String,
      supported: Seq[String],
      overrides: Map[String, String] = Map.empty
  ): String =
    overrides.get(effort) match
      case Some(mapped) => mapped
      case None =>
        if supported.isEmpty || supported.contains(effort) || !ladder.contains(effort) then effort
        else
          val idx     = ladder.indexOf(effort)
          val weaker  = ladder.take(idx).reverse.find(l => l != "none" && supported.contains(l))
          weaker.getOrElse(supported.filter(_ != "none").headOption.getOrElse(supported.head))

  // Per-family vocabularies (subset actually used by the ported providers).
  val kimiK3: (Seq[String], Map[String, String]) =
    (Seq("low", "high", "max"), Map("medium" -> "high", "xhigh" -> "max"))
  val glm52: (Seq[String], Map[String, String])  = (Seq("high", "max"), Map("xhigh" -> "max"))
  val glm53: (Seq[String], Map[String, String])  = (Seq("low", "medium", "high", "max"), Map("xhigh" -> "max"))
  val deepseekV4: (Seq[String], Map[String, String]) =
    (Seq("low", "medium", "high", "max"), Map("xhigh" -> "max"))
  val ollamaCloud: (Seq[String], Map[String, String]) =
    (Seq("none", "low", "medium", "high", "max"), Map("xhigh" -> "max"))
  val metaAi: (Seq[String], Map[String, String]) =
    (Seq("minimal", "low", "medium", "high", "xhigh"), Map("none" -> "minimal"))
  val threeLevels: (Seq[String], Map[String, String]) =
    (Seq("low", "medium", "high"), Map.empty)

  /** Anthropic adaptive-thinking effort map (Claude 4.6+). */
  val anthropicAdaptiveMap: Map[String, String] =
    Map(
      "ultra"   -> "max",
      "max"     -> "max",
      "xhigh"   -> "xhigh",
      "high"    -> "high",
      "medium"  -> "medium",
      "low"     -> "low",
      "minimal" -> "low"
    )

  /** Anthropic legacy manual-thinking token budgets. */
  val anthropicBudgets: Map[String, Int] =
    Map("xhigh" -> 32000, "high" -> 16000, "medium" -> 8000, "low" -> 4000)

  val anthropicDefaultBudget: Int = 8000
end Reasoning
