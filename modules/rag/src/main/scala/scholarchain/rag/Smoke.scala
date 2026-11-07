package scholarchain.rag

import scholarchain.storage.{RenderedDocument, VectorFilter, VectorHit, VectorReadPort}

/** Retrieval smoke tests (RAG.md, "Retrieval smoke tests"). Every expectation comes from the data's
  * own structure: a query built from a document's facts must find that document, and a structural
  * filter must return only matching documents. No benchmark question or label is used.
  */
object Smoke:
  /** check gets the hits and returns None on success or the reason it failed. */
  final case class Case(
      test: String,
      index: String,
      subject: String,
      query: String,
      filter: VectorFilter,
      topK: Int,
      check: Vector[VectorHit] => Option[String]
  )

  final case class Result(
      test: String,
      cases: Int,
      passed: Int,
      threshold: Double,
      failures: Vector[String]
  ):
    def rate: Double = if cases == 0 then 0 else passed.toDouble / cases
    def ok: Boolean = cases > 0 && rate >= threshold

  /** Test name -> (index, document type, required hit rate). */
  val Tests: Vector[(String, String, String, Double)] = Vector(
    ("profile-self@1", "researcher-profile-v1", "researcher-profile", 0.9),
    ("community-self@5", "collaboration-community-v1", "collaboration-community", 0.9),
    ("path-self@1", "collaboration-path-v1", "collaboration-path", 0.9),
    ("topic-self@1", "topic-institution-summary-v1", "topic-summary", 0.9),
    ("paper-self@1", "paper-abstract-v1", "paper-summary", 0.9),
    ("profile-community-filter", "researcher-profile-v1", "researcher-profile", 1.0)
  )
  val MaxPerTest = 200

  private def names(f: Templates.Facts, list: String): Vector[String] =
    f.list(list).toOption.toVector.flatten.flatMap(_.strOpt("name")).map(Templates.clean(_, 200))

  private def labelled(label: String, xs: Vector[String]) =
    Option.when(xs.nonEmpty)(s"$label: ${xs.mkString(", ")}.")

  /** The query text for a document, from its facts only. */
  def query(documentType: String, facts: ujson.Obj): Option[String] =
    val f = Templates.Facts(facts)
    def clean(k: String) = f.strOpt(k).map(Templates.clean(_, Templates.MaxTitleChars))
    val parts: Seq[Option[String]] = documentType match
      case "researcher-profile" =>
        Seq(
          clean("displayName").map(_ + "."),
          labelled("Coauthors", names(f, "coauthors")),
          labelled("Topics", names(f, "topics")),
          labelled("Venues", names(f, "venues"))
        )
      case "collaboration-community" =>
        Seq(
          labelled("Collaboration community members", names(f, "topMembers")),
          labelled("Topics", names(f, "topics")),
          labelled("Venues", names(f, "venues"))
        )
      // Distinctive facts only: wording every document of a type shares would dominate the query.
      case "collaboration-path" =>
        Seq(
          for s <- clean("sourceName"); t <- clean("targetName") yield s"$s and $t.",
          labelled("Via", names(f, "hops").dropRight(1))
        )
      case "topic-summary" =>
        Seq(
          clean("name").map(_ + "."),
          labelled("Researchers", names(f, "topResearchers")),
          labelled("Venues", names(f, "venues"))
        )
      case "paper-summary" => Seq(clean("title"))
      case _               => Seq.empty
    Option(parts.flatten.mkString(" ")).filter(_.nonEmpty)

  private def str(h: VectorHit, k: String) = h.metadata.value.get(k).flatMap(_.strOpt)

  /** Builds the cases: per test, the first perTest documents of its type by vector key. */
  def cases(
      docs: Seq[(RenderedDocument, ujson.Obj)],
      modelLabel: String,
      perTest: Int
  ): Vector[Case] =
    require(perTest >= 1 && perTest <= MaxPerTest, s"perTest must be 1 to $MaxPerTest")
    val sorted = docs.sortBy(_._1.vectorKey)
    def base(d: RenderedDocument, extra: Seq[VectorFilter]) = VectorFilter.And(
      Seq(
        VectorFilter.Eq("embeddingModelVersion", ujson.Str(modelLabel)),
        VectorFilter.Eq("window", d.metadata("window"))
      ) ++ extra
    )
    Tests.flatMap { (test, index, docType, _) =>
      val pool = sorted.filter((d, _) => d.documentType == docType && d.index == index)
      if test == "profile-community-filter" then
        pool
          .flatMap((d, f) =>
            d.metadata.value.get("communityId").flatMap(_.strOpt).map(c => (d, f, c))
          )
          .take(perTest)
          .flatMap { (d, f, c) =>
            query(docType, f).map { q =>
              Case(
                test,
                index,
                d.vectorKey,
                q,
                base(d, Seq(VectorFilter.Eq("communityId", ujson.Str(c)))),
                100,
                hits =>
                  if !hits.exists(_.key == d.vectorKey) then
                    Some("own profile missing under its community filter")
                  else
                    hits
                      .find(h => !str(h, "communityId").contains(c))
                      .map(h => s"${h.key} is outside community $c")
              )
            }
          }
      else
        val k = test.split('@').lift(1).flatMap(_.toIntOption).getOrElse(1)
        pool.take(perTest).flatMap { (d, f) =>
          query(docType, f).map { q =>
            Case(
              test,
              index,
              d.vectorKey,
              q,
              base(d, Nil),
              k,
              hits =>
                Option.unless(hits.take(k).exists(_.key == d.vectorKey))(
                  s"own document not in the top $k (top: ${hits.headOption.fold("none")(_.key)})"
                )
            )
          }
        }
    }

  /** Runs the cases; embed turns a query into a vector of the same model as the stored vectors. */
  def run(cases: Seq[Case], reader: VectorReadPort, embed: String => Array[Float]): Vector[Result] =
    val outcomes = cases.map { c =>
      val failure = reader.query(c.index, embed(c.query), c.topK, Some(c.filter)) match
        case Left(e)     => Some(s"query failed: $e")
        case Right(hits) => c.check(hits)
      c -> failure
    }
    Tests.map { (test, _, _, threshold) =>
      val mine = outcomes.filter(_._1.test == test)
      Result(
        test,
        mine.size,
        mine.count(_._2.isEmpty),
        threshold,
        mine.collect { case (c, Some(f)) => s"${c.subject}: $f" }.toVector
      )
    }
