package scholarchain.entityresolution

import java.nio.file.{Files, Path, Paths}

/** The module's `src/main` directory, whether tests run from the repository root or the module. */
object ModuleFiles:
  val main: Path =
    Seq(Paths.get("modules/entity-resolution/src/main"), Paths.get("src/main"))
      .find(Files.isDirectory(_))
      .getOrElse(sys.error("cannot locate modules/entity-resolution/src/main"))
