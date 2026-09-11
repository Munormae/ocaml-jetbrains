package dev.munormae.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

@State(name = OCamlProjectSettings.COMPONENT_NAME, storages = [Storage("ocaml.xml")])
class OCamlProjectSettings(private val project: Project) :
    SimplePersistentStateComponent<OCamlProjectSettings.SettingsState>(SettingsState()) {

    class SettingsState : BaseState() {
        var lspEnabled by property(true)
        var formatWithOcamlformat by property(true)

        // Kept only to read and migrate settings written by 0.1.0 snapshots.
        var useOpam by property(true)
        var opamExecutable by string("")
        var opamSwitch by string("")
        var lspExecutable by string("")
        var additionalLspArguments by string("")
        var duneWatchEnabled by property(false)
        var duneExecutable by string("")
        var ocamlformatExecutable by string("")
    }

    override fun loadState(state: SettingsState) {
        val hasLegacyMachineState = !state.useOpam ||
            !state.opamExecutable.isNullOrBlank() ||
            !state.opamSwitch.isNullOrBlank() ||
            !state.lspExecutable.isNullOrBlank() ||
            !state.additionalLspArguments.isNullOrBlank() ||
            state.duneWatchEnabled ||
            !state.duneExecutable.isNullOrBlank() ||
            !state.ocamlformatExecutable.isNullOrBlank()
        if (hasLegacyMachineState) {
            OCamlWorkspaceSettings.getInstance(project).migrateLegacy(
                useOpam = state.useOpam,
                opamExecutable = state.opamExecutable.orEmpty(),
                opamSwitch = state.opamSwitch.orEmpty(),
                lspExecutable = state.lspExecutable.orEmpty(),
                additionalLspArguments = state.additionalLspArguments.orEmpty(),
                duneWatchEnabled = state.duneWatchEnabled,
                duneExecutable = state.duneExecutable.orEmpty(),
                ocamlformatExecutable = state.ocamlformatExecutable.orEmpty(),
            )
            state.useOpam = true
            state.opamExecutable = ""
            state.opamSwitch = ""
            state.lspExecutable = ""
            state.additionalLspArguments = ""
            state.duneWatchEnabled = false
            state.duneExecutable = ""
            state.ocamlformatExecutable = ""
        }
        super.loadState(state)
        project.messageBus.syncPublisher(CHANGED_TOPIC).settingsChanged()
    }

    override fun noStateLoaded() {
        loadState(SettingsState())
    }

    fun notifyChanged() {
        project.messageBus.syncPublisher(CHANGED_TOPIC).settingsChanged()
    }

    companion object {
        const val COMPONENT_NAME = "OCamlProjectSettings"

        @JvmField
        val CHANGED_TOPIC: Topic<OCamlSettingsChangedListener> = Topic.create(
            "OCaml project settings changed",
            OCamlSettingsChangedListener::class.java,
        )

        fun getInstance(project: Project): OCamlProjectSettings = project.service()
    }
}

fun interface OCamlSettingsChangedListener {
    fun settingsChanged()
}
