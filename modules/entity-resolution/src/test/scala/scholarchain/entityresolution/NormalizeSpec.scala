package scholarchain.entityresolution

import org.scalatest.funsuite.AnyFunSuite

class NormalizeSpec extends AnyFunSuite:
  import Normalize.*

  test("foldUnicode: decomposition, diacritics, letters NFKD keeps, ligatures, non-Latin") {
    assert(foldUnicode("Müller") == "muller")
    assert(foldUnicode("Łukasz Ørsted") == "lukasz orsted")
    assert(foldUnicode("Straße") == "strasse")
    assert(foldUnicode("İstanbul") == "istanbul")
    assert(foldUnicode("ﬁle") == "file")
    assert(foldUnicode("Gómez-Peña") == "gomez-pena")
    assert(foldUnicode("清华大学") == "清华大学")
  }

  test("text: punctuation, apostrophes, and whitespace") {
    assert(text("  O'Brien,   J.-P.  ") == "obrien j p")
    assert(text("D’Angelo\t\n(ed.)") == "dangelo ed")
    assert(text("...") == "")
  }

  test("title markup is stripped without inserting space") {
    assert(stripMarkup("H<sub>2</sub>O and <i>x</i><sup>2</sup>") == "H2O and x2")
    assert(title("<i>Deep</i> Residual   Learning.") == "deep residual learning")
    assert(titleTokens(Some("On <sc>Graphs</sc>")) == Vector("on", "graphs"))
    assert(titleTokens(Some(" <i></i> ")).isEmpty)
    assert(titleTokens(None).isEmpty)
  }

  test("DBLP homonym suffix is split off and kept") {
    assert(splitHomonym("Wei Wang 0001") == ("Wei Wang", Some("0001")))
    assert(splitHomonym(" Wei Wang  0042 ") == ("Wei Wang", Some("0042")))
    assert(splitHomonym("Wei Wang") == ("Wei Wang", None))
    assert(splitHomonym("Route 66") == ("Route 66", None))
    val n = name("Wei Wang 0001")
    assert(n == ParsedName(Vector("wei"), "wang", Some("0001")))
    assert(n.comparable == "wei wang")
  }

  test("name order: 'Given Family' and 'Family, Given' agree") {
    assert(name("Wang, Wei").comparable == "wei wang")
    assert(name("Wang, Wei") == name("Wei Wang").copy(homonymSuffix = None))
    val direct = name("Wil M. P. van der Aalst")
    assert(direct == ParsedName(Vector("wil", "m", "p"), "van der aalst", None))
    assert(name("van der Aalst, Wil M. P.") == direct)
    assert(direct.familyKey == "vanderaalst")
    assert(name("Van Morrison") == ParsedName(Vector("van"), "morrison", None))
  }

  test("initials from hyphenated, dotted, and full given names") {
    assert(name("Jean-Pierre Müller").initials == "jp")
    assert(name("J.-P. Müller").initials == "jp")
    assert(name("J. R. R. Tolkien").initials == "jrr")
    assert(name("Madonna") == ParsedName(Vector.empty, "madonna", None))
    assert(name("Madonna").initials == "")
  }

  test("empty and punctuation-only names parse to empty") {
    assert(name("") == ParsedName(Vector.empty, "", None))
    assert(name(" .- ").comparable == "")
    assert(name("0001").comparable == "0001") // a bare number has no name before it to split
  }
