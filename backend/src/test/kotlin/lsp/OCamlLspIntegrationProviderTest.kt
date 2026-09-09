package dev.munormae.lsp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OCamlLspIntegrationProviderTest {
    @Test
    fun `starts only for enabled OCaml files in trusted projects`() {
        assertTrue(shouldStartOCamlLsp(lspEnabled = true, trusted = true, extension = "ml"))
        assertTrue(shouldStartOCamlLsp(lspEnabled = true, trusted = true, extension = "MLI"))
        assertFalse(shouldStartOCamlLsp(lspEnabled = true, trusted = false, extension = "ml"))
        assertFalse(shouldStartOCamlLsp(lspEnabled = false, trusted = true, extension = "ml"))
        assertFalse(shouldStartOCamlLsp(lspEnabled = true, trusted = true, extension = "dune"))
    }
}
