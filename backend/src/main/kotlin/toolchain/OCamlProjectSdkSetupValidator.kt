package dev.munormae.toolchain

import com.intellij.codeInsight.daemon.ProjectSdkSetupValidator
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.ui.EditorNotificationPanel
import dev.munormae.OCamlBundle
import javax.swing.event.HyperlinkEvent

class OCamlProjectSdkSetupValidator : ProjectSdkSetupValidator {
    override fun isApplicableFor(project: Project, file: VirtualFile): Boolean =
        isOCamlSourceFile(file) &&
            ProjectFileIndex.getInstance(project).isInContent(file) &&
            OCamlToolchainDetectionService.getInstance(project).status.selectedEnvironment?.isReady == true

    override fun getErrorMessage(project: Project, file: VirtualFile): String? =
        if (ProjectRootManager.getInstance(project).projectSdk?.sdkType is OCamlSdkType) null
        else OCamlBundle.message("notification.sdk.not.attached")

    override fun getFixHandler(project: Project, file: VirtualFile): EditorNotificationPanel.ActionHandler =
        object : EditorNotificationPanel.ActionHandler {
            override fun handlePanelActionClick(panel: EditorNotificationPanel, event: HyperlinkEvent) = fix(project)

            override fun handleQuickFixClick(editor: Editor, psiFile: PsiFile) = fix(project)
        }

    private fun fix(project: Project) {
        val environment = OCamlToolchainDetectionService.getInstance(project).status.selectedEnvironment
        if (environment != null) {
            OCamlEnvironmentSdkService.getInstance(project).synchronize(environment)
        } else {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, OCAML_SETTINGS_ID)
        }
    }
}

internal fun isOCamlSourceFile(file: VirtualFile): Boolean =
    file.extension?.lowercase() in setOf("ml", "mli")

private const val OCAML_SETTINGS_ID = "ocaml.settings"
