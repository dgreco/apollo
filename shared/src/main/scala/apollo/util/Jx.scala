package apollo.util

import kyo.*
import kyo.Structure.Value

/** Ergonomic construction and navigation of `Structure.Value` — Kyo's dynamic
  * JSON tree. Provider adapters speak externally-owned wire formats (OpenAI
  * chat completions, Anthropic messages, SSE payloads), so a dynamic AST at
  * that boundary beats fighting typed schema discriminators to mirror
  * someone else's contract.
  */
object Jx:

  // --- construction -------------------------------------------------------

  def obj(fields: (String, Value)*): Value = Value.Record(Chunk.from(fields))
  def arr(values: Value*): Value           = Value.Sequence(Chunk.from(values))
  def arr(values: Iterable[Value]): Value  = Value.Sequence(Chunk.from(values))
  def str(s: String): Value                = Value.Str(s)
  def num(n: Long): Value                  = Value.Integer(n)
  def num(n: Int): Value                   = Value.Integer(n.toLong)
  def num(n: Double): Value                = Value.Decimal(n)
  def bool(b: Boolean): Value              = Value.Bool(b)
  val nul: Value                           = Value.Null

  /** An object that omits `Absent` fields entirely (most APIs treat missing
    * and null differently).
    */
  def objOf(fields: (String, Maybe[Value])*): Value =
    Value.Record(Chunk.from(fields.collect { case (k, Present(v)) => (k, v) }))

  // --- parsing / rendering ------------------------------------------------

  def parse(json: String): Result[String, Value] =
    Json.decode[Value](json).mapFailure(_.getMessage)

  def render(value: Value): String = Json.encode(value)

  // --- navigation ---------------------------------------------------------

  extension (value: Value)

    def field(name: String): Maybe[Value] =
      value match
        case Value.Record(fields) =>
          Maybe.fromOption(fields.collectFirst { case (k, v) if k == name => v })
        case _ => Absent

    /** `json / "a" / "b"` style deep access. */
    def /(name: String): Maybe[Value] = value.field(name)

    def asStr: Maybe[String] =
      value match
        case Value.Str(s) => Present(s)
        case _            => Absent

    def asLong: Maybe[Long] =
      value match
        case Value.Integer(n) => Present(n)
        case Value.Decimal(d) => Present(d.toLong)
        case Value.BigNum(b)  => Present(b.toLong)
        case _                => Absent

    def asDouble: Maybe[Double] =
      value match
        case Value.Integer(n) => Present(n.toDouble)
        case Value.Decimal(d) => Present(d)
        case Value.BigNum(b)  => Present(b.toDouble)
        case _                => Absent

    def asBool: Maybe[Boolean] =
      value match
        case Value.Bool(b) => Present(b)
        case _             => Absent

    def asArr: Maybe[Chunk[Value]] =
      value match
        case Value.Sequence(elems) => Present(elems)
        case _                     => Absent

    def asObj: Maybe[Chunk[(String, Value)]] =
      value match
        case Value.Record(fields) => Present(fields)
        case _                    => Absent

    def isNull: Boolean = value == Value.Null

    /** Returns a copy of an object value with `name` set (replacing any
      * existing binding), or the value unchanged when it isn't an object.
      */
    def withField(name: String, v: Value): Value =
      value match
        case Value.Record(fields) =>
          Value.Record(fields.filter(_._1 != name).append(name -> v))
        case other => other

    /** Deep-merges `overrides` into this object: object-vs-object merges
      * recursively, anything else is replaced by the override.
      */
    def deepMerge(overrides: Value): Value =
      (value, overrides) match
        case (Value.Record(_), Value.Record(overrideFields)) =>
          overrideFields.foldLeft(value) { case (acc, (k, ov)) =>
            val merged = acc.field(k) match
              case Present(existing) => existing.deepMerge(ov)
              case Absent            => ov
            acc.withField(k, merged)
          }
        case _ => overrides
  end extension

  extension (maybe: Maybe[Value])
    def field(name: String): Maybe[Value]        = maybe.flatMap(_.field(name))
    def /(name: String): Maybe[Value]            = maybe.flatMap(_.field(name))
    def asStr: Maybe[String]                     = maybe.flatMap(_.asStr)
    def asLong: Maybe[Long]                      = maybe.flatMap(_.asLong)
    def asDouble: Maybe[Double]                  = maybe.flatMap(_.asDouble)
    def asBool: Maybe[Boolean]                   = maybe.flatMap(_.asBool)
    def asArr: Maybe[Chunk[Value]]               = maybe.flatMap(_.asArr)
    def asObj: Maybe[Chunk[(String, Value)]]     = maybe.flatMap(_.asObj)
  end extension
end Jx
