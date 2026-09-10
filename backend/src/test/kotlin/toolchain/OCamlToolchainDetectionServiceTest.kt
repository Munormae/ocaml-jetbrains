package dev.munormae.toolchain

import java.util.Collections
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OCamlToolchainDetectionServiceTest {
    @Test
    fun `derives Dune language version from installed version output`() {
        assertEquals("3.24", parseDuneLanguageVersion("3.24.2"))
        assertEquals("3.17", parseDuneLanguageVersion("dune 3.17+dev"))
        assertNull(parseDuneLanguageVersion("version unavailable"))
    }

    @Test
    fun `untrusted status explains why tools are not probed`() {
        val snapshot = ToolchainDetectionSnapshot.blocked()

        assertEquals("Blocked until the project is trusted", snapshot.opam.status)
        assertEquals(snapshot.opam, snapshot.ocamllsp)
        assertEquals(snapshot.opam, snapshot.dune)
        assertEquals(snapshot.opam, snapshot.ocamlformat)
    }

    @Test
    fun `failed opam probe short circuits all opam-based tool probes`() {
        val commands = mutableListOf<String>()
        val snapshot = detectToolchain(
            ToolchainSettingsSnapshot(true, "opam", "5.3.0", "", "", ""),
            projectBasePath = null,
        ) { commandLine, _, _ ->
            commands += commandLine.commandLineString
            ToolProbeResult("Unavailable")
        }

        assertEquals(1, commands.size)
        assertTrue(commands.single().contains("opam --version"))
        assertEquals("Unavailable because opam could not be started", snapshot.dune.status)
    }

    @Test
    fun `wizard Dune probe uses the selected opam switch`() {
        val command = createDuneVersionProbeCommand(useOpam = true, opamSwitch = " 5.1.1 ")

        assertEquals("opam", command.exePath)
        assertEquals(
            listOf("exec", "--switch", "5.1.1", "--", "dune", "--version"),
            command.parametersList.list,
        )
    }

    @Test
    fun `independent probes run on the supplied executor`() {
        val executor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "managed-toolchain-probe-test")
        }
        val threadNames = Collections.synchronizedList(mutableListOf<String>())

        try {
            detectToolchain(
                ToolchainSettingsSnapshot(false, "", "", "", "", ""),
                projectBasePath = null,
                probeExecutor = executor,
            ) { _, _, _ ->
                threadNames += Thread.currentThread().name
                ToolProbeResult("OK", isAvailable = true)
            }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(4, threadNames.size)
        assertTrue(threadNames.all { it == "managed-toolchain-probe-test" })
    }
}
