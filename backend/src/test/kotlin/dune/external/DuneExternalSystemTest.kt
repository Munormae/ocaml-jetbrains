package dev.munormae.dune.external

import dev.munormae.toolchain.OCamlEnvironmentKind
import dev.munormae.dune.model.DuneProjectModel
import dev.munormae.dune.model.DuneTarget
import dev.munormae.dune.model.DuneTargetKind
import dev.munormae.dune.model.DuneWorkspaceModel
import java.io.File
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.intellij.execution.process.ProcessOutputType

class DuneExternalSystemTest {
    @Test
    fun `task output is forwarded before the process finishes`() {
        val delivered = mutableListOf<Pair<String, Boolean>>()
        val process = object : DuneExternalTaskProcess {
            var completed = false

            override fun start(output: (String, ProcessOutputType) -> Unit) {
                output("compiling main.ml\n", ProcessOutputType.STDOUT)
                delivered += "observed" to completed
            }

            override fun waitFor(): Int {
                completed = true
                return 0
            }

            override fun terminateTree() = Unit
        }

        val output = mutableListOf<String>()
        val exitCode = executeDuneExternalTaskProcess(process, output = { text, _ -> output += text })

        assertEquals(0, exitCode)
        assertEquals(listOf("compiling main.ml\n"), output)
        assertEquals(listOf("observed" to false), delivered)
    }

    @Test
    fun `task cancelled while starting terminates before waiting`() {
        var cancelled = false
        val events = mutableListOf<String>()
        val process = object : DuneExternalTaskProcess {
            override fun start(output: (String, ProcessOutputType) -> Unit) {
                events += "start"
                cancelled = true
            }

            override fun terminateTree() {
                events += "terminate"
            }

            override fun waitFor(): Int {
                events += "wait"
                return -1
            }
        }

        executeDuneExternalTaskProcess(process, output = { _, _ -> }, cancelled = { cancelled })

        assertEquals(listOf("start", "terminate", "wait"), events)
    }

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
    fun `Dune package management tasks execute the selected Dune directly`() {
        val command = createDuneExternalTaskCommandLine(
            projectPath = ".",
            taskName = "build",
            settings = DuneExternalExecutionSettings().apply {
                environmentKind = OCamlEnvironmentKind.DUNE_PACKAGE_MANAGEMENT.name
                duneExecutable = "custom-dune"
            },
        )

        assertEquals("custom-dune", command.exePath)
        assertEquals(listOf("build"), command.parametersList.list)
    }

    @Test
    fun `managed watch builds use Dune RPC instead of a second build process`() {
        val command = createDuneExternalTaskCommandLine(
            projectPath = ".",
            taskName = "build",
            settings = DuneExternalExecutionSettings().apply {
                environmentKind = OCamlEnvironmentKind.PATH.name
                duneExecutable = "dune"
                useRpc = true
            },
        )

        assertEquals(listOf("rpc", "build", "."), command.parametersList.list)
    }

    @Test
    fun `mixed build and test tasks do not use RPC after pausing watch`() {
        assertEquals(false, useDuneRpcForExternalTasks(true, listOf("build", "test")))
        assertEquals(true, useDuneRpcForExternalTasks(true, listOf("build")))
        assertEquals(false, useDuneRpcForExternalTasks(false, listOf("build")))
    }

    @Test
    fun `selected directory tasks prepend the environment bin directory to PATH`() {
        val prefix = Path.of("ocaml-environment").toAbsolutePath().normalize()
        val command = createDuneExternalTaskCommandLine(
            projectPath = ".",
            taskName = "build",
            settings = DuneExternalExecutionSettings().apply {
                environmentKind = OCamlEnvironmentKind.CUSTOM.name
                environmentPrefix = prefix.toString()
                duneExecutable = prefix.resolve("bin/dune").toString()
            },
        )

        val expectedBin = prefix.resolve("bin").toString()
        assertEquals(expectedBin, command.environment.getValue("PATH").split(File.pathSeparator).first())
    }

    @Test
    fun `external executable tasks use paths relative to the Dune workspace`() {
        val root = Path.of("dune-workspace").toAbsolutePath().normalize()
        val nestedProject = root.resolve("vendor/child")
        val workspace = DuneWorkspaceModel(
            root,
            linkedMapOf(
                root to DuneProjectModel(root),
                nestedProject to DuneProjectModel(
                    nestedProject,
                    executables = listOf(
                        DuneTarget(
                            kind = DuneTargetKind.EXECUTABLE,
                            name = "main",
                            target = "./bin/main.exe",
                            directory = nestedProject.resolve("bin"),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf("./vendor/child/bin/main.exe"), duneWorkspaceExecutableTargets(workspace))
    }

    @Test
    fun `changed Dune file chooses the deepest linked external root`() {
        val root = Path.of("dune-workspace").toAbsolutePath().normalize()
        val child = root.resolve("vendor")
        val grandchild = child.resolve("project")

        assertEquals(
            grandchild,
            mostSpecificDuneRoot(grandchild.resolve("dune"), listOf(root, child, grandchild)),
        )
    }
}
