package dev.munormae

import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.ide.wizard.language.LanguageGeneratorNewProjectWizard
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.munormae.project.CreateOCamlModuleAction
import dev.munormae.project.OCamlDuneTemplatePropertiesProvider
import dev.munormae.project.OCamlNewProjectWizard
import java.util.Properties

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

    fun testOCamlIsRegisteredAsANewProjectLanguage() {
        assertTrue(
            LanguageGeneratorNewProjectWizard.EP_NAME.extensionList.any {
                it is OCamlNewProjectWizard && it.name == "OCaml"
            },
        )
    }

    fun testDuneFileTemplatesReceiveTheSafeDefaultLanguageVersion() {
        val virtualDirectory = myFixture.tempDirFixture.findOrCreateDir("template-project")
        val directory = checkNotNull(PsiManager.getInstance(project).findDirectory(virtualDirectory))
        val properties = Properties()
        OCamlDuneTemplatePropertiesProvider().fillProperties(directory, properties)

        val template = FileTemplateManager.getInstance(project).findInternalTemplate("Dune Project")
        assertEquals("3.0", properties.getProperty("DUNE_LANGUAGE_VERSION"))
        assertTrue(template.getText(properties).startsWith("(lang dune 3.0)"))
    }

    fun testOCamlModulePairCanBeCreatedWithoutPlatformErrors() {
        val virtualDirectory = myFixture.tempDirFixture.findOrCreateDir("src")
        val directory = checkNotNull(PsiManager.getInstance(project).findDirectory(virtualDirectory))
        val action = CreateOCamlModuleAction()

        WriteCommandAction.runWriteCommandAction(project) {
            action.createFile("camel_case", "OCaml Module and Interface", directory)
        }

        assertNotNull(directory.findFile("camel_case.ml"))
        assertNotNull(directory.findFile("camel_case.mli"))
    }
}
