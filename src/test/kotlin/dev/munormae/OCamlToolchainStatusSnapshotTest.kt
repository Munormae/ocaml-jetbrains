package dev.munormae

import dev.munormae.toolchain.OCamlToolchainStatusSnapshot
import org.junit.Assert.assertEquals
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
}
