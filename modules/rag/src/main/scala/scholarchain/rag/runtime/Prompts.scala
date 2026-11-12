package scholarchain.rag.runtime

import scholarchain.core.StableIds

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** One versioned prompt file (RUNTIME.md section 16):
  * {{{
  * prompt-version: <version>
  * output-schema: <schema version>
  * === system ===
  * ...
  * === user ===
  * ...
  * === retry ===
  * ...
  * }}}
  * `{{name}}` placeholders are filled in one pass, so filled-in text is never scanned again. The
  * output schema is `<dir>/<schema version>.schema.json` next to the prompt.
  */
final case class PromptTemplate(
    version: String,
    sha256: String,
    schemaVersion: String,
    schema: ujson.Value,
    schemaSha256: String,
    sections: Map[String, String]
):
  /** The section with every placeholder filled; Left when a placeholder has no value. */
  def render(section: String, values: Map[String, String]): Either[String, String] =
    sections.get(section).toRight(s"prompt $version has no $section section").flatMap { t =>
      val missing =
        PromptTemplate.Placeholder.findAllMatchIn(t).map(_.group(1)).filterNot(values.contains)
      if missing.nonEmpty then Left(s"prompt $version: no value for ${missing.mkString(", ")}")
      else
        Right(
          PromptTemplate.Placeholder.replaceAllIn(
            t,
            m => java.util.regex.Matcher.quoteReplacement(values(m.group(1)))
          )
        )
    }

object PromptTemplate:
  val Placeholder = "\\{\\{([a-z_]+)\\}\\}".r
  private val Section = "(?m)^=== ([a-z]+) ===$".r

  def parse(
      text: String,
      schemaText: String,
      expectVersion: String
  ): Either[String, PromptTemplate] =
    val lines = text.split("\n", -1).toVector
    for
      version <- lines.headOption
        .collect { case s"prompt-version: $v" => v.trim }
        .toRight("the first line must be 'prompt-version: <version>'")
      _ <- Either.cond(
        version == expectVersion,
        (),
        s"prompt header $version is not $expectVersion"
      )
      schemaVersion <- lines
        .lift(1)
        .collect { case s"output-schema: $v" => v.trim }
        .toRight("the second line must be 'output-schema: <schema version>'")
      schema <- scala.util
        .Try(ujson.read(schemaText))
        .toEither
        .left
        .map(e => s"schema: ${e.getMessage}")
      _ <- JsonSchema.unsupported(schema) match
        case Vector() => Right(())
        case es       => Left(es.mkString("; "))
      body = lines.drop(2).mkString("\n")
      marks = Section.findAllMatchIn(body).toVector
      _ <- Either.cond(marks.nonEmpty, (), "no '=== <section> ===' line")
    yield
      val sections = marks.zipWithIndex.map { (m, i) =>
        val end = marks.lift(i + 1).fold(body.length)(_.start)
        m.group(1) -> body.substring(m.end, end).trim
      }.toMap
      PromptTemplate(
        version,
        StableIds.textHash(text),
        schemaVersion,
        schema,
        StableIds.textHash(schemaText),
        sections
      )

  /** Loads `<dir>/<version>.md` and its output schema. */
  def load(dir: Path, version: String): Either[String, PromptTemplate] =
    def read(p: Path) =
      Either.cond(Files.isRegularFile(p), new String(Files.readAllBytes(p), UTF_8), s"missing $p")
    for
      text <- read(dir.resolve(s"$version.md"))
      schemaName <- text
        .split("\n")
        .lift(1)
        .collect { case s"output-schema: $v" => v.trim }
        .toRight(s"$version: no output-schema line")
      schemaText <- read(dir.resolve(s"$schemaName.schema.json"))
      t <- parse(text, schemaText, version)
    yield t

/** The planner and synthesis prompts named in configuration (runtime.prompts-dir). */
final case class PromptSet(planner: PromptTemplate, synthesis: PromptTemplate):
  /** Audit form: versions and sha256 of each prompt and schema file. */
  def versions: Map[String, String] = Map(
    "planner" -> planner.version,
    "planner.sha256" -> planner.sha256,
    "planner.schema" -> planner.schemaVersion,
    "planner.schema.sha256" -> planner.schemaSha256,
    "synthesis" -> synthesis.version,
    "synthesis.sha256" -> synthesis.sha256,
    "synthesis.schema" -> synthesis.schemaVersion,
    "synthesis.schema.sha256" -> synthesis.schemaSha256
  )

object PromptSet:
  def load(
      root: Path,
      plannerVersion: String,
      synthesisVersion: String
  ): Either[String, PromptSet] =
    for
      p <- PromptTemplate.load(root.resolve("planner"), plannerVersion)
      _ <- Either.cond(
        p.schemaVersion == RuntimeSchemas.PlannerOutput,
        (),
        s"planner schema ${p.schemaVersion}"
      )
      s <- PromptTemplate.load(root.resolve("synthesis"), synthesisVersion)
      _ <- Either.cond(
        RuntimeSchemas.SynthesisOutputVersions.contains(s.schemaVersion),
        (),
        s"synthesis schema ${s.schemaVersion}"
      )
    yield PromptSet(p, s)
