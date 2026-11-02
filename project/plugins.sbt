// sbt plugins load before project/*.scala, so plugin versions are pinned here rather than in
// project/Dependencies.scala. Dates are Maven Central publication dates (D-09: at least 14 days old).
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2") // 2026-07-17
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.5.0") // 2026-08-27
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.13.0") // 2026-10-08
