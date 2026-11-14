// Ruling 15 measurement for code review 1 finding 1, read-only: author positions in the existing
// canonical/authored outputs. Counts rows, null positions, positions < 1, and rows whose mentionId
// is not workId#position. Aggregates only; nothing is collected but counts.
// Run: java -cp <tests Test classpath> MeasureAuthored.java <parquet dir>...
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

public class MeasureAuthored {
  public static void main(String[] args) {
    SparkSession spark = SparkSession.builder().master("local[2]").appName("measure-authored")
        .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "2")
        .getOrCreate();
    try {
      for (String dir : args) {
        Dataset<Row> df = spark.read().parquet(dir);
        System.out.println("DATASET " + dir);
        System.out.println("SCHEMA " + df.schema().simpleString());
        df.createOrReplaceTempView("a");
        String mention = java.util.Arrays.asList(df.columns()).contains("mentionId")
            ? "sum(CASE WHEN mentionId <> concat(workId, '#', CAST(position AS STRING)) THEN 1 ELSE 0 END)"
            : "CAST(NULL AS BIGINT)";
        Row r = spark.sql("SELECT count(*), sum(CASE WHEN position IS NULL THEN 1 ELSE 0 END), "
            + "sum(CASE WHEN position < 1 THEN 1 ELSE 0 END), min(position), max(position), "
            + mention + " FROM a").first();
        System.out.println("rows=" + r.get(0) + "\tposition_null=" + r.get(1) + "\tposition_lt_1="
            + r.get(2) + "\tmin_position=" + r.get(3) + "\tmax_position=" + r.get(4)
            + "\tmentionId_not_workId#position=" + r.get(5));
      }
    } finally {
      spark.stop();
    }
  }
}
