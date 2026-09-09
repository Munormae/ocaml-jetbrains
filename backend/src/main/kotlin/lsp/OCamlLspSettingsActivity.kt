package dev.munormae.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.platform.lsp.api.LspClientManager
import dev.munormae.settings.OCamlProjectSettings
import dev.munormae.settings.OCamlSettingsChangedListener

class OCamlLspSettingsActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.messageBus.connect(project).subscribe(
            OCamlProjectSettings.CHANGED_TOPIC,
            OCamlSettingsChangedListener {
                LspClientManager.getInstance(project)
                    .stopAndRestartClientsIfNeeded(OCamlLspIntegrationProvider::class.java)
            },
        )
    }
}
