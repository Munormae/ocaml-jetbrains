package dev.munormae.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    @Test
    fun `watch termination waits for graceful shutdown before returning`() {
        val process = FakeDuneWatchProcess(waitResults = listOf(true))

        val terminated = terminateDuneWatchProcess(
            process = process,
            gracefulTimeoutMillis = 5,
            forceKillTimeoutMillis = 2,
        )

        assertTrue(terminated)
        assertEquals(1, process.destroyCalls)
        assertEquals(0, process.forceKillCalls)
        assertEquals(listOf(5L), process.waitTimeouts)
    }

    @Test
    fun `watch termination force kills after the graceful timeout`() {
        val process = FakeDuneWatchProcess(waitResults = listOf(false, true))
        var gracefulTimeouts = 0

        val terminated = terminateDuneWatchProcess(
            process = process,
            gracefulTimeoutMillis = 5,
            forceKillTimeoutMillis = 2,
            onGracefulTimeout = { gracefulTimeouts++ },
        )

        assertTrue(terminated)
        assertEquals(1, gracefulTimeouts)
        assertEquals(1, process.destroyCalls)
        assertEquals(1, process.forceKillCalls)
        assertEquals(listOf(5L, 2L), process.waitTimeouts)
    }

    @Test
    fun `watch termination reports a process that survives force kill`() {
        val process = FakeDuneWatchProcess(waitResults = listOf(false, false))

        assertFalse(
            terminateDuneWatchProcess(
                process = process,
                gracefulTimeoutMillis = 5,
                forceKillTimeoutMillis = 2,
            ),
        )
    }

    @Test
    fun `watch coordinator dispatches refresh work asynchronously and serially`() {
        val executor = Executors.newSingleThreadExecutor()
        val coordinator = DuneWatchCoordinator(executor)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = mutableListOf<String>()

        try {
            val first = coordinator.dispatch {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                events += "stop-old"
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse(first.isDone)
            coordinator.dispatch { events += "start-new" }

            release.countDown()
            coordinator.dispatchAndWait {}

            assertEquals(listOf("stop-old", "start-new"), events)
        } finally {
            release.countDown()
            coordinator.close()
        }
    }

    private class FakeDuneWatchProcess(waitResults: List<Boolean>) : DuneWatchProcessControl {
        private val waitResults = waitResults.iterator()

        var destroyCalls = 0
            private set
        var forceKillCalls = 0
            private set
        val waitTimeouts = mutableListOf<Long>()

        override fun isTerminated(): Boolean = false

        override fun isTerminating(): Boolean = false

        override fun requestTermination() {
            destroyCalls++
        }

        override fun waitFor(timeoutInMilliseconds: Long): Boolean {
            waitTimeouts += timeoutInMilliseconds
            return waitResults.next()
        }

        override fun forceKill() {
            forceKillCalls++
        }
    }
}
