package scholarchain.rag

import scholarchain.core.config.TimeWindow
import scholarchain.core.{Provenance, SchemaVersions, SourceType, StableIds}
import scholarchain.storage.{EmbeddingCandidate, EmbeddingCandidates, S3Layout}
import upickle.default.write

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.Comparator

/** Hand-made candidates of every document type (SELECTION.md shapes, Phase 6 facts shapes). */
object Fixtures:
  val windows = Vector(
    TimeWindow("all-years", 2000, 2026),
    TimeWindow("2000-2014", 2000, 2014),
    TimeWindow("2015-2026", 2015, 2026)
  )
  val allYears = windows.head
  val snapshot = "2026-10-03"
  val sourceRun = StableIds.runId("spark-store-conversion", "src-test", "config-v1", Seq("x"), 1)
  val env = Map("SCHOLARCHAIN_EMBEDDING_MODEL_ID" -> EmbeddingModels.TitanTextV2.modelId)

  def researcher(s: String) = s"scr:canon-v1.er-v2:${StableIds.hash("r", s)}"
  def community(label: String, w: TimeWindow = allYears) =
    StableIds.communityId("graph-v1", w.name, w.startYear, w.endYear, researcher(label))
  def pair(s: String) = s"pair-${StableIds.hash("pair", s)}"
  def obj(kv: (String, ujson.Value)*): ujson.Obj = ujson.Obj.from(kv)
  def arr(xs: ujson.Value*): ujson.Arr = ujson.Arr.from(xs)
  def named(id: String, name: String, n: Int): ujson.Obj = obj("id" -> id, "name" -> name, "n" -> n)

  val people = Vector(
    "Ada Lovelace",
    "Grace Hopper",
    "Edsger Dijkstra",
    "Barbara Liskov",
    "Donald Knuth",
    "Frances Allen"
  )
  val topicNames = Vector(
    "Quantum Annealing Hardware",
    "Distributed Consensus Protocols",
    "Program Verification Logic",
    "Compiler Loop Optimization"
  )
  def topicId(i: Int) = s"openalex:T1000$i"
  def venueId(i: Int) = s"dblp-venue:conf/v$i"
  def workId(i: Int) = s"dblp:conf/v$i/Work$i"

  def profileFacts(i: Int): ujson.Obj =
    val me = people(i)
    val co = people((i + 1) % people.size)
    obj(
      "researcherId" -> researcher(me),
      "displayName" -> me,
      "publicationCount" -> (3 + i),
      "distinctCoauthorCount" -> 4,
      "totalCoauthorOccurrences" -> 5,
      "averageCoauthorsPerPaper" -> 1.25,
      "maximumCoauthorsOnOnePaper" -> 2,
      "weightedDegree" -> 5.0,
      "coreness" -> 2,
      "componentId" -> s"comp-${StableIds.hash("c")}",
      "componentSize" -> 120,
      "inLargestComponent" -> true,
      "communityId" -> community(people(i % 2)),
      "communitySize" -> 3,
      "crossCommunityDegree" -> 1,
      "communitiesConnected" -> 2,
      "participationCoefficient" -> 0.375,
      "firstYear" -> 2004,
      "lastYear" -> 2021,
      "coauthors" -> arr(
        obj(
          "id" -> researcher(co),
          "name" -> co,
          "n" -> 2,
          "edgeId" -> s"cae-${StableIds.hash(me, co)}"
        )
      ),
      "topics" -> arr(named(topicId(i % 4), topicNames(i % 4), 2)),
      "venues" -> arr(named(venueId(i), s"Venue$i Symposium", 1)),
      "institutions" -> arr(obj("id" -> s"ror:inst$i", "name" -> s"Institute $i")),
      "recentWorks" -> arr(
        obj("id" -> workId(i), "title" -> s"Unique Study Number $i Of $me.", "year" -> 2021)
      )
    )

  def communityFacts(i: Int): ujson.Obj = obj(
    "communityId" -> community(people(i)),
    "communitySize" -> 3,
    "internalEdges" -> 3,
    "crossCommunityEdges" -> 2,
    "density" -> 1.0,
    "linkedCommunities" -> 1,
    "works" -> 4,
    "topMembers" -> arr(
      named(researcher(people(i)), people(i), 3),
      named(researcher(people(i + 2)), people(i + 2), 2)
    ),
    "topics" -> arr(named(topicId(i), topicNames(i), 2)),
    "venues" -> arr(named(venueId(i), s"Venue$i Symposium", 2))
  )

  def pathFacts(i: Int): ujson.Obj =
    val (s, mid, t) = (people(i), people(i + 1), people(i + 2))
    if i == 2 then
      obj(
        "pairId" -> pair(s + t),
        "status" -> "UNREACHABLE",
        "maxDepth" -> 6,
        "sourceResearcherId" -> researcher(s),
        "targetResearcherId" -> researcher(t),
        "sourceName" -> s,
        "targetName" -> t
      )
    else
      def hop(h: Int, who: String, yr: Int) = obj(
        "hop" -> h,
        "id" -> researcher(who),
        "name" -> who,
        "edgeId" -> s"cae-${StableIds.hash(who, h.toString)}",
        "n" -> h,
        "first" -> yr,
        "last" -> (yr + h - 1),
        "workId" -> workId(h),
        "title" -> s"Joint Paper $who"
      )
      obj(
        "pairId" -> pair(s + t),
        "status" -> "FOUND",
        "pathLength" -> 2,
        "maxDepth" -> 6,
        "componentId" -> s"comp-${StableIds.hash("c")}",
        "sourceResearcherId" -> researcher(s),
        "targetResearcherId" -> researcher(t),
        "sourceName" -> s,
        "targetName" -> t,
        "hops" -> arr(hop(1, mid, 2010), hop(2, t, 2015))
      )

  def topicFacts(i: Int): ujson.Obj = obj(
    "topicId" -> topicId(i),
    "name" -> topicNames(i),
    "field" -> "Computer Science",
    "subfield" -> s"Subfield$i",
    "works" -> (10 + i),
    "researchers" -> (20 + i),
    "topResearchers" -> arr(named(researcher(people(i)), people(i), 3)),
    "venues" -> arr(named(venueId(i), s"Venue$i Symposium", 4))
  )

  def paperFacts(i: Int): ujson.Obj = obj(
    "workId" -> workId(i),
    "title" -> s"Unique Study Number $i Of ${people(i)}.",
    "year" -> 2021,
    "workType" -> "inproceedings",
    "venueId" -> venueId(i),
    "venueName" -> s"Venue$i Symposium",
    "authorCount" -> 2,
    "authors" -> arr(obj("id" -> researcher(people(i)), "name" -> people(i), "position" -> 1)),
    "topics" -> arr(obj("id" -> topicId(i % 4), "name" -> topicNames(i % 4)))
  )

  val indexOf = Map(
    "researcher-profile" -> "researcher-profile-v1",
    "collaboration-community" -> "collaboration-community-v1",
    "collaboration-path" -> "collaboration-path-v1",
    "topic-summary" -> "topic-institution-summary-v1",
    "paper-summary" -> "paper-abstract-v1"
  )

  /** A candidate whose storage fields agree with its facts (Phase 6 summary text stand-in). */
  def candidate(
      docType: String,
      facts: ujson.Obj,
      rank: Int,
      window: String = "all-years"
  ): RenderInput =
    val d = Render.derive(docType, facts).fold(e => sys.error(e), identity)
    val index = indexOf(docType)
    val template = if docType == "paper-summary" then "source-title-v1" else "summary-template-v1"
    val text = s"Phase 6 text for ${d.entityIds.head} in $window."
    val hash = StableIds.textHash(text)
    val key = StableIds.chunkId(s"${d.entityIds.head}|$window", template, docType, None, hash)
    RenderInput(
      EmbeddingCandidate(
        EmbeddingCandidates.SchemaVersion,
        index,
        key,
        docType,
        d.entityIds,
        window,
        d.topicIds,
        d.institutionIds,
        d.communityId,
        text,
        hash,
        S3Layout.semanticText(snapshot, sourceRun, index, key),
        template,
        Selection.PolicyVersion,
        rank,
        Provenance(
          SchemaVersions.Provenance,
          SourceType.DERIVED,
          key,
          snapshot,
          sourceRun,
          Some(template),
          None
        )
      ),
      facts
    )

  /** Every document type: 6 profiles, 4 communities, 3 paths, 4 topics, 5 papers. */
  def corpus: Vector[RenderInput] =
    people.indices.map(i => candidate("researcher-profile", profileFacts(i), i + 1)).toVector ++
      (0 until 4).map(i => candidate("collaboration-community", communityFacts(i), i + 1)) ++
      (0 until 3).map(i => candidate("collaboration-path", pathFacts(i), i + 1)) ++
      (0 until 4).map(i => candidate("topic-summary", topicFacts(i), i + 1)) ++
      (0 until 5).map(i => candidate("paper-summary", paperFacts(i), i + 1))

  def line(in: RenderInput): String =
    val j = ujson.read(write(in.candidate))
    j("facts") = in.facts
    j.render()

  /** Writes candidates as <dir>/<index>/part-00000.jsonl. */
  def writeCandidates(dir: Path, ins: Seq[RenderInput]): Path =
    ins.groupBy(_.candidate.vectorIndex).foreach { (ix, xs) =>
      val f = dir.resolve(ix).resolve("part-00000.jsonl")
      Files.createDirectories(f.getParent)
      Files.writeString(f, xs.map(line).mkString("", "\n", "\n"), UTF_8)
    }
    dir

  def withTemp[A](f: Path => A): A =
    val d = Files.createTempDirectory("rag-test-")
    try f(d)
    finally
      val s = Files.walk(d)
      try s.sorted(Comparator.reverseOrder()).forEach(Files.delete(_))
      finally s.close()
