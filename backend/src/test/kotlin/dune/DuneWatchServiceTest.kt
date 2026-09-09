package dev.munormae.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class DuneWatchServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `finds a Dune project root`() {
        val root = temporaryFolder.newFolder().toPath()

        assertNull(findDuneRoot(root.toString()))

        Files.writeString(root.resolve("dune-project"), "(lang dune 3.17)")

        assertEquals(root.toAbsolutePath().normalize(), findDuneRoot(root.toString()))
    }

    @Test
    fun `builds an opam watch command`() {
        val root = temporaryFolder.newFolder().toPath()

        val commandLine = createDuneWatchCommandLine(
            root = root,
            useOpam = true,
            opamExecutable = "custom-opam",
            opamSwitch = " 5.3.0 ",
            duneExecutable = "custom-dune",
        )

        assertEquals("custom-opam", commandLine.exePath)
        assertEquals(
            listOf("exec", "--switch", "5.3.0", "--", "custom-dune", "build", "--watch"),
            commandLine.parametersList.list,
        )
        assertEquals(root.toFile(), commandLine.workDirectory)
    }

    @Test
    fun `builds a direct watch command with defaults`() {
        val root = temporaryFolder.newFolder().toPath()

        val commandLine = createDuneWatchCommandLine(
            root = root,
            useOpam = false,
            opamExecutable = null,
            opamSwitch = null,
            duneExecutable = null,
        )

        assertEquals("dune", commandLine.exePath)
        assertEquals(listOf("build", "--watch"), commandLine.parametersList.list)
        assertEquals(root.toFile(), commandLine.workDirectory)
    }
}
