package dev.munormae

import dev.munormae.toolchain.OCamlToolchainStatusSnapshot
import dev.munormae.toolchain.OCamlEnvironmentDescriptor
import dev.munormae.toolchain.OCamlEnvironmentKind
import dev.munormae.toolchain.OCamlToolAvailability
import dev.munormae.toolchain.OCamlToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OCamlToolchainStatusSnapshotTest {
    @Test
    fun `toolchain status snapshot preserves every tool value`() {
        val snapshot = OCamlToolchainStatusSnapshot("opam", "ocamllsp", "dune", "ocamlformat")

        assertEquals("opam", snapshot.opam)
        assertEquals("ocamllsp", snapshot.ocamllsp)
        assertEquals("dune", snapshot.dune)
        assertEquals("ocamlformat", snapshot.ocamlformat)
    }

    @Test
    fun `environment readiness requires compiler Dune and language server`() {
        val ready = environment(toolAvailability = OCamlToolAvailability.AVAILABLE)
        val missingLsp = ready.copy(
            languageServer = ready.languageServer.copy(availability = OCamlToolAvailability.MISSING),
        )
        val missingFormatter = ready.copy(
            formatter = ready.formatter.copy(availability = OCamlToolAvailability.MISSING),
        )

        assertTrue(ready.isReady)
        assertTrue(ready.hasAllTools)
        assertFalse(missingLsp.isReady)
        assertTrue(missingFormatter.isReady)
        assertFalse(missingFormatter.hasAllTools)
        assertTrue(missingLsp.canInstallTools)
    }

    @Test
    fun `snapshot resolves selected environment by stable id`() {
        val first = environment(id = "opam:5.3.0")
        val selected = environment(id = "local:C:/work/camel/_opam", kind = OCamlEnvironmentKind.LOCAL_OPAM_SWITCH)
        val snapshot = OCamlToolchainStatusSnapshot(
            opam = "OK",
            ocamllsp = "OK",
            dune = "OK",
            ocamlformat = "OK",
            environments = listOf(first, selected),
            selectedEnvironmentId = selected.id,
        )

        assertSame(selected, snapshot.selectedEnvironment)
    }

    private fun environment(
        id: String = "opam:5.3.0",
        kind: OCamlEnvironmentKind = OCamlEnvironmentKind.OPAM_SWITCH,
        toolAvailability: OCamlToolAvailability = OCamlToolAvailability.AVAILABLE,
    ): OCamlEnvironmentDescriptor {
        val tool = OCamlToolStatus(
            availability = toolAvailability,
            version = "5.3.0",
            executable = "/switch/bin/tool",
        )
        return OCamlEnvironmentDescriptor(
            id = id,
            name = "OCaml 5.3.0",
            kind = kind,
            switchName = "5.3.0",
            prefix = "/switch",
            compiler = tool,
            dune = tool.copy(version = "3.24.2"),
            languageServer = tool.copy(version = "1.23.0"),
            formatter = tool.copy(version = "0.27.0"),
            canInstallTools = true,
        )
    }
}
