package scholarchain.rag

import scholarchain.core.StableIds

/** One embedding model the runtime knows: its Bedrock model ID, output dimension, and whether its
  * vectors are unit length.
  */
final case class EmbeddingModelSpec(modelId: String, dimension: Int, normalized: Boolean)

/** Embedding model versioning (RAG.md, "Embedding model versioning"). The version string names
  * everything that makes two vectors comparable, plus the rendering template of the text:
  * `<modelId>|<dimension>|normalized|<templateVersion>`. It is the `embeddingModelVersion` of every
  * vector, so a retrieval filter on it never mixes models, sizes, or templates.
  */
object EmbeddingModels:
  /** Ruling 9: Titan Text Embeddings V2, 1024 dimensions, normalized. */
  val TitanTextV2 = EmbeddingModelSpec("amazon.titan-embed-text-v2:0", 1024, normalized = true)
  val Known: Vector[EmbeddingModelSpec] = Vector(TitanTextV2)

  def forId(modelId: String): Either[String, EmbeddingModelSpec] =
    Known
      .find(_.modelId == modelId)
      .toRight(
        s"embedding model '$modelId' is not one of ${Known.map(_.modelId).mkString(", ")}; " +
          s"set SCHOLARCHAIN_EMBEDDING_MODEL_ID"
      )

  def version(spec: EmbeddingModelSpec, templateVersion: String): String =
    val norm = if spec.normalized then "normalized" else "raw"
    s"${spec.modelId}|${spec.dimension}|$norm|$templateVersion"

  /** The model and template of a version string; None for an unknown model or a malformed string.
    */
  def parse(version: String): Option[(EmbeddingModelSpec, String)] =
    version.split('|') match
      case Array(id, dim, norm, template) =>
        Known
          .find(s =>
            s.modelId == id && s.dimension.toString == dim &&
              norm == (if s.normalized then "normalized" else "raw")
          )
          .map(_ -> template)
      case _ => None

  /** The label the fake embedder gives its vectors (storage HashingEmbedder: "fake:" + version). */
  def fakeLabel(version: String): String = s"fake:$version"

/** Selection policy `selection-v1` (modules/rag/SELECTION.md). Spark applies it; these are the
  * constants it names and the rank hash, so tests can freeze them.
  */
object Selection:
  val PolicyVersion = "selection-v1"
  val Seed = "20261003"
  val ProfileQuota = 5000
  val CommunityQuotas: Map[String, Int] =
    Map("all-years" -> 2000, "2000-2014" -> 1000, "2015-2026" -> 1000)
  val PaperQuota = 1000

  def rankKey(index: String, subjectId: String, window: String): String =
    StableIds.hash("embed-select", PolicyVersion, Seed, index, subjectId, window)
