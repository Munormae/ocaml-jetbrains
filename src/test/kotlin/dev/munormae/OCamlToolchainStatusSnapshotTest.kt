package dev.munormae

import dev.munormae.toolchain.OCamlToolchainStatusSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class OCamlToolchainStatusSnapshotTest {
    @Test
    fun `transient toolchain status preserves all fields for split-mode RPC`() {
        val snapshot = OCamlToolchainStatusSnapshot("opam", "ocamllsp", "dune", "ocamlformat")

        assertEquals("opam", snapshot.opam)
        assertEquals("ocamllsp", snapshot.ocamllsp)
        assertEquals("dune", snapshot.dune)
        assertEquals("ocamlformat", snapshot.ocamlformat)
    }
}
