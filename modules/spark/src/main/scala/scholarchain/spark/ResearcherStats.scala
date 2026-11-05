package scholarchain.spark

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.*

/** Spark job 3: collaboration breadth and team-size statistics per researcher and window.
  *
  * The basis is DBLP authorship, the same basis as the coauthor graph (DBLP pair candidates): an
  * AUTHORED row (researcher, canonical work) counts when one of its mentions is a DBLP mention.
  * team(w) = the number of distinct canonical researchers with a DBLP mention on canonical work w.
  * A work is in a window when its year is (inclusive years; a null year is in no window).
  *
  *   - P_r publicationCount: distinct canonical works of r in the window.
  *   - T_r totalCoauthorOccurrences: sum over those works of (team(w) - 1).
  *   - A_r averageCoauthorsPerPaper: T_r / P_r.
  *   - M_r maximumCoauthorsOnOnePaper: max over those works of (team(w) - 1); maximumTeamWorkIds
  *     lists every work that reaches M_r, sorted.
  *   - C_r distinctCoauthorCount: the number of coauthor edges of r in the window (its degree).
  *   - W_r weightedDegree: the sum of jointPublicationCount over those edges.
  *
  * W_r = T_r whenever the edges and the teams come from the same authorship (each shared work adds
  * one to both). The job checks it and reports every researcher where it fails.
  *
  * Shuffles: the team-size group-by on workId, its join back, the group-by on (window, researcher),
  * the degree group-by over edges, and the final join on (window, researcherId).
  */
object ResearcherStats:

  /** authored (researcherId, workId, year, sourceDatasets); edges (window, researcherA,
    * researcherB, jointPublicationCount); windows (window, windowStartYear, windowEndYear). One row
    * per (window, researcher) with at least one DBLP publication in the window.
    */
  def apply(authored: DataFrame, edges: DataFrame, windows: DataFrame): DataFrame =
    val dblp = authored
      .where(array_contains(col("sourceDatasets"), "DBLP"))
      .select("researcherId", "workId", "year")
    val team = dblp.groupBy("workId").agg(count(lit(1)).as("teamSize"))
    val pubs = dblp
      .join(team, "workId")
      .join(broadcast(windows), col("year").between(col("windowStartYear"), col("windowEndYear")))
      .withColumn("n", col("teamSize") - 1)
      .groupBy("window", "windowStartYear", "windowEndYear", "researcherId")
      .agg(
        count(lit(1)).as("publicationCount"),
        sum("n").as("totalCoauthorOccurrences"),
        max("n").as("maximumCoauthorsOnOnePaper"),
        collect_list(struct(col("n"), col("workId"))).as("nw")
      )
      .withColumn(
        "maximumTeamWorkIds",
        array_sort(
          transform(
            filter(col("nw"), x => x("n") === col("maximumCoauthorsOnOnePaper")),
            x => x("workId")
          )
        )
      )
      .drop("nw")
    pubs
      .join(degrees(edges), Seq("window", "researcherId"), "left")
      .select(
        col("researcherId"),
        col("window"),
        col("windowStartYear"),
        col("windowEndYear"),
        col("publicationCount"),
        coalesce(col("degree"), lit(0L)).as("distinctCoauthorCount"),
        col("totalCoauthorOccurrences"),
        (col("totalCoauthorOccurrences").cast("double") / col("publicationCount"))
          .as("averageCoauthorsPerPaper"),
        col("maximumCoauthorsOnOnePaper"),
        col("maximumTeamWorkIds"),
        coalesce(col("weight"), lit(0L)).cast("double").as("weightedDegree")
      )

  /** (window, researcherId, degree, weight) for every edge endpoint. */
  def degrees(edges: DataFrame): DataFrame =
    edges
      .select(col("window"), col("researcherA").as("researcherId"), col("jointPublicationCount"))
      .union(
        edges.select(
          col("window"),
          col("researcherB").as("researcherId"),
          col("jointPublicationCount")
        )
      )
      .groupBy("window", "researcherId")
      .agg(count(lit(1)).as("degree"), sum("jointPublicationCount").as("weight"))
