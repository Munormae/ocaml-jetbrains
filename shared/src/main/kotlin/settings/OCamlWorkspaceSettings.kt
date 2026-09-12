package dev.munormae.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@State(
    name = OCamlWorkspaceSettings.COMPONENT_NAME,
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class OCamlWorkspaceSettings(private val project: Project) :
    SimplePersistentStateComponent<OCamlWorkspaceSettings.WorkspaceState>(WorkspaceState()) {

    class WorkspaceState : BaseState() {
        var environmentId by string("")
        var environmentPrefixOverride by string("")
        var opamExecutableOverride by string("")
        var lspExecutableOverride by string("")
        var duneExecutableOverride by string("")
        var ocamlformatExecutableOverride by string("")
        var additionalLspArguments by string("")
        var manageDuneWatch by property(true)
        var legacyUseOpam by property(true)
        var legacyOpamSwitch by string("")
    }

    internal fun migrateLegacy(
        useOpam: Boolean,
        opamExecutable: String,
        opamSwitch: String,
        lspExecutable: String,
        additionalLspArguments: String,
        duneWatchEnabled: Boolean,
        duneExecutable: String,
        ocamlformatExecutable: String,
    ) {
        if (!state.environmentId.isNullOrBlank() || state.hasOverrides()) return
        state.opamExecutableOverride = opamExecutable
        state.lspExecutableOverride = lspExecutable
        state.duneExecutableOverride = duneExecutable
        state.ocamlformatExecutableOverride = ocamlformatExecutable
        state.additionalLspArguments = additionalLspArguments
        state.manageDuneWatch = duneWatchEnabled
        state.legacyUseOpam = useOpam
        state.legacyOpamSwitch = opamSwitch
    }

    private fun WorkspaceState.hasOverrides(): Boolean =
        !opamExecutableOverride.isNullOrBlank() ||
            !environmentPrefixOverride.isNullOrBlank() ||
            !lspExecutableOverride.isNullOrBlank() ||
            !duneExecutableOverride.isNullOrBlank() ||
            !ocamlformatExecutableOverride.isNullOrBlank() ||
            !additionalLspArguments.isNullOrBlank() ||
            !legacyOpamSwitch.isNullOrBlank()

    companion object {
        const val COMPONENT_NAME = "OCamlWorkspaceSettings"

        fun getInstance(project: Project): OCamlWorkspaceSettings = project.service()
    }
}
