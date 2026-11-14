import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// Read-only: opens the existing window-summary store object and the Spark manifest rows it was
// built from with the current WindowSummaries validator, and prints every row's problems.
// Usage: java -cp <storage runtime cp> CheckExisting.java <store-object file> <s3 root> <spark _manifest.json>
public class CheckExisting {
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static void main(String[] a) throws Exception {
    Path object = Paths.get(a[0]);
    Path root = Paths.get(a[1]);
    String key = root.relativize(object).toString();
    byte[] bytes = Files.readAllBytes(object);
    System.out.println("object " + key + " bytes " + bytes.length + " sha256 " + sha(bytes));
    scala.collection.immutable.Map env = scala.collection.immutable.Map$.MODULE$.empty();
    scala.util.Either cfgE = scholarchain.core.config.ConfigLoader.load(env);
    if (cfgE.isLeft()) throw new IllegalStateException("config: " + cfgE);
    scholarchain.core.config.ScholarChainConfig cfg =
        (scholarchain.core.config.ScholarChainConfig) cfgE.toOption().get();
    scala.collection.immutable.Seq windows = (scala.collection.immutable.Seq) cfg.graph().windows();
    System.out.println("windows " + windows);
    scholarchain.storage.ObjectReadPort port = new scholarchain.storage.ObjectReadPort() {
      public scala.Option get(String k) { return k.equals(key) ? scala.Option.apply(bytes) : scala.Option.empty(); }
      public scala.collection.immutable.Vector list(String p) { return scala.package$.MODULE$.Vector().empty(); }
    };
    scala.util.Either opened = scholarchain.storage.WindowSummaries.open(port, key, windows);
    System.out.println("OPEN_STORE_OBJECT " + (opened.isRight() ? "OK" : "REFUSED " + opened.left().get()));
    scala.util.Either parsed = scholarchain.storage.WindowSummaries.parse(key, bytes, windows);
    report("store-object", parsed, windows);

    String m = Files.readString(Paths.get(a[2]));
    ujson.Value mv = ujson.package$.MODULE$.read(ujson.Readable$.MODULE$.fromString(m), false);
    scala.util.Either fromSpark = scholarchain.storage.WindowSummaries.fromSparkManifest(a[2], mv, windows);
    report("spark-manifest", fromSpark, windows);
    if (parsed.isRight() && fromSpark.isRight())
      System.out.println("STORE_ROWS_EQUAL_MANIFEST_ROWS " + sameSet(parsed.toOption().get(), fromSpark.toOption().get()));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  static void report(String name, scala.util.Either e, scala.collection.immutable.Seq windows) {
    if (e.isLeft()) { System.out.println(name + " REFUSED " + e.left().get()); return; }
    scala.collection.immutable.Map byName = scholarchain.storage.ProvenanceRules.windowsByName(windows);
    scala.collection.Iterator it = ((scala.collection.immutable.Vector) e.toOption().get()).iterator();
    int n = 0, bad = 0;
    while (it.hasNext()) {
      scholarchain.storage.WindowSummary s = (scholarchain.storage.WindowSummary) it.next();
      scala.collection.immutable.List p = scholarchain.storage.WindowSummaries.problems(s, byName);
      n++; if (!p.isEmpty()) bad++;
      System.out.println(name + " row " + s.window() + " researchers=" + s.researchers() + " edges=" + s.coauthorEdges()
          + " components=" + s.components() + " largestComponent=" + s.largestComponentSize() + " singletons=" + s.singletonResearchers()
          + " communities=" + s.communities() + " largestCommunity=" + s.largestCommunitySize() + " singletonCommunities=" + s.singletonCommunities()
          + " atLeastMin=" + s.communitiesAtLeastMinSize() + " minSize=" + s.communityMinSize() + " problems=" + p);
    }
    System.out.println(name + " ROWS " + n + " WITH_PROBLEMS " + bad);
  }

  static boolean sameSet(Object x, Object y) {
    return ((scala.collection.immutable.Vector) x).toSet().equals(((scala.collection.immutable.Vector) y).toSet());
  }

  static String sha(byte[] b) throws Exception {
    byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(b);
    StringBuilder sb = new StringBuilder();
    for (byte x : d) sb.append(String.format("%02x", x));
    return sb.toString();
  }
}
