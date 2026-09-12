package dev.munormae.lsp

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspFormattingCustomizer
import com.intellij.platform.lsp.api.customization.LspFormattingDisabled
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import com.intellij.util.execution.ParametersListUtil
import dev.munormae.OCamlBundle
import dev.munormae.icons.OCamlIcons
import dev.munormae.settings.OCamlProjectSettings
import dev.munormae.settings.OCamlWorkspaceSettings
import dev.munormae.toolchain.OCamlEnvironmentTool
import dev.munormae.toolchain.createOCamlEnvironmentCommandLine

class OCamlLspIntegrationProvider : LspIntegrationProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        clientStarter: LspIntegrationProvider.LspClientStarter,
    ) {
        if (!shouldStartOCamlLsp(
                lspEnabled = OCamlProjectSettings.getInstance(project).state.lspEnabled,
                trusted = TrustedProjects.isProjectTrusted(project),
                extension = file.extension,
                languageServerAvailable = project.languageServerAvailable(),
            )
        ) return

        clientStarter.ensureClientStarted(OCamlLspClientDescriptor(project))
    }

    override fun createWidgetItem(lspClient: LspClient, currentFile: VirtualFile?): LspClientWidgetItem =
        OCamlLspWidgetItem(lspClient, currentFile)
}

internal fun shouldStartOCamlLsp(
    lspEnabled: Boolean,
    trusted: Boolean,
    extension: String?,
    languageServerAvailable: Boolean = true,
): Boolean = lspEnabled && trusted && languageServerAvailable && extension?.lowercase() in OCAML_EXTENSIONS

private val OCAML_EXTENSIONS = setOf("ml", "mli")

private class OCamlLspClientDescriptor(project: Project) :
    ProjectWideLspClientDescriptor(project, OCamlBundle.message("lsp.name")) {

    override val lspCustomization: LspCustomization = object : LspCustomization() {
        override val formattingCustomizer: LspFormattingCustomizer =
            if (OCamlProjectSettings.getInstance(project).state.formatWithOcamlformat) {
                LspFormattingSupport()
            } else {
                LspFormattingDisabled
            }
    }

    override fun isSupportedFile(file: VirtualFile): Boolean = shouldStartOCamlLsp(
        lspEnabled = OCamlProjectSettings.getInstance(project).state.lspEnabled,
        trusted = TrustedProjects.isProjectTrusted(project),
        extension = file.extension,
        languageServerAvailable = project.languageServerAvailable(),
    )

    override fun getLanguageId(file: VirtualFile): String = "ocaml"

    override fun createCommandLine(): GeneralCommandLine {
        if (!TrustedProjects.isProjectTrusted(project)) {
            throw ExecutionException(OCamlBundle.message("lsp.error.untrusted"))
        }
        val workspace = OCamlWorkspaceSettings.getInstance(project).state
        return createOCamlEnvironmentCommandLine(
            project = project,
            tool = OCamlEnvironmentTool.LANGUAGE_SERVER,
            arguments = ParametersListUtil.parse(workspace.additionalLspArguments.orEmpty()),
        )
    }
}

private class OCamlLspWidgetItem(lspClient: LspClient, currentFile: VirtualFile?) :
    LspClientWidgetItem(lspClient, currentFile, OCamlIcons.File) {
    override fun createWidgetInlineActions(): List<AnAction> =
        super.createWidgetInlineActions() + openSettingsAction(OCamlBundle.message("lsp.widget.settings"))

    private fun openSettingsAction(text: String): AnAction = object : AnAction(text, null, OCamlIcons.File) {
        override fun actionPerformed(event: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(lspClient.project, OCAML_SETTINGS_ID)
        }
    }
}

private const val OCAML_SETTINGS_ID = "ocaml.settings"

private fun Project.languageServerAvailable(): Boolean =
    dev.munormae.toolchain.OCamlToolchainDetectionService.getInstance(this)
        .status.selectedEnvironment?.languageServer?.isAvailable == true
