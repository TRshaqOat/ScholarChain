package scholarchain.rag.cli

import scholarchain.core.StableIds
import scholarchain.rag.runtime.{Audit, RetrievalMode}
import scholarchain.storage.cli.Cli
import scholarchain.storage.{FileObjectStore, Hashing, ManifestEntry, RunManifest, Throttle}
import upickle.default.{read, write}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}

/** Runs the benchmark questions through the runtime (RUNTIME.md section 22; ruling 12): one store
  * load, then each question in file order. Per-question results are write-once objects under
  * `reports/rag-benchmark/snapshot=<d>/run=<benchmark run>/questions/<id>.json`, so a rerun with
  * the same inputs resumes by question ID. `--max n` answers at most n pending questions. After
  * every invocation the summary of all results present and a run manifest are written (keyed by the
  * number of results, write-once). A question whose runtime fails, or whose model or embedding call
  * never succeeded because of an error (`Benchmark.callFailure`), gets no result; the run stops
  * there and the next run retries it. `--retrieval-mode fused|graph-only|vector-only` (default
  * fused; RUNTIME.md section 24) picks the retrievers; each result's ranked retrieved list is also
  * written as `.../retrieval/<id>-<mode>.csv`. Exit 0 when no question failed in this invocation, 1
  * otherwise, 2 usage, 3 disk.
  */
object BenchmarkCli:
  val UsageText =
    "rag/runMain scholarchain.rag.cli.BenchmarkCli --questions <benchmark CSV> " +
      s"--path-evidence <stored path hops CSV> [--max <n>] " +
      s"[--retrieval-mode fused|graph-only|vector-only] ${AskCli.StoreUsage}"

  def main(args: Array[String]): Unit = Cli.main("BenchmarkCli", args)(run(_, _))

  def run(
      args: Seq[String],
      out: String => Unit,
      sleep: Long => Unit = Thread.sleep,
      nowMs: () => Long = () => Throttle.monotonicMs(),
      env: Map[String, String] = sys.env
  ): Int =
    RagCli.withArgs(
      args,
      Cli.LiveFlags,
      AskCli.StoreOpts ++ Set("questions", "path-evidence", "max", "retrieval-mode"),
      UsageText,
      out,
      env
    ) { (a, cfg) =>
      for
        qPath <- a.req("questions").map(Paths.get(_))
        pPath <- a.req("path-evidence").map(Paths.get(_))
        qBytes <- readFile(qPath)
        pBytes <- readFile(pPath)
        qs <- Benchmark.questions(new String(qBytes, UTF_8)).left.map(e => s"$qPath: $e")
        ref <- Benchmark.pathReference(new String(pBytes, UTF_8)).left.map(e => s"$pPath: $e")
        max <- a.int("max", Some(qs.size)).filterOrElse(_ >= 1, "--max must be at least 1")
        retrieval <- a
          .get("retrieval-mode")
          .fold(Some(RetrievalMode.FUSED))(RetrievalMode.fromFlag)
          .toRight("--retrieval-mode must be fused, graph-only, or vector-only")
        loaded <- AskCli.open(a, cfg, out, sleep, nowMs, retrieval)
      yield loaded.fold(1) { rt =>
        val s = rt.setup
        val benchRun = StableIds.runId(
          "rag-benchmark",
          Benchmark.CodeVersion,
          cfg.configVersion,
          Seq(
            s"runtime=${s.runId}",
            s"questions=${Hashing.sha256(qBytes)}",
            s"path-evidence=${Hashing.sha256(pBytes)}"
          ),
          1
        )
        val store = FileObjectStore(rt.outRoot.resolve("s3"))
        val base = s"reports/rag-benchmark/snapshot=${s.snapshot}/run=$benchRun"
        def resultKey(id: String) = s"$base/questions/$id.json"
        def stored(id: String) =
          store.reader.get(resultKey(id)).map(b => read[Benchmark.Result](new String(b, UTF_8)))
        val pending = qs.filter(q => stored(q.id).isEmpty)
        out(
          s"benchmark run $benchRun: ${qs.size} questions, ${qs.size - pending.size} already done, " +
            s"answering ${math.min(max, pending.size)} now"
        )
        def one(q: Benchmark.Question): Either[String, Benchmark.Result] =
          Cli.freeDiskProblem(rt.outRoot).toLeft(()).flatMap { _ =>
            val t0 = nowMs()
            rt.ask(q.text, None) match
              case Left(f)  => Left(s"runtime failed at ${f.stage}: ${f.message}")
              case Right(r) =>
                val latency = nowMs() - t0
                val res = Benchmark.result(q, r, ref, cfg, latency)
                for
                  _ <- rt.writeManifest(r)
                  _ <- Benchmark.callFailure(r.audit).map(e => s"$e; no result recorded").toLeft(())
                  _ <- Audit.putOnce(
                    store.loader,
                    resultKey(q.id),
                    write(res, indent = 2).getBytes(UTF_8),
                    s"question ${q.id}"
                  )
                yield res
          }
        // Stop at the first failure (live: an unreachable model would only burn the quota); a
        // rerun of the same command resumes at the failed question.
        val failure = pending
          .take(max)
          .iterator
          .map { q =>
            val r = one(q)
            r.foreach(res => out(line(res)))
            r.left.map(q.id -> _)
          }
          .collectFirst { case Left((id, e)) =>
            out(s"$id FAILED: $e; stopping, rerun the same command to resume")
          }
        val failed = if failure.isDefined then 1 else 0
        val results = qs.flatMap(q => stored(q.id))
        val sum = Benchmark.summary(
          benchRun,
          s.runId,
          s.mode.toString,
          s.retrievalMode.toString,
          qs,
          results
        )
        val summaryBytes = write(sum, indent = 2).getBytes(UTF_8)
        val n = f"${results.size}%03d"
        // Rebuilt from every stored result (resume-safe: same result, same bytes, Unchanged). Not in
        // the manifest: each list is a copy of the `retrieved` field of a result that is.
        val lists = results.map(r =>
          Audit.putOnce(
            store.loader,
            s"$base/retrieval/${r.questionId}-${s.retrievalMode.flag}.csv",
            Benchmark.retrievedCsv(r.retrieved).getBytes(UTF_8),
            r.queryId
          )
        )
        val written = for
          _ <- lists.collectFirst { case Left(e) => e }.toLeft(())
          se <- Audit.putOnce(store.loader, s"$base/summary-$n.json", summaryBytes, "summary")
          entries = results.map(r =>
            val b = store.reader.get(resultKey(r.questionId)).get
            ManifestEntry(resultKey(r.questionId), Hashing.sha256(b), b.length.toLong, r.queryId)
          ) :+ se
          mk = s"runs/rag-benchmark/snapshot=${s.snapshot}/run=$benchRun/manifest-$n.json"
          _ <- Audit.putOnce(
            store.loader,
            mk,
            RunManifest.toJson(
              RunManifest(RunManifest.SchemaVersion, "rag-benchmark", benchRun, s.snapshot, entries)
            ),
            "manifest"
          )
        yield s"$base/summary-$n.json"
        written match
          case Left(e) =>
            out(s"summary not written: $e")
            1
          case Right(k) =>
            printSummary(sum, out)
            out(s"summary: ${rt.outRoot.resolve("s3").resolve(k)}")
            if failed == 0 then 0 else 1
      }
    }

  private def line(res: Benchmark.Result): String =
    val fails = res.checks.filter(_.outcome == Benchmark.Outcome.FAIL).map(_.name)
    s"${res.questionId} ${res.abstentionReason.fold("answered")(r => s"abstained $r")}; " +
      s"${res.verification.verified} verified; graph ${res.graph.reads} reads, " +
      s"${res.graph.evidenceItems} items; semantic ${res.semantic.hits} hits; " +
      s"llm ${res.llm.attempts} attempts ${res.llm.requests} requests; ${res.latencyMs} ms; " +
      (if fails.isEmpty then "checks pass" else s"FAIL ${fails.mkString(",")}")

  private def readFile(p: java.nio.file.Path): Either[String, Array[Byte]] =
    Either.cond(Files.isRegularFile(p), Files.readAllBytes(p), s"$p is not a file")

  def printSummary(s: Benchmark.Summary, out: String => Unit): Unit =
    def m(x: Map[String, Int]) = x.toVector.sorted.map((k, v) => s"$k $v").mkString(", ")
    def l(x: Benchmark.Latency) = s"p50 ${x.p50Ms} ms, p95 ${x.p95Ms} ms, max ${x.maxMs} ms"
    out(
      s"done ${s.questionsDone} of ${s.questionsTotal} (${s.mode.toLowerCase} mode, " +
        s"${s.retrievalMode.toLowerCase.replace('_', '-')} retrieval)"
    )
    if s.notDone.nonEmpty then out(s"not done: ${s.notDone.mkString(" ")}")
    s.checks.toVector.sortBy(_._1).foreach { (k, t) =>
      out(s"  check $k: ${t.pass} pass, ${t.fail} fail, ${t.notApplicable} n/a")
    }
    s.failures.foreach(f => out(s"  FAIL $f"))
    out(s"plan sources: ${m(s.planSources)}; outcomes: ${m(s.abstentions)}")
    out(s"resolutions: ${m(s.resolutions)}")
    val v = s.verification
    out(
      s"verification: ${v.verified} verified, ${v.partiallySupported} partial, ${v.unsupported} unsupported, ${v.conflicting} conflicting; ${s.claims} final claims"
    )
    val g = s.graph
    out(
      s"graph retrieval: ${g.reads} reads in ${g.questionsWithReads} questions, ${g.rows} rows, ${g.truncatedReads} truncated, ${g.evidenceItems} evidence items, ${g.tokens} tokens; paths ${m(g.pathStatuses)}; ${l(g.latency)}"
    )
    val se = s.semantic
    out(
      s"semantic retrieval: ${se.embeddingCalls} embedding calls in ${se.questionsWithEmbedding} questions, ${se.hits} hits in ${se.questionsWithHits} questions, ${se.tokens} tokens; indexes ${m(se.indexes)}; ${l(se.latency)}"
    )
    out(
      s"llm: ${s.llm.attempts} attempts, ${s.llm.requests} requests (${m(s.llm.attemptsByStage)}), outcomes ${m(s.llm.outcomes)}, tokens in ${s.llm.inputTokens} out ${s.llm.outputTokens}, throttle wait ${s.llm.throttleWaitMs} ms"
    )
    out(s"question latency: ${l(s.latency)}, total ${s.latency.totalMs} ms")
