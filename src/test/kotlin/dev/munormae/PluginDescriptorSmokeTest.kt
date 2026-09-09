package dev.munormae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class PluginDescriptorSmokeTest {
    @Test
    fun `backend descriptor loads the LSP module and provider`() {
        val descriptor = requireNotNull(
            javaClass.classLoader.getResourceAsStream("ocaml.jetbrains.backend.xml"),
        ) { "The packaged backend module descriptor is missing" }

        val document = descriptor.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val dependencies = document.getElementsByTagName("module")
        val moduleNames = (0 until dependencies.length)
            .map { dependencies.item(it).attributes.getNamedItem("name").nodeValue }

        assertTrue(
            "The backend module must depend on the actual IntelliJ LSP content module",
            "intellij.platform.lsp" in moduleNames,
        )
        assertFalse(
            "The legacy LSP compatibility alias is not a resolvable content module",
            "com.intellij.modules.lsp" in moduleNames,
        )

        val providers = document.getElementsByTagName("platform.lsp.integrationProvider")
        assertEquals("Exactly one OCaml LSP provider must be registered", 1, providers.length)
        assertEquals(
            "dev.munormae.lsp.OCamlLspIntegrationProvider",
            providers.item(0).attributes.getNamedItem("implementation").nodeValue,
        )

        val services = document.getElementsByTagName("projectService")
        assertTrue(
            "The backend module must register the Dune watch lifecycle service",
            (0 until services.length).any {
                services.item(it).attributes.getNamedItem("serviceImplementation").nodeValue ==
                    "dev.munormae.dune.DuneWatchService"
            },
        )
    }
}
