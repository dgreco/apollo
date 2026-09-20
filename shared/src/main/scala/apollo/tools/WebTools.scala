package apollo.tools

import apollo.http.{HttpError, Transport}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Web tools: web_extract (fetch + readable-text extraction, always
  * available) and web_search (Brave Search API when BRAVE_SEARCH_API_KEY is
  * configured — the availability probe hides the tool otherwise, like a
  * the upstream harness check_fn).
  */
object WebTools:

  val entries: List[ToolEntry] = List(
    ToolEntry(
      name = "web_search",
      toolset = "web",
      description = "Search the web. Returns titles, URLs and snippets.",
      parametersJson = """{"type":"object","properties":{
        "query":{"type":"string","description":"Search query"},
        "limit":{"type":"integer","minimum":1,"maximum":100,"default":5}
      },"required":["query"]}""".replaceAll("\n\\s*", ""),
      emoji = "🔍",
      available = ctx => ctx.config.env.get("BRAVE_SEARCH_API_KEY").nonEmpty,
      handler = webSearch
    ),
    ToolEntry(
      name = "web_extract",
      toolset = "web",
      description = "Fetch one or more URLs and extract their readable text content.",
      parametersJson = """{"type":"object","properties":{
        "urls":{"type":"array","items":{"type":"string"},"maxItems":5},
        "char_limit":{"type":"integer","minimum":2000,"default":15000}
      },"required":["urls"]}""".replaceAll("\n\\s*", ""),
      emoji = "📄",
      maxResultChars = 100_000,
      handler = webExtract
    )
  )

  private def webSearch(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    ((args / "query").asStr, ctx.config.env.get("BRAVE_SEARCH_API_KEY")) match
      case (Present(query), Present(key)) =>
        val limit = (args / "limit").asLong.map(_.toInt).getOrElse(5).max(1).min(20)
        val url =
          s"https://api.search.brave.com/res/v1/web/search?q=${urlEncode(query)}&count=$limit"
        Abort.run[HttpError](Transport.getJson(url, List("x-subscription-token" -> key))).map {
          case Result.Success(body) =>
            Jx.parse(body) match
              case Result.Success(json) =>
                val results = (json / "web" / "results").asArr.getOrElse(Chunk.empty).toList.take(limit)
                if results.isEmpty then ToolOutcome.Ok("no results")
                else
                  ToolOutcome.Ok(results.zipWithIndex.map { (r, i) =>
                    val title   = (r / "title").asStr.getOrElse("")
                    val rurl    = (r / "url").asStr.getOrElse("")
                    val snippet = (r / "description").asStr.getOrElse("")
                    s"${i + 1}. $title\n   $rurl\n   $snippet"
                  }.mkString("\n"))
              case _ => ToolOutcome.Error("unparseable search response")
          case Result.Failure(e) => ToolOutcome.Error(s"search failed: ${e.getMessage}")
          case Result.Panic(e)   => ToolOutcome.Error(s"search failed: ${e.getMessage}")
        }
      case (Absent, _) => ToolOutcome.Error("missing required parameter: query")
      case _           => ToolOutcome.Error("web_search requires BRAVE_SEARCH_API_KEY")

  private def webExtract(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    (args / "urls").asArr match
      case Absent => ToolOutcome.Error("missing required parameter: urls")
      case Present(urlsJson) =>
        val urls      = urlsJson.toList.flatMap(_.asStr.toList).take(5)
        val charLimit = (args / "char_limit").asLong.map(_.toInt).getOrElse(15000).max(2000)
        if urls.isEmpty then ToolOutcome.Error("urls must contain at least one URL")
        else
          Kyo.foreach(urls) { url =>
            Abort.run[HttpError](Transport.getJson(url, List("accept" -> "text/html,*/*"))).map {
              case Result.Success(body) =>
                val text = htmlToText(body).take(charLimit)
                s"=== $url ===\n$text"
              case Result.Failure(e) => s"=== $url ===\n[fetch failed: ${e.getMessage.take(300)}]"
              case Result.Panic(e)   => s"=== $url ===\n[fetch failed: ${e.getMessage}]"
            }
          }.map(parts => ToolOutcome.Ok(parts.mkString("\n\n")))

  /** Small readable-text extraction: strips script/style/tags, decodes common
    * entities, collapses whitespace.
    */
  private[tools] def htmlToText(html: String): String =
    val noScripts = html
      .replaceAll("(?is)<script.*?</script>", " ")
      .replaceAll("(?is)<style.*?</style>", " ")
      .replaceAll("(?is)<!--.*?-->", " ")
    val blockBreaks = noScripts
      .replaceAll("(?i)</(p|div|h[1-6]|li|tr|section|article|br)>", "\n")
      .replaceAll("(?i)<br\\s*/?>", "\n")
    val noTags = blockBreaks.replaceAll("(?s)<[^>]+>", " ")
    val decoded = noTags
      .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
      .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
    decoded.linesIterator.map(_.trim.replaceAll("\\s+", " ")).filter(_.nonEmpty).mkString("\n")

  private def urlEncode(s: String): String =
    java.net.URLEncoder.encode(s, "UTF-8")
end WebTools
