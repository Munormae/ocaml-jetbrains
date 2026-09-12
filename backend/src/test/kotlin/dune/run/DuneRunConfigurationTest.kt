package dev.munormae.dune.run

import com.intellij.execution.configurations.RuntimeConfigurationError
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DuneRunConfigurationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `executable target presentation includes model name and local target`() {
        assertEquals(
            "main — ./bin/main.exe",
            DuneTargetChoice("main", "./bin/main.exe").toString(),
        )
    }

    @Test
    fun `custom Dune executable can satisfy run configuration validation`() {
        assertEquals(true, isDuneExecutableAvailable(detected = false, customExecutable = "custom-dune"))
        assertEquals(false, isDuneExecutableAvailable(detected = false, customExecutable = ""))
    }

    @Test
    fun `build command supports quoted targets and Dune arguments`() {
        assertEquals(
            listOf("build", "--profile", "release", "@check", "target with spaces"),
            buildArguments(
                command = DuneCommand.BUILD,
                target = "@check \"target with spaces\"",
                duneArguments = "--profile release",
                programArguments = "ignored",
            ),
        )
    }

    @Test
    fun `exec command keeps executable separate from program arguments`() {
        assertEquals(
            listOf("exec", "--display", "quiet", "./bin/main.exe", "--", "--verbose", "two words"),
            buildArguments(
                command = DuneCommand.EXEC,
                target = " ./bin/main.exe ",
                duneArguments = "--display quiet",
                programArguments = "--verbose \"two words\"",
            ),
        )
    }

    @Test
    fun `exec command omits separator when there are no program arguments`() {
        assertEquals(
            listOf("exec", "./bin/main.exe"),
            buildArguments(
                command = DuneCommand.EXEC,
                target = "./bin/main.exe",
                duneArguments = "",
                programArguments = "",
            ),
        )
    }

    @Test
    fun `relative working directory is resolved from project`() {
        val projectDirectory = temporaryFolder.newFolder().toPath().toAbsolutePath().normalize()

        assertEquals(
            projectDirectory.resolve("test/integration").normalize(),
            resolveWorkingDirectory(projectDirectory.toString(), "test/integration"),
        )
        assertEquals(projectDirectory, resolveWorkingDirectory(projectDirectory.toString(), ""))
    }

    @Test(expected = RuntimeConfigurationError::class)
    fun `working directory requires a project base path`() {
        resolveWorkingDirectory(null, "")
    }
}
