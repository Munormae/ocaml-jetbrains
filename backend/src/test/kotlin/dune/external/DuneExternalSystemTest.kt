package dev.munormae.dune.external

import dev.munormae.toolchain.OCamlEnvironmentKind
import java.io.File
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuneExternalSystemTest {
    @Test
    fun `PATH tasks execute Dune directly`() {
        val command = createDuneExternalTaskCommandLine(
            projectPath = ".",
            taskName = "exec ./bin/main.exe",
            settings = DuneExternalExecutionSettings().apply {
                environmentKind = OCamlEnvironmentKind.PATH.name
                duneExecutable = "custom-dune"
            },
        )

        assertEquals("custom-dune", command.exePath)
        assertEquals(listOf("exec", "./bin/main.exe"), command.parametersList.list)
    }

    @Test
    fun `executable task keeps a model target containing spaces as one argument`() {
        val command = createDuneExternalTaskCommandLine(
            projectPath = ".",
            taskName = "exec ./example app/main.exe",
            settings = DuneExternalExecutionSettings().apply {
                environmentKind = OCamlEnvironmentKind.PATH.name
                duneExecutable = "dune"
            },
        )

        assertEquals(listOf("exec", "./example app/main.exe"), command.parametersList.list)
    }

    @Test
    fun `OPAM tasks preserve selected switch`() {
        val command = createDuneExternalTaskCommandLine(
            projectPath = ".",
            taskName = "build",
            settings = DuneExternalExecutionSettings().apply {
                environmentKind = OCamlEnvironmentKind.OPAM_SWITCH.name
                switchName = "5.3.0"
            },
        )

        assertTrue(command.parametersList.list.containsAll(listOf("exec", "--switch", "5.3.0", "--", "dune", "build")))
    }

    @Test
    fun `selected directory tasks prepend the environment bin directory to PATH`() {
        val command = createDuneExternalTaskCommandLine(
            projectPath = ".",
            taskName = "build",
            settings = DuneExternalExecutionSettings().apply {
                environmentKind = OCamlEnvironmentKind.CUSTOM.name
                environmentPrefix = "C:/ocaml"
                duneExecutable = "C:/ocaml/bin/dune.exe"
            },
        )

        val expectedBin = Path.of("C:/ocaml", "bin").toString()
        assertEquals(expectedBin, command.environment.getValue("PATH").split(File.pathSeparator).first())
    }
}
