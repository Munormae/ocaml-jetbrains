package dev.munormae.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OCamlRelatedFileProviderTest {
    @Test
    fun `resolves implementation and interface counterparts`() {
        assertEquals("foo.mli", ocamlCounterpartFileName("foo.ml"))
        assertEquals("foo.ml", ocamlCounterpartFileName("foo.mli"))
        assertNull(ocamlCounterpartFileName("dune"))
    }
}
