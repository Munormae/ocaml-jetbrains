package dev.munormae.toolchain

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import dev.munormae.OCamlBundle
import dev.munormae.dune.findDuneRoot
import dev.munormae.settings.OCamlProjectSettings
import java.util.function.Function
import javax.swing.JComponent

internal enum class OCamlSetupProblem {
    NONE,
    DETECTION_FAILED,
    ENVIRONMENT_MISSING,
    DUNE_MISSING,
    LANGUAGE_SERVER_MISSING,
    FORMATTER_MISSING,
}

internal fun ocamlSetupProblem(
    extension: String?,
    inProjectContent: Boolean,
    detectionState: OCamlEnvironmentDetectionState,
    environment: OCamlEnvironmentDescriptor?,
    duneRequired: Boolean = true,
    formatterRequired: Boolean = true,
): OCamlSetupProblem {
    if (extension?.lowercase() !in setOf("ml", "mli") || !inProjectContent) return OCamlSetupProblem.NONE
    if (detectionState == OCamlEnvironmentDetectionState.FAILED) return OCamlSetupProblem.DETECTION_FAILED
    if (detectionState != OCamlEnvironmentDetectionState.READY) return OCamlSetupProblem.NONE
    if (environment?.compiler?.isAvailable != true) return OCamlSetupProblem.ENVIRONMENT_MISSING
    if (duneRequired && !environment.dune.isAvailable) return OCamlSetupProblem.DUNE_MISSING
    if (!environment.languageServer.isAvailable) return OCamlSetupProblem.LANGUAGE_SERVER_MISSING
    if (formatterRequired && !environment.formatter.isAvailable) return OCamlSetupProblem.FORMATTER_MISSING
    return OCamlSetupProblem.NONE
}

class OCamlEditorNotificationProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        val status = OCamlToolchainDetectionService.getInstance(project).status
        val problem = ocamlSetupProblem(
            extension = file.extension,
            inProjectContent = ProjectFileIndex.getInstance(project).isInContent(file),
            detectionState = status.detectionState,
            environment = status.selectedEnvironment,
            duneRequired = findDuneRoot(file.path) != null,
            formatterRequired = OCamlProjectSettings.getInstance(project).state.formatWithOcamlformat,
        )
        if (problem == OCamlSetupProblem.NONE) return null
        val environment = status.selectedEnvironment
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text(
                    when (problem) {
                        OCamlSetupProblem.DETECTION_FAILED ->
                            OCamlBundle.message("notification.environment.failed", status.problem)
                        OCamlSetupProblem.ENVIRONMENT_MISSING ->
                            OCamlBundle.message("notification.environment.missing")
                        OCamlSetupProblem.DUNE_MISSING ->
                            OCamlBundle.message("notification.dune.missing")
                        OCamlSetupProblem.LANGUAGE_SERVER_MISSING ->
                            OCamlBundle.message("notification.lsp.missing")
                        OCamlSetupProblem.FORMATTER_MISSING ->
                            OCamlBundle.message("notification.formatter.missing")
                        OCamlSetupProblem.NONE -> ""
                    },
                )
                createActionLabel(OCamlBundle.message("notification.configure")) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, OCAML_SETTINGS_ID)
                }
                if (problem == OCamlSetupProblem.ENVIRONMENT_MISSING) {
                    createActionLabel(OCamlBundle.message("notification.create.local")) {
                        confirmCreateLocalEnvironment(project)
                    }
                } else if (environment?.canInstallTools == true) {
                    createActionLabel(OCamlBundle.message("notification.install")) {
                        confirmInstallTools(project, environment)
                    }
                }
            }
        }
    }

    private fun confirmCreateLocalEnvironment(project: Project) {
        if (Messages.showYesNoDialog(
                project,
                OCamlBundle.message("notification.create.local.confirm"),
                OCamlBundle.message("notification.create.local"),
                Messages.getQuestionIcon(),
            ) == Messages.YES
        ) {
            OCamlToolchainDetectionService.getInstance(project).createLocalEnvironment()
        }
    }

    private fun confirmInstallTools(project: Project, environment: OCamlEnvironmentDescriptor) {
        if (Messages.showYesNoDialog(
                project,
                OCamlBundle.message("settings.install.confirm", environment.name),
                OCamlBundle.message("settings.install.confirm.title"),
                Messages.getQuestionIcon(),
            ) == Messages.YES
        ) {
            OCamlToolchainDetectionService.getInstance(project).installRequiredTools(environment.id)
        }
    }
}

private const val OCAML_SETTINGS_ID = "ocaml.settings"
