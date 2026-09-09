package dev.munormae.dune.run

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DuneRunConfigurationProvisionerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `discovers build run and test configurations from Dune stanzas`() {
        val root = temporaryFolder.newFolder("camel-project").toPath()
        Files.writeString(root.resolve("dune-project"), "(lang dune 3.17)\n")
        val bin = Files.createDirectories(root.resolve("bin"))
        Files.writeString(
            bin.resolve("dune"),
            """
                (executable
                 (name main)
                 (public_name camel-app))

                (test
                 (name test_main))
            """.trimIndent(),
        )

        val specs = discoverDuneRunConfigurations(root)

        assertEquals(listOf(DuneCommand.BUILD, DuneCommand.EXEC, DuneCommand.TEST), specs.map { it.command })
        val executable = specs.single { it.command == DuneCommand.EXEC }
        assertEquals("./bin/main.exe", executable.target)
        assertEquals("camel-app", executable.legacyTarget)
        assertEquals("Dune Run camel-app", executable.name)
        assertTrue(specs.all { it.workingDirectory == root.toAbsolutePath().normalize().toString() })
    }

    @Test
    fun `uses local executable path when public name is absent`() {
        val root = temporaryFolder.newFolder("local-executable").toPath()
        Files.writeString(root.resolve("dune-project"), "(lang dune 3.17)\n")
        val tools = Files.createDirectories(root.resolve("tools"))
        Files.writeString(tools.resolve("dune"), "(executable (name generator))\n")

        val exec = discoverDuneRunConfigurations(root).single { it.command == DuneCommand.EXEC }

        assertEquals("./tools/generator.exe", exec.target)
    }

    @Test
    fun `discovers executables deeper than the old scan limit`() {
        val root = temporaryFolder.newFolder("deep-monorepo").toPath()
        Files.writeString(root.resolve("dune-project"), "(lang dune 3.17)\n")
        var directory = root
        repeat(12) { index -> directory = Files.createDirectories(directory.resolve("level$index")) }
        Files.writeString(directory.resolve("dune"), "(executable (name deep_tool))\n")

        val executable = discoverDuneRunConfigurations(root).single { it.command == DuneCommand.EXEC }

        assertTrue(executable.target.endsWith("/deep_tool.exe"))
        assertTrue(executable.target.count { it == '/' } > 8)
    }
}
