package scholarchain.entityresolution

import org.scalatest.funsuite.AnyFunSuite

class BlockingSpec extends AnyFunSuite:
  import BlockingKeys.*
  import OversizePolicy.*

  /** Each level's key body starts with the previous level's body (the refinement property). */
  private def refines(keys: Vector[Vector[String]]): Boolean =
    def body(k: String) = k.drop(3)
    keys
      .zip(keys.tail)
      .forall((prev, next) => next.forall(n => prev.exists(p => body(n).startsWith(body(p)))))

  test("researcher keys: primary, first-name fallback, coauthor fallback") {
    val keys = researcher("Wei Wang 0001", Seq("Li Zhang", "Ming Liu", "Hui Zhang"))
    assert(
      keys == Vector(
        Vector("r0:wang|w"),
        Vector("r1:wang|wei"),
        Vector("r2:wang|wei|liu", "r2:wang|wei|zhang")
      )
    )
    assert(keys.size == ResearcherLevels)
    assert(refines(keys))
  }

  test("researcher keys ignore name order and homonym suffix; no coauthors gives '-'") {
    assert(researcher("Wang, Wei", Nil) == researcher("Wei Wang 0002", Nil))
    assert(researcher("Wang, Wei", Nil)(2) == Vector("r2:wang|wei|-"))
    assert(
      researcher("Madonna", Nil)
        .map(_.head) == Vector("r0:madonna|-", "r1:madonna|-", "r2:madonna|-|-")
    )
    assert(researcher("Wil van der Aalst", Nil).head == Vector("r0:vanderaalst|w"))
  }

  test("researcher keys are missing for names without letters or digits") {
    assert(researcher("", Seq("Li Zhang")).isEmpty)
    assert(researcher(" .- ", Nil).isEmpty)
  }

  test("work keys: primary title prefix, year fallback, full-title fallback") {
    val keys = work(Some("<i>Deep</i> Residual Learning for Image Recognition."), Some(2016))
    assert(
      keys == Vector(
        Vector("w0:deep_residual_learning"),
        Vector("w1:deep_residual_learning|2016"),
        Vector("w2:deep_residual_learning|2016|deep_residual_learning_for_image_recognition")
      )
    )
    assert(keys.size == WorkLevels)
    assert(refines(keys))
  }

  test("work keys: leading stopwords skipped, all-stopword titles, missing year and title") {
    assert(work(Some("On the Theory of Graphs"), None).head == Vector("w0:theory_graphs"))
    assert(
      work(Some("On the"), None) == Vector(
        Vector("w0:on_the"),
        Vector("w1:on_the|-"),
        Vector("w2:on_the|-|on_the")
      )
    )
    assert(work(None, Some(2020)).isEmpty)
    assert(work(Some("<b></b>"), Some(2020)).isEmpty)
  }

  test("oversize action: keep at the limit, re-key above it, report at the last level") {
    assert(action(0, 3, 3, 3) == Action.Keep)
    assert(action(0, 4, 3, 3) == Action.Rekey(1))
    assert(action(1, 4, 3, 3) == Action.Rekey(2))
    assert(action(2, 4, 3, 3) == Action.ReportOversize)
    assertThrows[IllegalArgumentException](action(0, 1, 0, 3))
  }

  private val mentions = Vector(
    ("m1", "Wei Wang 0001", Seq("Li Zhang")),
    ("m2", "Wei Wang", Seq("Li Zhang")),
    ("m3", "Wei Wang", Seq("Ming Liu")),
    ("m4", "Wen Wang", Nil),
    ("m5", "W. Wang", Seq("Ming Liu")),
    ("m6", "Ana Silva", Nil),
    ("m7", "", Nil)
  )
  private def researcherPlan(ms: Seq[(String, String, Seq[String])], max: Int) =
    plan(ms, _._1, m => researcher(m._2, m._3), ResearcherLevels, max)

  test("plan re-keys oversize researcher blocks level by level") {
    val p = researcherPlan(mentions, 2)
    assert(
      p.blocks == Vector(
        Block(0, "r0:silva|a", Vector("m6")),
        Block(1, "r1:wang|w", Vector("m5")),
        Block(1, "r1:wang|wen", Vector("m4")),
        Block(2, "r2:wang|wei|liu", Vector("m3")),
        Block(2, "r2:wang|wei|zhang", Vector("m1", "m2"))
      )
    )
    assert(p.oversize.isEmpty)
    assert(p.missingKey == Vector("m7"))
    assert(researcherPlan(mentions.reverse, 2) == p)
  }

  test("plan keeps blocks within the limit and reports blocks oversize at the last level") {
    val roomy = researcherPlan(mentions, 1000)
    assert(roomy.blocks.map(_.key) == Vector("r0:silva|a", "r0:wang|w"))
    assert(roomy.blocks(1).memberIds == Vector("m1", "m2", "m3", "m4", "m5"))
    val tight = researcherPlan(mentions, 1)
    assert(tight.oversize == Vector(Oversize(2, "r2:wang|wei|zhang", 2)))
    assert(!tight.blocks.exists(_.key == "r2:wang|wei|zhang"))
    assert(tight.blocks.map(_.key).contains("r2:wang|wei|liu"))
  }

  test("plan re-keys an oversize work block by year") {
    val works = Vector(
      ("w-a", Some("Graph Neural Networks"), Some(2020)),
      ("w-b", Some("Graph neural networks."), Some(2020)),
      ("w-c", Some("Graph Neural Networks"), Some(2021))
    )
    val p = plan(works, _._1, w => work(w._2, w._3), WorkLevels, 2)
    assert(
      p.blocks == Vector(
        Block(1, "w1:graph_neural_networks|2020", Vector("w-a", "w-b")),
        Block(1, "w1:graph_neural_networks|2021", Vector("w-c"))
      )
    )
  }
