package scholarchain.rag.runtime

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.rag.cli.AskCli
import scholarchain.storage.{FileObjectStore, RunManifest}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** The runtime CLI in fake mode on the fixture files (key-value reads through the storage
  * ItemFileReader, whose equality with the in-memory fake DynamoSpec tests).
  */
class AskCliSpec extends AnyFunSuite:
  import RuntimeFixtures.*

  private def withFiles[A](body: (Path, Vector[String]) => A): A =
    val root = tempDir()
    try
      val seg = s"snapshot=$snapshot/run=$graphRun"
      writeGraph(root.resolve(s"graph/$seg"))
      writeItems(root.resolve(s"items/$seg"))
      writeVectors(root.resolve("vectors"))
      FileObjectStore(root.resolve("s3")).loader.putNew(profileRef, profileText.getBytes(UTF_8))
      body(
        root,
        Vector(
          "--graph",
          root.resolve(s"graph/$seg").toString,
          "--items",
          root.resolve(s"items/$seg").toString,
          "--objects",
          root.resolve("s3").toString,
          "--vectors",
          root.resolve("vectors").toString,
          "--out",
          root.resolve("out").toString
        )
      )
    finally deleteTree(root)

  test(
    "fake mode answers one question, writes the audit, answer, records, and a manifest under --out"
  ) {
    withFiles { (root, paths) =>
      val lines = Vector.newBuilder[String]
      val code = AskCli.run(
        Vector("--question", "Have Ada Lovelace and Grace Hopper coauthored a paper?") ++ paths,
        lines += _,
        _ => (),
        () => 0L,
        env
      )
      val out = lines.result()
      assert(code == 0, out.mkString("\n"))
      assert(
        out.exists(_.startsWith("fake mode")) && out.exists(l =>
          l.contains("[VERIFIED]") && l.contains("have 2 joint publications in all-years")
        ),
        out.mkString("\n")
      )
      val store = FileObjectStore(root.resolve("out/s3")).reader
      val manifests = store.list("runs/rag-runtime/")
      assert(manifests.size == 1)
      val m = RunManifest.fromJson(store.get(manifests.head).get)
      assert(
        m.entries.map(_.key).exists(_.startsWith("logs/rag-runtime/")) && RunManifest
          .verify(m, store)
          .isEmpty
      )
      assert(Files.list(root.resolve("out/dynamodb")).count() == 1)
    }
  }

  test("usage errors: live needs a profile, and the Haiku rate is at most 8 per minute") {
    withFiles { (_, paths) =>
      def code(extra: String*) =
        AskCli.run(Vector("--question", "q?") ++ paths ++ extra, _ => (), _ => (), () => 0L, env)
      assert(code("--live") == 2)
      assert(code("--per-minute", "9") == 2)
      assert(AskCli.run(Vector("--question", "q?"), _ => (), _ => (), () => 0L, env) == 2)
    }
  }
