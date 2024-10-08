package protocbridge.frontend

import java.nio.file.{Files, Path}

import protocbridge.ProtocCodeGenerator
import protocbridge.ExtraEnv
import java.nio.file.attribute.PosixFilePermission

import scala.concurrent.blocking
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.sys.process._
import java.{util => ju}

/** PluginFrontend for Unix-like systems <b>except macOS</b> (Linux, FreeBSD,
  * etc)
  *
  * Creates a pair of named pipes for input/output and a shell script that
  * communicates with them. Compared with `SocketBasedPluginFrontend`, this
  * frontend doesn't rely on `nc` that might not be available in some
  * distributions.
  */
object PosixPluginFrontend extends PluginFrontend {
  case class InternalState(
      inputPipe: Path,
      outputPipe: Path,
      tempDir: Path,
      shellScript: Path
  )

  override def prepare(
      plugin: ProtocCodeGenerator,
      env: ExtraEnv
  ): (Path, InternalState) = {
    val tempDirPath = Files.createTempDirectory("protopipe-")
    val inputPipe = createPipe(tempDirPath, "input")
    val outputPipe = createPipe(tempDirPath, "output")
    val sh = createShellScript(inputPipe, outputPipe)

    Future {
      blocking {
        // Each of the following two calls below block until the shell script opens the
        // corresponding pipe. This ensures that we are not writing to any of the pipes
        val fsin = Files.newInputStream(inputPipe)
        val fsout = Files.newOutputStream(outputPipe)

        val response = PluginFrontend.runWithInputStream(plugin, fsin, env)
        fsin.close()
        fsout.write(response)
        fsout.close()
      }
    }
    (sh, InternalState(inputPipe, outputPipe, tempDirPath, sh))
  }

  override def cleanup(state: InternalState): Unit = {
    if (sys.props.get("protocbridge.debug") != Some("1")) {
      Files.delete(state.inputPipe)
      Files.delete(state.outputPipe)
      Files.delete(state.tempDir)
      Files.delete(state.shellScript)
    }
  }

  private def createPipe(tempDirPath: Path, name: String): Path = {
    val pipeName = tempDirPath.resolve(name)
    Seq("mkfifo", "-m", "600", pipeName.toAbsolutePath.toString).!!
    pipeName
  }

  private def createShellScript(inputPipe: Path, outputPipe: Path): Path = {
    val shell = sys.env.getOrElse("PROTOCBRIDGE_SHELL", "/bin/sh")
    val scriptName = PluginFrontend.createTempFile(
      "",
      s"""|#!$shell
          |set -e
          |exec 4> "$inputPipe"
          |exec 5< "$outputPipe"
          |cat /dev/stdin >&4
          |exec 4>&-
          |cat <&5
          |exec 5<&-
      """.stripMargin
    )
    val perms = new ju.HashSet[PosixFilePermission]
    perms.add(PosixFilePermission.OWNER_EXECUTE)
    perms.add(PosixFilePermission.OWNER_READ)
    Files.setPosixFilePermissions(
      scriptName,
      perms
    )
    scriptName
  }
}
