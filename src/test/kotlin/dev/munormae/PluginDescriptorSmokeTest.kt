package dev.munormae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class PluginDescriptorSmokeTest {
    @Test
    fun `backend descriptor exposes the transient toolchain status RPC`() {
        val descriptor = requireNotNull(
            javaClass.classLoader.getResourceAsStream("ocaml.jetbrains.backend.xml"),
        ) { "The packaged backend module descriptor is missing" }

        val document = descriptor.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val providers = document.getElementsByTagName("platform.rpc.backend.remoteApiProvider")
        assertEquals("Exactly one toolchain RPC provider must be registered", 1, providers.length)
        assertEquals(
            "dev.munormae.toolchain.OCamlToolchainRpcApiProvider",
            providers.item(0).attributes.getNamedItem("implementation").nodeValue,
        )
    }

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
        assertTrue(
            "The module creation action must declare its IntelliJ language implementation dependency",
            "intellij.platform.lang.impl" in moduleNames,
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

        val projectWizards = document.getElementsByTagName("newProjectWizard.languageGenerator")
        assertEquals("Exactly one OCaml project wizard must be registered in the backend", 1, projectWizards.length)
        assertEquals(
            "dev.munormae.project.OCamlNewProjectWizard",
            projectWizards.item(0).attributes.getNamedItem("implementation").nodeValue,
        )

        val templatePropertyProviders = document.getElementsByTagName("defaultTemplatePropertiesProvider")
        assertEquals("Exactly one OCaml template-properties provider must be registered", 1, templatePropertyProviders.length)
        assertEquals(
            "dev.munormae.project.OCamlDuneTemplatePropertiesProvider",
            templatePropertyProviders.item(0).attributes.getNamedItem("implementation").nodeValue,
        )

        val internalFileTemplates = document.getElementsByTagName("internalFileTemplate")
        val internalFileTemplateNames = (0 until internalFileTemplates.length)
            .map { internalFileTemplates.item(it).attributes.getNamedItem("name").nodeValue }
            .toSet()
        assertTrue(
            "Every bundled OCaml/Dune template must be registered with the platform",
            setOf(
                "OCaml Module",
                "OCaml Interface",
                "Dune File",
                "Dune Project",
                "Dune Workspace",
                "OPAM Package",
                "OCaml Format",
            ).all { it in internalFileTemplateNames },
        )

        val services = document.getElementsByTagName("projectService")
        assertTrue(
            "The backend module must register the Dune watch lifecycle service",
            (0 until services.length).any {
                services.item(it).attributes.getNamedItem("serviceImplementation").nodeValue ==
                    "dev.munormae.dune.DuneWatchService"
            },
        )
        assertTrue(
            "The backend module must register live Dune configuration provisioning",
            (0 until services.length).any {
                services.item(it).attributes.getNamedItem("serviceImplementation").nodeValue ==
                    "dev.munormae.dune.run.DuneRunConfigurationProvisioningService"
            },
        )
        assertTrue(
            "The backend module must register toolchain detection",
            (0 until services.length).any {
                services.item(it).attributes.getNamedItem("serviceImplementation").nodeValue ==
                    "dev.munormae.toolchain.OCamlToolchainDetectionService"
            },
        )

        val configurationTypes = document.getElementsByTagName("configurationType")
        assertEquals("Exactly one Dune run configuration type must be registered", 1, configurationTypes.length)
        assertEquals(
            "dev.munormae.dune.run.DuneRunConfigurationType",
            configurationTypes.item(0).attributes.getNamedItem("implementation").nodeValue,
        )

        val startupActivities = document.getElementsByTagName("postStartupActivity")
        assertTrue(
            "The backend must discover Dune run configurations when a project opens",
            (0 until startupActivities.length).any {
                startupActivities.item(it).attributes.getNamedItem("implementation").nodeValue ==
                    "dev.munormae.dune.run.DuneRunConfigurationProvisioningActivity"
            },
        )

        val actions = document.getElementsByTagName("action")
        assertTrue(
            "The backend module must register the OCaml module creation action",
            (0 until actions.length).any {
                actions.item(it).attributes.getNamedItem("id").nodeValue == "OCaml.NewModule"
            },
        )
    }
}
