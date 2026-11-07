package scholarchain.storage

import scholarchain.core.config.ConfigLoader
import scholarchain.core.{Provenance, SchemaVersions, SourceType, StableIds}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.Comparator

/** Hand-made fixtures shared by the storage specs. */
object Fixtures:
  val config = ConfigLoader.load(Map.empty).fold(e => sys.error(e.mkString), identity)
  val windows = config.graph.windows
  val snapshot = "2026-10-03"
  val runId = StableIds.runId("storage-test", "code", "config-v1", Seq("fixture"), 1)
  val otherRunId = StableIds.runId("storage-test", "code", "config-v1", Seq("fixture"), 2)
  val policy = "graph-v1"

  def researcher(anchor: String): String = StableIds.researcherId(None, "canon-v1.er-v2", anchor)
  val (ra, rb) = {
    val x = researcher("a"); val y = researcher("b")
    if x < y then (x, y) else (y, x)
  }
  val rc = researcher("c")
  val w1 = "dblp:conf/test/W1"
  val w2 = "dblp:conf/test/W2"
  val venue = "dblp-venue:conf/test"
  val community = StableIds.communityId(policy, "all-years", 2000, 2026, ra)

  /** Fresh temporary directory, deleted by `deleteTree` (run-scoped test data only). */
  def tempDir(): Path = Files.createTempDirectory("storage-test-")
  def deleteTree(p: Path): Unit =
    if Files.exists(p) then
      val s = Files.walk(p)
      try s.sorted(Comparator.reverseOrder()).forEach(Files.delete(_))
      finally s.close()

  def withTemp[A](f: Path => A): A =
    val d = tempDir()
    try f(d)
    finally deleteTree(d)

  // ---- Neptune --------------------------------------------------------------------------------

  def prov(spec: FileSpec): Map[String, Prop] =
    Map[String, Prop](
      "runId" -> runId,
      "snapshot" -> snapshot,
      "schemaVersion" -> spec.schemaVersion
    ) ++
      (if spec.kind == ElementKind.Edge then Map[String, Prop]("policyVersion" -> policy)
       else Map.empty)

  def vertex(spec: FileSpec, id: String, props: (String, Prop)*): GraphElement =
    GraphElement(id, spec.label, "", "", prov(spec) ++ props)

  def edge(spec: FileSpec, from: String, to: String, props: (String, Prop)*): GraphElement =
    GraphElement(
      NeptuneContract.edgeId(spec.label, from, to, policy),
      spec.label,
      from,
      to,
      prov(spec) ++ props
    )

  def researcherV(id: String): GraphElement =
    vertex(
      NeptuneContract.Researcher,
      id,
      "displayName" -> s"Name $id",
      "mentionCount" -> 2L,
      "dblpAnchored" -> true,
      "assignedTier" -> "TIER1",
      "identityConfidence" -> 0.97
    )

  def coauthor(a: String, b: String, works: Vector[String]): GraphElement =
    val w = windows.head
    val id = StableIds.coauthorEdgeId(a, b, w.startYear, w.endYear, policy).toOption.get
    GraphElement(
      id,
      "COAUTHORED_WITH",
      a,
      b,
      prov(NeptuneContract.CoauthoredWith) ++ Map[String, Prop](
        "window" -> w.name,
        "windowStartYear" -> w.startYear,
        "windowEndYear" -> w.endYear,
        "jointPublicationCount" -> works.size.toLong,
        "firstCollaborationYear" -> 2005,
        "lastCollaborationYear" -> 2010,
        "supportingWorkIds" -> works.sorted.mkString(" ")
      )
    )

  /** A small valid graph: three researchers, two works, a venue, a community; 7 vertices, 7 edges.
    */
  def graph: Map[FileSpec, Vector[GraphElement]] =
    import NeptuneContract.*
    val w = windows.head
    Map(
      Researcher -> Vector(researcherV(ra), researcherV(rb), researcherV(rc)),
      Work -> Vector(
        vertex(Work, w1, "title" -> "A, \"quoted\" title", "year" -> 2005, "dblpAnchored" -> true),
        vertex(Work, w2, "year" -> 2010, "dblpAnchored" -> true)
      ),
      Venue -> Vector(vertex(Venue, venue, "name" -> "Test Conf")),
      Community -> Vector(
        vertex(
          Community,
          community,
          "window" -> w.name,
          "windowStartYear" -> w.startYear,
          "windowEndYear" -> w.endYear,
          "communitySize" -> 3L,
          "internalEdges" -> 1L,
          "crossCommunityEdges" -> 0L,
          "communityLabel" -> ra
        )
      ),
      // Both coauthors AUTHORED both works the coauthor edge cites (F2 evidence tracing).
      Authored -> Vector(
        edge(Authored, ra, w1, "position" -> 1, "year" -> 2005),
        edge(Authored, ra, w2, "position" -> 1, "year" -> 2010),
        edge(Authored, rb, w1, "position" -> 2, "year" -> 2005),
        edge(Authored, rb, w2, "position" -> 2, "year" -> 2010)
      ),
      PublishedIn -> Vector(edge(PublishedIn, w1, venue)),
      CoauthoredWith -> Vector(coauthor(ra, rb, Vector(w1, w2))),
      MemberOf -> Vector(
        edge(
          MemberOf,
          ra,
          community,
          "window" -> w.name,
          "windowStartYear" -> w.startYear,
          "windowEndYear" -> w.endYear
        )
      )
    )

  def writeGraph(root: Path, g: Map[FileSpec, Vector[GraphElement]]): Unit =
    g.foreach { (spec, elems) =>
      val dir = root.resolve(spec.dir).resolve(spec.name)
      Files.createDirectories(dir)
      val text = (spec.header +: elems.map(NeptuneContract.renderRow(spec, _)))
        .map(Csv.render)
        .mkString("\n") + "\n"
      Files.writeString(dir.resolve("part-00000.csv"), text, UTF_8)
    }

  // ---- Embedding candidates and vectors -------------------------------------------------------

  def candidate(
      index: String,
      docType: String,
      entityIds: Vector[String],
      text: String
  ): EmbeddingCandidate =
    val hash = StableIds.textHash(text)
    val key =
      StableIds.chunkId(s"${entityIds.head}|all-years", "summary-template-v1", docType, None, hash)
    EmbeddingCandidate(
      EmbeddingCandidates.SchemaVersion,
      index,
      key,
      docType,
      entityIds,
      "all-years",
      Vector("openalex:T10002"),
      Vector.empty,
      None,
      text,
      hash,
      S3Layout.semanticText(snapshot, runId, index, key),
      "summary-template-v1",
      "select-v1",
      1,
      Provenance(
        SchemaVersions.Provenance,
        SourceType.DERIVED,
        key,
        snapshot,
        runId,
        Some("summary-template-v1"),
        None
      )
    )

  val model = "amazon.titan-embed-text-v2:0"

  // ---- Rendered documents and DynamoDB rankings -----------------------------------------------

  val templateVersion = "rendering-v1"
  val modelVersion = "titan-v2-1024-norm.rendering-v1"

  def rendered(
      index: String,
      docType: String,
      entityIds: Vector[String],
      text: String,
      modelId: String = model
  ): RenderedDocument =
    val hash = StableIds.textHash(text)
    val key =
      StableIds.chunkId(s"${entityIds.head}|all-years", templateVersion, docType, None, hash)
    val m = ujson.Obj(
      "vectorKey" -> key,
      "entityIds" -> ujson.Arr.from(entityIds.map(ujson.Str(_))),
      "window" -> "all-years",
      "documentType" -> docType,
      "sourceDataset" -> "DERIVED",
      "embeddingModelVersion" -> modelVersion,
      "textHash" -> hash,
      "runId" -> runId,
      "textRef" -> S3Layout.semanticText(snapshot, runId, index, key)
    )
    RenderedDocument(
      RenderedDocuments.SchemaVersion,
      key,
      index,
      docType,
      text,
      hash,
      templateVersion,
      modelId,
      m,
      Provenance(
        SchemaVersions.Provenance,
        SourceType.DERIVED,
        entityIds.head,
        snapshot,
        runId,
        Some(templateVersion),
        None
      )
    )

  def profiles: Vector[RenderedDocument] = Vector(
    rendered("researcher-profile-v1", "researcher-profile", Vector(ra), "Alpha works on graphs."),
    rendered("researcher-profile-v1", "researcher-profile", Vector(rb), "Beta works on proofs."),
    rendered("researcher-profile-v1", "researcher-profile", Vector(rc), "Gamma studies networks.")
  )

  /** Writes documents to dir/<index>/part-00000.jsonl and returns dir. */
  def writeRendered(dir: Path, docs: Seq[RenderedDocument]): Path =
    docs.groupBy(_.index).foreach { (ix, ds) =>
      Files.createDirectories(dir.resolve(ix))
      Files.writeString(
        dir.resolve(s"$ix/part-00000.jsonl"),
        ds.map(upickle.default.write(_)).mkString("", "\n", "\n"),
        UTF_8
      )
    }
    dir

  /** A ResearcherMetricCache top-N item (ruling 21) for publicationCount in all-years. */
  def rankingItem(entries: Seq[(String, Long)]): Item =
    import AttributeValue.*
    Map(
      "researcherId" -> S("RANKING#publicationCount"),
      "metricKey" -> S("TOP#all-years"),
      "metric" -> S("publicationCount"),
      "window" -> S("all-years"),
      "windowStartYear" -> num(2000L),
      "windowEndYear" -> num(2026L),
      "entries" -> L(entries.zipWithIndex.map { case ((id, v), k) =>
        M(Map("rank" -> num((k + 1).toLong), "researcherId" -> S(id), "value" -> num(v)))
      }.toVector),
      "runId" -> S(runId),
      "snapshot" -> S(snapshot),
      "schemaVersion" -> S("researcher-ranking-v1"),
      "policyVersion" -> S(policy)
    )

  /** Unit vector pointing mostly along axis i. */
  def unit(i: Int, tilt: Int = -1): Array[Float] =
    val a = Array.fill(VectorContract.Dimension)(0f)
    a(i) = 1f
    if tilt >= 0 then a(tilt) = 0.5f
    val n = math.sqrt(a.map(x => x.toDouble * x).sum).toFloat
    a.map(_ / n)

  def vectorRecord(
      c: EmbeddingCandidate,
      data: Array[Float],
      modelVersion: String = model
  ): VectorRecord =
    VectorRecord(c.vectorKey, data, EmbeddingCandidates.toMetadata(c, modelVersion))
