package scholarchain.storage

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.{EdgeType, StableIds}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

class NeptuneSpec extends AnyFunSuite:
  import Fixtures.*
  import NeptuneContract.*

  private def codes(r: Either[Vector[Violation], ?]): Set[String] =
    r.left.toOption.getOrElse(Vector.empty).map(_.code).toSet

  private def loadInto(g: InMemoryGraph, root: Path, ev: Option[ObjectReadPort] = None) =
    g.loader.load(root, windows, ev)

  test("CSV serialization round trip keeps every typed value, including quotes and commas") {
    for (spec, elems) <- graph; e <- elems do
      val parsed = Csv.parseString(Csv.render(renderRow(spec, e)) + "\n")
      assert(parsed.size == 1)
      val (back, issues) =
        NeptuneCsv.parseRow(spec, parsed.head, ProvenanceRules.windowsByName(windows))
      assert(issues.isEmpty, s"${spec.name}: $issues")
      assert(back.contains(e))
  }

  test("edge IDs follow the core formulas") {
    val authored = graph(Authored).head
    assert(StableIds.graphEdgeId(EdgeType.AUTHORED, ra, w1, policy, Nil) == Right(authored.id))
    val w = windows.head
    assert(
      graph(CoauthoredWith).head.id == StableIds
        .coauthorEdgeId(rb, ra, w.startYear, w.endYear, policy)
        .toOption
        .get
    )
  }

  test("a valid directory loads, supports the pair lookup, and reloads idempotently") {
    withTemp { root =>
      writeGraph(root, graph)
      val g = InMemoryGraph()
      val first = loadInto(g, root)
      assert(first == Right(GraphLoadReport(7, 7, 0, 0)))
      val again = loadInto(g, root)
      assert(again == Right(GraphLoadReport(0, 0, 7, 7)))
      assert(g.vertexCount == 7 && g.edgeCount == 7)
      val e = g.reader.coauthorEdge(rb, ra, "all-years")
      assert(e.map(_.props("jointPublicationCount")).contains(2L))
      assert(g.reader.coauthorEdge(ra, rb, "2000-2014").isEmpty)
      assert(g.reader.coauthors(ra, "all-years", 10).map(_.id) == e.toVector.map(_.id))
      assertThrows[IllegalArgumentException](g.reader.coauthors(ra, "all-years", 0))
    }
  }

  test("a reload that changes an existing element is refused and changes nothing") {
    withTemp { root =>
      writeGraph(root, graph)
      val g = InMemoryGraph()
      loadInto(g, root)
      writeGraph(
        root,
        graph.updated(
          Researcher,
          Vector(
            researcherV(ra).copy(props = researcherV(ra).props.updated("mentionCount", 9L)),
            researcherV(rb),
            researcherV(rc)
          )
        )
      )
      assert(codes(loadInto(g, root)) == Set("PROPERTY_CONFLICT"))
      assert(g.reader.vertex(ra).map(_.props("mentionCount")).contains(2L))
    }
  }

  test("coauthor edges need existing endpoints of the right label") {
    withTemp { root =>
      val stranger = researcher("zzz-missing")
      val (a, b) = if stranger < ra then (stranger, ra) else (ra, stranger)
      writeGraph(root, graph.updated(CoauthoredWith, Vector(coauthor(a, b, Vector(w1)))))
      // The missing researcher also has no AUTHORED edge to the cited work.
      assert(
        codes(loadInto(InMemoryGraph(), root)) == Set(
          "EDGE_ENDPOINT_MISSING",
          "EVIDENCE_NOT_AUTHORED"
        )
      )
    }
    withTemp { root =>
      writeGraph(
        root,
        graph.updated(Authored, graph(Authored) :+ edge(Authored, ra, venue, "position" -> 1))
      )
      assert(codes(loadInto(InMemoryGraph(), root)) == Set("EDGE_ENDPOINT_LABEL"))
    }
  }

  test("F2: inline supporting works must be Work vertices that both endpoints AUTHORED") {
    def scan(g: Map[FileSpec, Vector[GraphElement]]) = withTemp { root =>
      writeGraph(root, g)
      NeptuneCsv.scan(root, windows, None)
    }
    val ok = scan(graph)
    assert(ok.violations.isEmpty)
    assert(ok.evidenceChecks == Map("edges-inline" -> 1L, "work-ids-traced" -> 2L))
    // A well-formed work ID with no Work vertex: the review's failure input.
    val ghost = "dblp:conf/x/Y20"
    val missing = scan(graph.updated(CoauthoredWith, Vector(coauthor(ra, rb, Vector(w1, ghost)))))
    assert(missing.violations.map(_.code).toSet == Set("EVIDENCE_WORK_MISSING"))
    assert(missing.violations.head.detail.contains(ghost))
    assert(missing.ruleCounts.get("EVIDENCE_WORK_MISSING").contains(1L))
    // rb never AUTHORED w2.
    val notAuthored =
      scan(graph.updated(Authored, graph(Authored).filterNot(e => e.from == rb && e.to == w2)))
    assert(notAuthored.violations.map(_.code).toSet == Set("EVIDENCE_NOT_AUTHORED"))
    assert(notAuthored.violations.head.detail.contains(s"$rb did not author $w2"))
  }

  test("coauthor edges need evidence that matches the joint publication count") {
    val base = coauthor(ra, rb, Vector(w1, w2))
    def check(e: GraphElement, expected: String) = withTemp { root =>
      writeGraph(root, graph.updated(CoauthoredWith, Vector(e)))
      assert(codes(loadInto(InMemoryGraph(), root)).contains(expected), expected)
    }
    check(base.copy(props = base.props - "supportingWorkIds"), "MISSING_EVIDENCE")
    check(
      base.copy(props = base.props.updated("jointPublicationCount", 3L)),
      "EVIDENCE_COUNT_MISMATCH"
    )
    check(base.copy(props = base.props.updated("supportingWorkIds", s"$w2 $w1")), "BAD_EVIDENCE")
    check(
      base.copy(props = base.props.updated("supportingWorkIdsRef", "curated/x")),
      "AMBIGUOUS_EVIDENCE"
    )
    check(base.copy(from = rb, to = ra), "NON_CANONICAL_PAIR")
    check(base.copy(props = base.props.updated("lastCollaborationYear", 2030)), "BAD_YEARS")
    check(base.copy(id = "cae-" + "0" * 32), "EDGE_ID_MISMATCH")
  }

  test("long evidence lists go through an S3 evidence object that must exist, match, and trace") {
    val works = (1 to 21).map(i => f"dblp:conf/test/L$i%02d").toVector
    val inline = coauthor(ra, rb, works)
    val ref = S3Layout.coauthorEvidence(snapshot, runId, "all-years", inline.id)
    val e =
      inline.copy(props = inline.props - "supportingWorkIds" + ("supportingWorkIdsRef" -> ref))
    val authoredLong =
      for r <- Vector(ra, rb); w <- works yield edge(Authored, r, w, "position" -> 1)
    def longGraph(co: GraphElement, authored: Vector[GraphElement] = authoredLong) =
      graph
        .updated(Work, graph(Work) ++ works.map(w => vertex(Work, w, "dblpAnchored" -> true)))
        .updated(Authored, graph(Authored) ++ authored)
        .updated(CoauthoredWith, Vector(co))
    val good = CoauthorEvidence(EvidenceSchemaVersion, e.id, "all-years", works, runId, snapshot)
    // Each case gets its own write-once object store.
    def withObject(g: Map[FileSpec, Vector[GraphElement]], ev: Option[CoauthorEvidence]) =
      withTemp { root =>
        writeGraph(root.resolve("g"), g)
        val store = FileObjectStore(root.resolve("s3"))
        ev.foreach(x => store.loader.putNew(ref, upickle.default.write(x).getBytes(UTF_8)))
        val scan = NeptuneCsv.scan(root.resolve("g"), windows, Some(store.reader))
        (codes(loadInto(InMemoryGraph(), root.resolve("g"), Some(store.reader))), scan)
      }
    withTemp { root =>
      writeGraph(root, longGraph(inline))
      assert(codes(loadInto(InMemoryGraph(), root)) == Set("EVIDENCE_TOO_LONG"))
    }
    assert(withObject(longGraph(e), None)._1 == Set("BAD_EVIDENCE_REF"))
    val (okCodes, okScan) = withObject(longGraph(e), Some(good))
    assert(okCodes.isEmpty)
    assert(okScan.evidenceChecks == Map("edges-object" -> 1L, "work-ids-traced" -> 21L))
    // M4: order, runId, and snapshot of the object.
    val reversed = good.copy(workIds = works.reverse)
    assert(withObject(longGraph(e), Some(reversed))._1 == Set("EVIDENCE_OBJECT_ORDER"))
    val dup = good.copy(workIds = works.updated(1, works(0)))
    assert(withObject(longGraph(e), Some(dup))._1.contains("EVIDENCE_OBJECT_ORDER"))
    assert(
      withObject(longGraph(e), Some(good.copy(runId = otherRunId)))._1 == Set(
        "EVIDENCE_OBJECT_PROVENANCE"
      )
    )
    assert(
      withObject(longGraph(e), Some(good.copy(snapshot = "2026-01-01")))._1 == Set(
        "EVIDENCE_OBJECT_PROVENANCE"
      )
    )
    // F2 on the object's work IDs: one not a Work vertex, one rb never AUTHORED.
    val ghost = good.copy(workIds = works.updated(20, "dblp:conf/test/L99"))
    assert(withObject(longGraph(e), Some(ghost))._1 == Set("EVIDENCE_WORK_MISSING"))
    val lessAuthored = authoredLong.filterNot(x => x.from == rb && x.to == works(4))
    assert(
      withObject(longGraph(e, lessAuthored), Some(good))._1 == Set("EVIDENCE_NOT_AUTHORED")
    )
    // Without an evidence root the object is not opened; the scan says so.
    withTemp { root =>
      writeGraph(root, longGraph(e))
      val s = NeptuneCsv.scan(root, windows, None)
      assert(s.violations.isEmpty && s.evidenceChecks == Map("edges-object-not-opened" -> 1L))
    }
  }

  test("headers, types, provenance, duplicates, and stray files are reported") {
    withTemp { root =>
      writeGraph(
        root,
        graph.updated(
          Researcher,
          Vector(researcherV(ra), researcherV(ra), researcherV(rb), researcherV(rc))
        )
      )
      assert(codes(loadInto(InMemoryGraph(), root)) == Set("DUPLICATE_ID"))
    }
    withTemp { root =>
      writeGraph(root, graph)
      val f = root.resolve("vertices/researcher/part-00000.csv")
      Files.writeString(f, Files.readString(f).replace(",true,", ",yes,"))
      Files.writeString(root.resolve("_SUCCESS"), "")
      assert(codes(loadInto(InMemoryGraph(), root)) == Set("BAD_TYPE", "UNKNOWN_FILE"))
    }
    withTemp { root =>
      writeGraph(
        root,
        graph.updated(
          Venue,
          Vector(
            vertex(Venue, venue, "name" -> "V")
              .copy(props = vertex(Venue, venue, "name" -> "V").props.updated("runId", "run-1"))
          )
        )
      )
      Files.createDirectories(root.resolve("edges/has-topic"))
      Files.writeString(root.resolve("edges/has-topic/part-00000.csv"), "a,b\n")
      assert(codes(loadInto(InMemoryGraph(), root)) == Set("BAD_PROVENANCE", "BAD_HEADER"))
    }
  }
