// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.config

import kyo.*
import org.virtuslab.yaml.*

/** Lenient navigation over a parsed YAML document.
  *
  * the upstream harness's `config.yaml` is a sprawling, ever-growing document; decoding it
  * into rigid case classes would break on every unknown key. Instead we keep
  * the raw AST and read just the paths we understand, with `Maybe` for
  * anything missing or of an unexpected shape.
  */
object Yaml:

  def parse(content: String): Result[String, Node] =
    content.asNode match
      case Right(node) => Result.succeed(node)
      case Left(err)   => Result.fail(err.msg)

  /** Deep-merges `overlay` into `base`: mapping-vs-mapping merges per key
    * recursively, anything else is replaced by the overlay (overlay wins at
    * the leaf) — the managed-scope merge semantics
    * (upstream `config.py::_deep_merge`).
    */
  def deepMerge(base: Node, overlay: Node): Node =
    (base, overlay) match
      case (Node.MappingNode(bm, _), Node.MappingNode(om, _)) =>
        def keyOf(n: Node): Option[String] = n match
          case Node.ScalarNode(v, _) => Some(v)
          case _                     => None
        val overlayByKey = om.toList.flatMap((k, v) => keyOf(k).map(_ -> v)).toMap
        val mergedBase = bm.toList.map { (k, v) =>
          keyOf(k).flatMap(overlayByKey.get) match
            case Some(ov) => k -> deepMerge(v, ov)
            case None     => k -> v
        }
        val baseKeys = bm.toList.flatMap((k, _) => keyOf(k)).toSet
        val added    = om.toList.filter((k, _) => keyOf(k).forall(ks => !baseKeys.contains(ks)))
        Node.MappingNode((mergedBase ++ added).toMap[Node, Node])
      case (_, o) => o

  /** Returns a mapping node with `key` bound to `value` (replacing any
    * existing binding); non-mapping bases become a fresh mapping.
    */
  def withEntry(base: Node, key: String, value: Node): Node =
    base match
      case Node.MappingNode(m, _) =>
        val filtered = m.toList.filterNot {
          case (Node.ScalarNode(k, _), _) => k == key
          case _                          => false
        }
        Node.MappingNode((filtered :+ ((Node.ScalarNode(key): Node) -> value)).toMap[Node, Node])
      case _ =>
        Node.MappingNode(Map[Node, Node](Node.ScalarNode(key) -> value))

  extension (node: Node)

    /** Child of a mapping node by key. */
    def field(key: String): Maybe[Node] =
      node match
        case Node.MappingNode(mappings, _) =>
          Maybe.fromOption {
            mappings.collectFirst {
              case (Node.ScalarNode(k, _), v) if k == key => v
            }
          }
        case _ => Absent

    /** Deep navigation: `node.path("model", "provider")`. */
    def path(keys: String*): Maybe[Node] =
      keys.foldLeft(Maybe(node))((acc, k) => acc.flatMap(_.field(k)))

    def str: Maybe[String] =
      node match
        case Node.ScalarNode(value, _) if value != "null" && value != "~" => Present(value)
        case _                                                            => Absent

    def bool: Maybe[Boolean] =
      node.str.flatMap {
        case "true" | "True" | "yes" | "on"   => Present(true)
        case "false" | "False" | "no" | "off" => Present(false)
        case _                                => Absent
      }

    def long: Maybe[Long]     = node.str.flatMap(s => Maybe.fromOption(s.toLongOption))
    def int: Maybe[Int]       = node.str.flatMap(s => Maybe.fromOption(s.toIntOption))
    def double: Maybe[Double] = node.str.flatMap(s => Maybe.fromOption(s.toDoubleOption))

    def seq: Maybe[List[Node]] =
      node match
        case Node.SequenceNode(nodes, _) => Present(nodes.toList)
        case _                           => Absent

    /** All key/value pairs of a mapping node, in document order. */
    def entries: Maybe[List[(String, Node)]] =
      node match
        case Node.MappingNode(mappings, _) =>
          Present(mappings.toList.collect { case (Node.ScalarNode(k, _), v) => (k, v) })
        case _ => Absent

    /** String list that also accepts a single scalar (upstream allows both for
      * keys like `agent.coding_instructions`).
      */
    def strings: Maybe[List[String]] =
      node.seq.map(_.flatMap(_.str.toList)).orElse(node.str.map(List(_)))
  end extension
end Yaml
