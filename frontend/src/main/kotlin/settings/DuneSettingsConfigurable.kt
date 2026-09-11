package dev.munormae.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import dev.munormae.OCamlBundle
import dev.munormae.toolchain.DuneProjectSyncState
import dev.munormae.toolchain.OCamlToolchainSettingsDto
import dev.munormae.toolchain.OCamlToolchainStatusChangedListener
import dev.munormae.toolchain.OCamlToolchainStatusService
import dev.munormae.toolchain.OCamlToolchainStatusSnapshot

class DuneSettingsConfigurable(private val project: Project) :
    BoundConfigurable(OCamlBundle.message("dune.settings.display.name")) {

    private lateinit var rootLabel: JBLabel
    private lateinit var statusLabel: JBLabel
    private lateinit var targetsLabel: JBLabel

    override fun createPanel(): DialogPanel {
        val workspace = OCamlWorkspaceSettings.getInstance(project).state
        val service = OCamlToolchainStatusService.getInstance(project)
        project.messageBus.connect(checkNotNull(disposable)).subscribe(
            OCamlToolchainStatusService.CHANGED_TOPIC,
            OCamlToolchainStatusChangedListener(::updateStatus),
        )
        return panel {
            group(OCamlBundle.message("dune.settings.project.group")) {
                row(OCamlBundle.message("settings.dune.project")) {
                    rootLabel = label("").component
                }
                row(OCamlBundle.message("settings.dune.status")) {
                    statusLabel = label("").component
                }
                row(OCamlBundle.message("dune.settings.targets")) {
                    targetsLabel = label("").component
                }
                row {
                    button(OCamlBundle.message("settings.refresh")) {
                        service.refresh(
                            OCamlToolchainSettingsDto(
                                environmentId = workspace.environmentId.orEmpty(),
                                environmentPrefixOverride = workspace.environmentPrefixOverride.orEmpty(),
                                opamExecutableOverride = workspace.opamExecutableOverride.orEmpty(),
                                lspExecutableOverride = workspace.lspExecutableOverride.orEmpty(),
                                duneExecutableOverride = workspace.duneExecutableOverride.orEmpty(),
                                ocamlformatExecutableOverride = workspace.ocamlformatExecutableOverride.orEmpty(),
                            ),
                        )
                    }
                }
            }
            group(OCamlBundle.message("dune.settings.lifecycle.group")) {
                row {
                    checkBox(OCamlBundle.message("settings.advanced.watch"))
                        .bindSelected(workspace::manageDuneWatch)
                }
            }
        }.also { updateStatus(service.snapshot) }
    }

    override fun apply() {
        super.apply()
        OCamlProjectSettings.getInstance(project).notifyChanged()
    }

    private fun updateStatus(snapshot: OCamlToolchainStatusSnapshot) {
        rootLabel.text = snapshot.duneProject.root.ifBlank { "—" }
        statusLabel.text = when (snapshot.duneProject.state) {
            DuneProjectSyncState.NOT_LOADED -> OCamlBundle.message("status.dune.not.loaded")
            DuneProjectSyncState.LOADING -> OCamlBundle.message("status.dune.loading")
            DuneProjectSyncState.READY -> OCamlBundle.message("status.dune.ready")
            DuneProjectSyncState.FAILED -> OCamlBundle.message("status.dune.failed", snapshot.duneProject.problem)
        }
        targetsLabel.text = OCamlBundle.message(
            "dune.settings.targets.summary",
            snapshot.duneProject.executableCount,
            snapshot.duneProject.libraryCount,
            snapshot.duneProject.testCount,
        )
    }
}
