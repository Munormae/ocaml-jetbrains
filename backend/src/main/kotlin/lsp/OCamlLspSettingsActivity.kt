package dev.munormae.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.platform.lsp.api.LspClientManager
import dev.munormae.dune.DuneWatchService
import dev.munormae.dune.run.DuneRunConfigurationProvisioningService
import dev.munormae.settings.OCamlProjectSettings
import dev.munormae.settings.OCamlSettingsChangedListener
import dev.munormae.toolchain.OCamlToolchainDetectionService

class OCamlLspSettingsActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val currentProject = project
        project.messageBus.connect(project).subscribe(
            OCamlProjectSettings.CHANGED_TOPIC,
            OCamlSettingsChangedListener {
                LspClientManager.getInstance(project)
                    .stopAndRestartClientsIfNeeded(OCamlLspIntegrationProvider::class.java)
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) DuneWatchService.getInstance(project).refresh()
                }
                OCamlToolchainDetectionService.getInstance(project).refresh()
                DuneRunConfigurationProvisioningService.getInstance(project).requestRefresh()
            },
        )
        ApplicationManager.getApplication().messageBus.connect(project).subscribe(
            TrustedProjectsListener.TOPIC,
            object : TrustedProjectsListener {
                override fun onProjectTrusted(project: Project) {
                    if (project === currentProject) handleTrustChange(project, trusted = true)
                }

                override fun onProjectUntrusted(project: Project) {
                    if (project === currentProject) handleTrustChange(project, trusted = false)
                }
            },
        )
        DuneWatchService.getInstance(project).refresh()
        OCamlToolchainDetectionService.getInstance(project).refresh()
        DuneRunConfigurationProvisioningService.getInstance(project).requestRefresh(delayMs = 0)
    }

    private fun handleTrustChange(project: Project, trusted: Boolean) {
        if (project.isDisposed || ApplicationManager.getApplication().isUnitTestMode) return
        val lspClients = LspClientManager.getInstance(project)
        if (trusted) {
            lspClients.startClientsIfNeeded(OCamlLspIntegrationProvider::class.java)
        } else {
            lspClients.stopClients(OCamlLspIntegrationProvider::class.java)
        }
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) DuneWatchService.getInstance(project).refresh()
        }
        OCamlToolchainDetectionService.getInstance(project).refresh()
        if (trusted) DuneRunConfigurationProvisioningService.getInstance(project).requestRefresh(delayMs = 0)
    }
}
