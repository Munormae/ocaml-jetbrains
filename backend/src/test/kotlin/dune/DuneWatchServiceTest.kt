package dev.munormae.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        val nestedDirectory = Files.createDirectories(root.resolve("src/nested"))

        assertEquals(root.toAbsolutePath().normalize(), findDuneRoot(nestedDirectory.toString()))
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

    @Test
    fun `watch resumes only after the last concurrent pause lease closes`() {
        var pauseCalls = 0
        var resumeCalls = 0
        val controller = ReferenceCountedPauseController(
            onFirstAcquire = { pauseCalls++ },
            onLastRelease = { resumeCalls++ },
        )

        val first = controller.acquire()
        val second = controller.acquire()

        assertTrue(controller.isPaused)
        assertEquals(1, pauseCalls)
        first.close()
        assertTrue(controller.isPaused)
        assertEquals(0, resumeCalls)
        second.close()
        assertFalse(controller.isPaused)
        assertEquals(1, resumeCalls)

        second.close()
        assertEquals(1, resumeCalls)
    }
}
