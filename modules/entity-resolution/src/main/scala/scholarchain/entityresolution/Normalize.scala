package scholarchain.entityresolution

import java.text.Normalizer
import java.util.Locale

/** Versions of the pure entity-resolution definitions. A change to any rule in a file bumps that
  * file's version; Hadoop records them in MatchCandidate provenance and run manifests.
  */
object Versions:
  val Normalization = "norm-v1"
  val Blocking = "block-v1"
  val Features = "feat-v1"

/** A person name split into comparable parts (rule norm-v1).
  *
  * @param givenNames
  *   normalized given-name tokens in printed order (may be empty)
  * @param family
  *   normalized family name, tokens joined by one space, including particles such as "van der"
  * @param homonymSuffix
  *   DBLP homonym number ("0001" in "Wei Wang 0001"); never part of the comparable name
  */
final case class ParsedName(
    givenNames: Vector[String],
    family: String,
    homonymSuffix: Option[String]
):
  /** Given tokens then family, one space apart: "Smith, John" and "John Smith" give "john smith".
    */
  def comparable: String = (givenNames :+ family).filter(_.nonEmpty).mkString(" ")

  /** First letter of each given token: "Jean-Pierre K." gives "jpk". */
  def initials: String = givenNames.map(_.take(1)).mkString

  /** Family name without spaces, so "de la Cruz" and "Delacruz" share a blocking key. */
  def familyKey: String = family.replace(" ", "")

object Normalize:
  // Letters NFKD does not decompose to ASCII, folded the way bibliographic sources usually do.
  private val ExtraFolds =
    Map(
      'ß' -> "ss",
      'æ' -> "ae",
      'œ' -> "oe",
      'ø' -> "o",
      'đ' -> "d",
      'ð' -> "d",
      'ł' -> "l",
      'þ' -> "th",
      'ı' -> "i"
    )
  private val Markup = "<[^>]*>".r
  private val Homonym = "^(.*\\S)\\s+(\\d{4})$".r
  private val Particles =
    Set(
      "van",
      "von",
      "de",
      "der",
      "den",
      "del",
      "della",
      "di",
      "da",
      "du",
      "le",
      "la",
      "dos",
      "das",
      "ten",
      "ter"
    )

  /** Unicode fold: lower-case (Locale.ROOT), NFKD, combining marks removed, then ExtraFolds.
    * "Müller" gives "muller", "Łukasz" gives "lukasz", "Straße" gives "strasse". Non-Latin letters
    * are kept.
    */
  def foldUnicode(s: String): String =
    Normalizer
      .normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)
      .replaceAll("\\p{M}+", "")
      .flatMap(c => ExtraFolds.getOrElse(c, c.toString))

  /** Folded text with apostrophes deleted ("O'Brien" gives "obrien"), every other run of characters
    * that are not letters or digits turned into one space, and the result trimmed.
    */
  def text(s: String): String =
    foldUnicode(s).replaceAll("['’`]", "").replaceAll("[^\\p{L}\\p{N}]+", " ").trim

  /** Removes inline XML or HTML tags without inserting space: "H<sub>2</sub>O" gives "H2O". */
  def stripMarkup(s: String): String = Markup.replaceAllIn(s, "")

  /** Comparable title: markup stripped, then text. "<i>Deep</i> Learning." gives "deep learning".
    */
  def title(s: String): String = text(stripMarkup(s))

  /** Title tokens of [[title]]; empty for a missing or blank title. */
  def titleTokens(s: Option[String]): Vector[String] =
    s.map(title).filter(_.nonEmpty).fold(Vector.empty)(_.split(' ').toVector)

  /** Splits a trailing DBLP homonym number: "Wei Wang 0001" gives ("Wei Wang", Some("0001")). */
  def splitHomonym(raw: String): (String, Option[String]) =
    raw.trim match
      case Homonym(name, n) => (name, Some(n))
      case other            => (other, None)

  /** Parses a printed name. "Family, Given" splits at the first comma. Otherwise the last token is
    * the family name, and a run of particles before it joins the family when at least one given
    * token stays: "Wil van der Aalst" and "van der Aalst, Wil" both give given [wil], family "van
    * der aalst". Hyphens and periods separate tokens, so "J.-P." gives initials "jp".
    */
  def name(raw: String): ParsedName =
    val (printed, suffix) = splitHomonym(raw)
    def tokens(s: String) = text(s).split(' ').toVector.filter(_.nonEmpty)
    printed.indexOf(',') match
      case i if i >= 0 =>
        ParsedName(tokens(printed.drop(i + 1)), tokens(printed.take(i)).mkString(" "), suffix)
      case _ =>
        val t = tokens(printed)
        if t.isEmpty then ParsedName(Vector.empty, "", suffix)
        else
          val before = t.init
          val particles = before.reverse.takeWhile(Particles).size.min(before.size - 1).max(0)
          val (givenNames, familyParticles) = before.splitAt(before.size - particles)
          ParsedName(givenNames, (familyParticles :+ t.last).mkString(" "), suffix)
