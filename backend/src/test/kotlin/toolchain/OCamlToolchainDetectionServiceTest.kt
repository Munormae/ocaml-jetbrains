package dev.munormae.toolchain

import dev.munormae.settings.OCamlWorkspaceSettings
import java.util.Collections
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OCamlToolchainDetectionServiceTest {
    @Test
    fun `selecting a non-custom environment clears the previous directory override`() {
        val state = OCamlWorkspaceSettings.WorkspaceState().apply {
            environmentId = "custom:C:/ocaml"
            environmentPrefixOverride = "C:/ocaml"
        }

        updateEnvironmentSelection(state, "opam_switch:5.3.0", "")

        assertEquals("opam_switch:5.3.0", state.environmentId)
        assertEquals("", state.environmentPrefixOverride)
    }

    @Test
    fun `directory-only selection resolves to the backend-normalized custom environment id`() {
        val prefix = "C:/ocaml"

        assertEquals(
            environmentCandidate(OCamlEnvironmentKind.CUSTOM, "", prefix).id,
            resolveEnvironmentSelectionId("", prefix),
        )
        assertEquals("opam_switch:5.3.0", resolveEnvironmentSelectionId("opam_switch:5.3.0", prefix))
    }

    @Test
    fun `OPAM switch list is normalized and de-duplicated`() {
        assertEquals(
            listOf("5.3.0", "default", "C:/work/camel"),
            parseOpamSwitchList(" 5.3.0\ndefault\n5.3.0\nC:/work/camel\n"),
        )
    }

    @Test
    fun `environment selection prefers requested then local then current OPAM then PATH`() {
        val path = environmentCandidate(OCamlEnvironmentKind.PATH, "", "C:/ocaml")
        val current = environmentCandidate(OCamlEnvironmentKind.OPAM_SWITCH, "5.3.0", "C:/opam/5.3.0")
        val local = environmentCandidate(OCamlEnvironmentKind.LOCAL_OPAM_SWITCH, "C:/work/camel", "C:/work/camel/_opam")
        val candidates = listOf(path, current, local)

        assertEquals(path.id, selectEnvironmentCandidate(candidates, path.id, "5.3.0")?.id)
        assertEquals(local.id, selectEnvironmentCandidate(candidates, "", "5.3.0")?.id)
        assertEquals(current.id, selectEnvironmentCandidate(listOf(path, current), "", "5.3.0")?.id)
        assertEquals(path.id, selectEnvironmentCandidate(listOf(path), "", "")?.id)
    }

    @Test
    fun `OPAM environment command keeps switch and tool arguments separate`() {
        val candidate = environmentCandidate(
            OCamlEnvironmentKind.OPAM_SWITCH,
            "5.3.0",
            "C:/opam/5.3.0",
        )

        val command = createEnvironmentCommandLine(
            candidate = candidate,
            opamExecutable = "C:/bin/opam.exe",
            executable = "ocamllsp",
            arguments = listOf("--version"),
        )

        assertEquals("C:/bin/opam.exe", command.exePath)
        assertEquals(
            listOf("exec", "--switch", "5.3.0", "--", "ocamllsp", "--version"),
            command.parametersList.list,
        )
    }

    @Test
    fun `selected directory environment runs tools directly`() {
        val candidate = environmentCandidate(
            OCamlEnvironmentKind.CUSTOM,
            "",
            "C:/ocaml",
        )

        val command = createEnvironmentCommandLine(
            candidate = candidate,
            opamExecutable = "opam",
            executable = "C:/ocaml/bin/ocamllsp.exe",
            arguments = listOf("--version"),
        )

        assertEquals("C:/ocaml/bin/ocamllsp.exe", command.exePath)
        assertEquals(listOf("--version"), command.parametersList.list)
    }

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
