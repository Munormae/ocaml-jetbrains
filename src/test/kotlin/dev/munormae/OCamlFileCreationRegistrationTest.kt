package dev.munormae

import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.munormae.project.CreateOCamlModuleAction

class OCamlFileCreationRegistrationTest : BasePlatformTestCase() {
    fun testOCamlModuleActionAndFileTemplatesAreRegistered() {
        val action = ActionManager.getInstance().getAction("OCaml.NewModule")
        assertInstanceOf(action, CreateOCamlModuleAction::class.java)

        val templates = FileTemplateManager.getInstance(project)
        assertNotNull(templates.findInternalTemplate("OCaml Module"))
        assertNotNull(templates.findInternalTemplate("OCaml Interface"))

        val fileTemplateNames = templates.allTemplates.map { it.name }.toSet()
        assertContainsElements(
            fileTemplateNames,
            "Dune File",
            "Dune Project",
            "Dune Workspace",
            "OPAM Package",
            "OCaml Format",
        )
    }
}
