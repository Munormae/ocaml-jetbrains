package dev.munormae.repl

import dev.munormae.toolchain.OCamlEnvironmentDescriptor
import dev.munormae.toolchain.OCamlEnvironmentKind
import dev.munormae.toolchain.OCamlToolAvailability
import dev.munormae.toolchain.OCamlToolStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenOCamlReplActionTest {
    @Test
    fun `REPL requires trust Dune and UTop`() {
        val available = OCamlToolStatus(OCamlToolAvailability.AVAILABLE)
        val ready = OCamlEnvironmentDescriptor(
            id = "opam_switch:5.3.0",
            name = "OCaml",
            kind = OCamlEnvironmentKind.OPAM_SWITCH,
            dune = available,
            utop = available,
        )
        val missingUtop = ready.copy(utop = OCamlToolStatus(OCamlToolAvailability.MISSING))

        assertTrue(canOpenOCamlRepl(trusted = true, hasDuneRoot = true, environment = ready))
        assertFalse(canOpenOCamlRepl(trusted = false, hasDuneRoot = true, environment = ready))
        assertFalse(canOpenOCamlRepl(trusted = true, hasDuneRoot = false, environment = ready))
        assertFalse(canOpenOCamlRepl(trusted = true, hasDuneRoot = true, environment = missingUtop))
    }
}
