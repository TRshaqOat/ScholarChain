package scholarchain.rag.runtime

/** A validator for the JSON Schema subset the runtime output schemas use
  * (prompts/<name>/<schema>.schema.json): type (one name or a list), const, enum, properties,
  * required, additionalProperties (false or a schema), items, minItems, maxItems, minLength,
  * maxLength, minimum, maximum, minProperties, and if/then (then applies when the value passes if).
  * Any other keyword is refused when the schema is loaded, so a schema can never silently mean more
  * than is checked. upickle ignores unknown keys, so this check is what rejects extra keys in model
  * output. Error messages name schema paths only: a key the model chose is never echoed (it is
  * model text, and messages reach the audit and the retry prompt).
  */
object JsonSchema:
  private val Known = Set(
    "$schema",
    "$id",
    "title",
    "description",
    "type",
    "const",
    "enum",
    "properties",
    "required",
    "additionalProperties",
    "items",
    "minItems",
    "maxItems",
    "minLength",
    "maxLength",
    "minimum",
    "maximum",
    "minProperties",
    "if",
    "then"
  )

  /** Keywords outside the subset, anywhere in the schema; empty means the schema is usable. */
  def unsupported(schema: ujson.Value, at: String = "$"): Vector[String] = schema match
    case ujson.Obj(m) =>
      m.keys.filterNot(Known).map(k => s"$at: unsupported keyword $k").toVector ++
        m.get("properties")
          .toVector
          .flatMap(_.obj.toVector.flatMap((k, s) => unsupported(s, s"$at.$k"))) ++
        m.get("items").toVector.flatMap(unsupported(_, s"$at[]")) ++
        Seq("if", "then").flatMap(k => m.get(k).toVector.flatMap(unsupported(_, s"$at($k)"))) ++
        m.get("additionalProperties")
          .collect { case o: ujson.Obj => o }
          .toVector
          .flatMap(unsupported(_, s"$at.*"))
    case ujson.Bool(_) => Vector.empty
    case _             => Vector(s"$at: a schema must be an object")

  private def typeOk(t: String, v: ujson.Value): Boolean = (t, v) match
    case ("object", _: ujson.Obj)   => true
    case ("array", _: ujson.Arr)    => true
    case ("string", _: ujson.Str)   => true
    case ("boolean", _: ujson.Bool) => true
    case ("null", ujson.Null)       => true
    case ("number", _: ujson.Num)   => true
    case ("integer", ujson.Num(n))  => n.isWhole && math.abs(n) < 1e15
    case _                          => false

  /** Every violation of value against schema, with a JSON path; empty means valid. */
  def errors(schema: ujson.Value, value: ujson.Value, at: String = "$"): Vector[String] =
    schema match
      case ujson.Bool(true)  => Vector.empty
      case ujson.Bool(false) => Vector(s"$at: not allowed")
      case ujson.Obj(s)      =>
        val types = s.get("type").toVector.flatMap {
          case ujson.Str(t) => Vector(t)
          case a: ujson.Arr => a.arr.toVector.map(_.str)
          case _            => Vector.empty
        }
        val typeErr =
          Option.when(types.nonEmpty && !types.exists(typeOk(_, value)))(
            s"$at: expected ${types.mkString(" or ")}"
          )
        val constErr = s.get("const").filter(_ != value).map(_ => s"$at: not the required value")
        val enumErr =
          s.get("enum").filterNot(_.arr.contains(value)).map(_ => s"$at: not an allowed value")
        def num(k: String) = s.get(k).map(_.num)
        val shape: Vector[String] = value match
          case ujson.Str(x) =>
            Vector(
              num("minLength").filter(x.length < _).map(_ => s"$at: too short"),
              num("maxLength").filter(x.length > _).map(_ => s"$at: too long")
            ).flatten
          case ujson.Num(x) =>
            Vector(
              num("minimum").filter(x < _).map(_ => s"$at: below minimum"),
              num("maximum").filter(x > _).map(_ => s"$at: above maximum")
            ).flatten
          case ujson.Arr(xs) =>
            Vector(
              num("minItems").filter(xs.size < _).map(_ => s"$at: too few items"),
              num("maxItems").filter(xs.size > _).map(_ => s"$at: too many items")
            ).flatten ++ s
              .get("items")
              .toVector
              .flatMap(is => xs.toVector.zipWithIndex.flatMap((x, i) => errors(is, x, s"$at[$i]")))
          case ujson.Obj(o) =>
            val props: Map[String, ujson.Value] = s.get("properties").fold(Map.empty)(_.obj.toMap)
            val missing = s
              .get("required")
              .toVector
              .flatMap(_.arr.map(_.str))
              .filterNot(o.contains)
              .map(k => s"$at: missing key $k")
            val each = o.toVector.flatMap { (k, x) =>
              props.get(k) match
                case Some(ps) => errors(ps, x, s"$at.$k")
                case None     =>
                  s.get("additionalProperties") match
                    case Some(ujson.Bool(false)) => Vector(s"$at: unexpected key")
                    case Some(ap: ujson.Obj)     => errors(ap, x, s"$at.*")
                    case _                       => Vector.empty
            }
            val few = num("minProperties").filter(o.size < _).map(_ => s"$at: too few keys")
            missing ++ each ++ few
          case _ => Vector.empty
        val cond = (s.get("if"), s.get("then")) match
          case (Some(i), Some(t)) if errors(i, value, at).isEmpty => errors(t, value, at)
          case _                                                  => Vector.empty
        // A null allowed by type skips the value keywords (enum lists may omit null).
        if value == ujson.Null && types.contains("null") then typeErr.toVector
        else
          (typeErr ++ constErr ++ enumErr).toVector ++ (if typeErr.isEmpty then shape ++ cond
                                                        else Vector.empty)
      case _ => Vector(s"$at: invalid schema")

  val NotJson =
    "output-is-json: the output is not exactly one JSON object (no prose, no second object; one enclosing code fence is tolerated)"

  /** Parses text as one JSON value (nothing else around it, apart from whitespace and one enclosing
    * Markdown code fence, ruling 27) and validates it.
    */
  def parseAndValidate(schema: ujson.Value, text: String): Either[Vector[String], ujson.Value] =
    scala.util
      .Try(ujson.read(LlmOutput.unfence(text).trim))
      .toEither
      .left
      .map(_ => Vector(NotJson))
      .flatMap(v =>
        errors(schema, v) match
          case Vector() => Right(v)
          case es       => Left(es.map(e => s"schema: $e"))
      )
