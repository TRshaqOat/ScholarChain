package scholarchain.rag.runtime

import scholarchain.core.config.ScholarChainConfig
import scholarchain.rag.{EmbeddingModels, SemanticContext}
import scholarchain.storage.{
  Embedder,
  ObjectReadPort,
  Throttle,
  VectorContract,
  VectorFilter,
  VectorReadPort
}

import scala.util.control.NonFatal

/** Stage 6 (SEMANTIC_CONTEXT, RUNTIME.md section 9). The question is embedded once and reused for
  * every index chosen by the planner code. Every query filters on the embedding model version (fake
  * vectors carry "fake:<version>") and a window; resolved IDs narrow it further. The hits are
  * descriptive context only: nothing here chooses, confirms, or changes a graph fact.
  */
object SemanticStage:
  final case class Output(
      results: Vector[SemanticResult],
      call: Option[EmbeddingCall],
      missing: Vector[MissingEvidence]
  )

  def run(
      question: String,
      plan: QueryPlan,
      resolved: Vector[Resolved],
      facts: GraphFacts,
      embedder: Embedder,
      throttle: Option[Throttle],
      vectors: VectorReadPort,
      objects: ObjectReadPort,
      modelVersion: String,
      cfg: ScholarChainConfig,
      nowMs: () => Long,
      checkDeadline: String => Unit = _ => ()
  ): Output =
    val ix = cfg.s3Vectors.indexes
    val corpus = Windows.corpusWindowName(cfg)
    val label = if embedder.isFake then EmbeddingModels.fakeLabel(modelVersion) else modelVersion
    def str(xs: Seq[String]) = xs.map(ujson.Str(_))
    val ids = resolved.map(_.researcherId).distinct
    val pairs = plan.operations
      .filter(_.operation == scholarchain.core.QueryOperation.SHORTEST_PATH)
      .flatMap { op =>
        val m = resolved.map(r => r.index -> r.researcherId).toMap
        for x <- m.get(op.researchers(0)); y <- m.get(op.researchers(1)) yield (x, y)
      }
    val communities = facts.metrics.flatMap(_.metrics.communityId).distinct
    val workIds = facts.works.map(_.workId).distinct.take(VectorContract.MaxFilterConstraints - 2)
    val queries: Vector[(String, String, Option[VectorFilter])] = plan.semanticIndexes.flatMap {
      index =>
        if ix.get("researchers").contains(index) then
          Option.when(ids.nonEmpty)((index, corpus, Some(VectorFilter.In("entityIds", str(ids)))))
        else if ix.get("paths").contains(index) then
          Option.when(pairs.nonEmpty)(
            (
              index,
              corpus,
              Some(
                VectorFilter.Or(
                  pairs.map((x, y) =>
                    VectorFilter.And(
                      Seq(
                        VectorFilter.Eq("entityIds", ujson.Str(x)),
                        VectorFilter.Eq("entityIds", ujson.Str(y))
                      )
                    )
                  )
                )
              )
            )
          )
        else if ix.get("communities").contains(index) then
          Option.when(communities.nonEmpty)(
            (index, plan.window.window, Some(VectorFilter.In("communityId", str(communities))))
          )
        else if ix.get("abstracts").contains(index) then
          Option.when(workIds.nonEmpty)(
            (index, corpus, Some(VectorFilter.In("entityIds", str(workIds))))
          )
        else Some((index, plan.window.window, None))
    }
    if queries.isEmpty then Output(Vector.empty, None, Vector.empty)
    else
      checkDeadline("the query embedding")
      val t0 = nowMs()
      if !embedder.isFake then throttle.foreach(_.acquire())
      val t1 = nowMs()
      val embedded =
        try Right(embedder.embed(question))
        catch case NonFatal(e) => Left(e)
      val call = EmbeddingCall(
        queries.map(_._1).mkString(","),
        label,
        1,
        if embedded.isRight then CallOutcome.OK else CallOutcome.FAILED,
        None,
        nowMs() - t1,
        t1 - t0
      )
      embedded match
        case Left(_) =>
          Output(
            Vector.empty,
            Some(call),
            Vector(
              MissingEvidence(
                MissingReason.NO_SEMANTIC_MATCH,
                "embedding",
                "The question could not be embedded; no semantic context."
              )
            )
          )
        case Right(vec) =>
          val failed = Vector.newBuilder[MissingEvidence]
          val results = queries.flatMap { (index, window, extra) =>
            val base = Seq(
              VectorFilter.Eq("embeddingModelVersion", ujson.Str(label)),
              VectorFilter.Eq("window", ujson.Str(window))
            )
            val filter = VectorFilter.And(base ++ extra.toSeq)
            checkDeadline(s"the $index query")
            val q0 = nowMs()
            // Repair-01 (D4): a vector store error (after the storage retry policy) or a refusal
            // degrades this index to a missing-evidence note, as an embedding failure does; the
            // question goes on with its graph evidence.
            val hits =
              try vectors.query(index, vec, plan.limits.topK, Some(filter))
              catch case NonFatal(e) if !e.isInstanceOf[DeadlineExceeded] => Left("error")
            hits match
              case Left(_) =>
                failed += MissingEvidence(
                  MissingReason.NO_SEMANTIC_MATCH,
                  index,
                  s"The $index query failed; no semantic context from it."
                )
                None
              case Right(hs) =>
                val (items, dropped) = SemanticContext.fetch(objects, index, hs)
                Some(
                  SemanticResult(
                    RuntimeSchemas.SemanticResult,
                    index,
                    label,
                    filterJson(filter).render(),
                    plan.limits.topK,
                    items,
                    dropped.size,
                    nowMs() - q0
                  )
                )
          }
          val none = results
            .filter(_.items.isEmpty)
            .map(r =>
              MissingEvidence(
                MissingReason.NO_SEMANTIC_MATCH,
                r.index,
                s"No ${r.index} document matched the filters."
              )
            )
          Output(results, Some(call), failed.result() ++ none)

  /** The filter as S3 Vectors JSON (audit form; holds IDs and enum values only). */
  def filterJson(f: VectorFilter): ujson.Value = f match
    case VectorFilter.Eq(k, v)  => ujson.Obj(k -> ujson.Obj("$eq" -> v))
    case VectorFilter.In(k, vs) => ujson.Obj(k -> ujson.Obj("$in" -> ujson.Arr.from(vs)))
    case VectorFilter.And(fs)   => ujson.Obj("$and" -> ujson.Arr.from(fs.map(filterJson)))
    case VectorFilter.Or(fs)    => ujson.Obj("$or" -> ujson.Arr.from(fs.map(filterJson)))
    case other                  => ujson.Str(other.toString)
