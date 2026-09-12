package dev.munormae.dune.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DuneConsoleFilterTest {
    @Test
    fun `parses OCaml compiler source locations`() {
        assertEquals(
            OCamlCompilerLocation("test/foo.ml", 41, 2, 0, 43),
            parseOCamlCompilerLocation("File \"test/foo.ml\", line 42, characters 2-4:"),
        )
        assertEquals(
            OCamlCompilerLocation("lib/foo.ml", 0, 0, 0, 25),
            parseOCamlCompilerLocation("File \"lib/foo.ml\", line 1:"),
        )
    }

    @Test
    fun `ignores unrelated output`() {
        assertNull(parseOCamlCompilerLocation("Done: 12/12"))
    }
}
