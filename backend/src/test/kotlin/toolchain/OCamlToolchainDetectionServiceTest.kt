package dev.munormae.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
