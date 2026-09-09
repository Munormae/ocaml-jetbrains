package dev.munormae.project

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.util.IncorrectOperationException
import dev.munormae.icons.OCamlIcons

class CreateOCamlModuleAction : CreateFileFromTemplateAction(
    "OCaml Module",
    "Creates an OCaml module, interface, or both",
    OCamlIcons.File,
) {
    override fun buildDialog(
        project: Project,
        directory: PsiDirectory,
        builder: CreateFileFromTemplateDialog.Builder,
    ) {
        builder
            .setTitle("New OCaml Module (snake_case recommended)")
            .setValidator(MODULE_NAME_VALIDATOR)
            .addKind("Module (.ml)", OCamlIcons.File, MODULE_TEMPLATE)
            .addKind("Interface (.mli)", OCamlIcons.File, INTERFACE_TEMPLATE)
            .addKind("Module + Interface", OCamlIcons.File, MODULE_AND_INTERFACE_TEMPLATE)
    }

    override fun createFile(name: String, templateName: String, directory: PsiDirectory): PsiFile {
        val baseName = moduleFileBaseName(name)
        if (templateName != MODULE_AND_INTERFACE_TEMPLATE) {
            return requireNotNull(super.createFile(baseName, templateName, directory)) {
                "Unable to create an OCaml file from template $templateName"
            }
        }

        val conflictingFile = listOfNotNull(
            directory.findFile("$baseName.ml"),
            directory.findFile("$baseName.mli"),
        ).firstOrNull()
        if (conflictingFile != null) {
            throw IncorrectOperationException("File already exists: ${conflictingFile.name}")
        }

        val implementation = requireNotNull(super.createFile(baseName, MODULE_TEMPLATE, directory)) {
            "Unable to create an OCaml implementation file"
        }
        requireNotNull(super.createFile(baseName, INTERFACE_TEMPLATE, directory)) {
            "Unable to create an OCaml interface file"
        }
        return implementation
    }

    override fun getActionName(directory: PsiDirectory, newName: String, templateName: String): String =
        "Create OCaml module ${moduleFileBaseName(newName)}"

    companion object {
        internal const val MODULE_TEMPLATE = "OCaml Module"
        internal const val INTERFACE_TEMPLATE = "OCaml Interface"
        internal const val MODULE_AND_INTERFACE_TEMPLATE = "OCaml Module and Interface"

        private val MODULE_NAME_VALIDATOR = object : InputValidator {
            override fun checkInput(inputString: String): Boolean = isValidModuleFileName(inputString)

            override fun canClose(inputString: String): Boolean = checkInput(inputString)
        }
    }
}

internal fun moduleFileBaseName(input: String): String {
    val trimmed = input.trim()
    return when {
        trimmed.endsWith(".mli", ignoreCase = true) -> trimmed.dropLast(4)
        trimmed.endsWith(".ml", ignoreCase = true) -> trimmed.dropLast(3)
        else -> trimmed
    }
}

internal fun isValidModuleFileName(input: String): Boolean =
    moduleFileBaseName(input).matches(Regex("[A-Za-z][A-Za-z0-9_']*"))
