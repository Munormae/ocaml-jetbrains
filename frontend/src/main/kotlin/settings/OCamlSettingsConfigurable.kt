package dev.munormae.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import dev.munormae.OCamlBundle
import dev.munormae.toolchain.DuneProjectSyncState
import dev.munormae.toolchain.OCamlEnvironmentDescriptor
import dev.munormae.toolchain.OCamlEnvironmentDetectionState
import dev.munormae.toolchain.OCamlEnvironmentKind
import dev.munormae.toolchain.OCamlToolAvailability
import dev.munormae.toolchain.OCamlToolStatus
import dev.munormae.toolchain.OCamlToolchainSettingsDto
import dev.munormae.toolchain.OCamlToolchainStatusChangedListener
import dev.munormae.toolchain.OCamlToolchainStatusService
import dev.munormae.toolchain.OCamlToolchainStatusSnapshot
import javax.swing.JButton
import javax.swing.JComboBox

class OCamlSettingsConfigurable(private val project: Project) :
    BoundConfigurable(OCamlBundle.message("settings.display.name")) {

    private val projectSettings: OCamlProjectSettings
        get() = OCamlProjectSettings.getInstance(project)
    private val workspaceSettings: OCamlWorkspaceSettings
        get() = OCamlWorkspaceSettings.getInstance(project)
    private val statusService: OCamlToolchainStatusService
        get() = OCamlToolchainStatusService.getInstance(project)

    private val environmentModel = CollectionComboBoxModel<OCamlEnvironmentDescriptor>()
    private lateinit var environmentCombo: JComboBox<OCamlEnvironmentDescriptor>
    private lateinit var compilerStatus: JBLabel
    private lateinit var duneStatus: JBLabel
    private lateinit var lspStatus: JBLabel
    private lateinit var formatterStatus: JBLabel
    private lateinit var duneProject: JBLabel
    private lateinit var duneProjectStatus: JBLabel
    private lateinit var installButton: JButton
    private lateinit var environmentPrefixOverride: TextFieldWithBrowseButton
    private lateinit var opamOverride: TextFieldWithBrowseButton
    private lateinit var lspOverride: TextFieldWithBrowseButton
    private lateinit var duneOverride: TextFieldWithBrowseButton
    private lateinit var formatterOverride: TextFieldWithBrowseButton

    override fun createPanel(): DialogPanel {
        val shared = projectSettings.state
        val local = workspaceSettings.state
        val executableChooser = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
            .withTitle(OCamlBundle.message("settings.file.chooser"))

        project.messageBus.connect(checkNotNull(disposable)).subscribe(
            OCamlToolchainStatusService.CHANGED_TOPIC,
            OCamlToolchainStatusChangedListener(::updateStatus),
        )

        return panel {
            group(OCamlBundle.message("settings.environment.group")) {
                row(OCamlBundle.message("settings.environment.label")) {
                    environmentCombo = comboBox(environmentModel)
                        .align(AlignX.FILL)
                        .component
                }
                row {
                    compilerStatus = label("").component
                    installButton = button(OCamlBundle.message("settings.install.tools")) {
                        installSelectedEnvironmentTools()
                    }.component
                }
            }

            group(OCamlBundle.message("settings.language.group")) {
                row {
                    checkBox(OCamlBundle.message("settings.language.enable"))
                        .bindSelected(shared::lspEnabled)
                }
                row {
                    checkBox(OCamlBundle.message("settings.format.enable"))
                        .bindSelected(shared::formatWithOcamlformat)
                }
                row(OCamlBundle.message("settings.advanced.lsp")) {
                    lspStatus = label("").component
                }
                row(OCamlBundle.message("settings.advanced.formatter")) {
                    formatterStatus = label("").component
                }
            }

            group(OCamlBundle.message("settings.dune.group")) {
                row(OCamlBundle.message("settings.dune.project")) {
                    duneProject = label("").component
                }
                row(OCamlBundle.message("settings.dune.status")) {
                    duneProjectStatus = label("").component
                }
                row(OCamlBundle.message("settings.advanced.dune")) {
                    duneStatus = label("").component
                }
                row {
                    button(OCamlBundle.message("settings.refresh")) { requestRefresh() }
                }
            }

            collapsibleGroup(OCamlBundle.message("settings.advanced.group")) {
                row(OCamlBundle.message("settings.advanced.environment.prefix")) {
                    environmentPrefixOverride = textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFolderDescriptor()
                            .withTitle(OCamlBundle.message("settings.environment.prefix.chooser")),
                        project,
                    )
                        .bindText(
                            { local.environmentPrefixOverride.orEmpty() },
                            { local.environmentPrefixOverride = it.trim() },
                        )
                        .align(AlignX.FILL)
                        .component
                }
                row(OCamlBundle.message("settings.advanced.opam")) {
                    opamOverride = textFieldWithBrowseButton(executableChooser, project)
                        .bindText(
                            { local.opamExecutableOverride.orEmpty() },
                            { local.opamExecutableOverride = it.trim() },
                        )
                        .align(AlignX.FILL)
                        .component
                }
                row(OCamlBundle.message("settings.advanced.lsp")) {
                    lspOverride = textFieldWithBrowseButton(executableChooser, project)
                        .bindText(
                            { local.lspExecutableOverride.orEmpty() },
                            { local.lspExecutableOverride = it.trim() },
                        )
                        .align(AlignX.FILL)
                        .component
                }
                row(OCamlBundle.message("settings.advanced.dune")) {
                    duneOverride = textFieldWithBrowseButton(executableChooser, project)
                        .bindText(
                            { local.duneExecutableOverride.orEmpty() },
                            { local.duneExecutableOverride = it.trim() },
                        )
                        .align(AlignX.FILL)
                        .component
                }
                row(OCamlBundle.message("settings.advanced.formatter")) {
                    formatterOverride = textFieldWithBrowseButton(executableChooser, project)
                        .bindText(
                            { local.ocamlformatExecutableOverride.orEmpty() },
                            { local.ocamlformatExecutableOverride = it.trim() },
                        )
                        .align(AlignX.FILL)
                        .component
                }
                row(OCamlBundle.message("settings.advanced.arguments")) {
                    textField()
                        .bindText(
                            { local.additionalLspArguments.orEmpty() },
                            { local.additionalLspArguments = it.trim() },
                        )
                        .align(AlignX.FILL)
                }
                row {
                    checkBox(OCamlBundle.message("settings.advanced.watch"))
                        .bindSelected(local::manageDuneWatch)
                }
            }.apply { expanded = false }
        }.also {
            updateStatus(statusService.snapshot)
            environmentCombo.addActionListener { updateSelectedEnvironmentPresentation() }
        }
    }

    override fun apply() {
        val before = workspaceSettings.state.environmentId.orEmpty()
        val beforePrefix = workspaceSettings.state.environmentPrefixOverride.orEmpty()
        val selectedEnvironment = environmentCombo.selectedItem as? OCamlEnvironmentDescriptor
        val enteredPrefix = environmentPrefixOverride.text.trim()
        val prefixChanged = enteredPrefix != beforePrefix
        val selectByPrefix = prefixChanged && enteredPrefix.isNotBlank()
        val selected = if (prefixChanged) "" else selectedEnvironment?.id.orEmpty()
        val selectedPrefix = when {
            selectByPrefix -> enteredPrefix
            prefixChanged -> ""
            selectedEnvironment?.kind == OCamlEnvironmentKind.CUSTOM -> selectedEnvironment.prefix
            selected != before -> ""
            else -> beforePrefix
        }
        workspaceSettings.state.environmentId = selected
        environmentPrefixOverride.text = selectedPrefix
        super.apply()
        projectSettings.notifyChanged()
        if (selected != before || selectedPrefix != beforePrefix) {
            statusService.selectEnvironment(selected, selectedPrefix)
        } else {
            requestRefresh()
        }
    }

    override fun reset() {
        super.reset()
        selectConfiguredEnvironment(statusService.snapshot)
        updateSelectedEnvironmentPresentation()
    }

    private fun requestRefresh() {
        showDetecting()
        statusService.refresh(
            OCamlToolchainSettingsDto(
                environmentId = (environmentCombo.selectedItem as? OCamlEnvironmentDescriptor)?.id.orEmpty(),
                environmentPrefixOverride = environmentPrefixOverride.text.trim(),
                opamExecutableOverride = opamOverride.text.trim(),
                lspExecutableOverride = lspOverride.text.trim(),
                duneExecutableOverride = duneOverride.text.trim(),
                ocamlformatExecutableOverride = formatterOverride.text.trim(),
            ),
        )
    }

    private fun installSelectedEnvironmentTools() {
        val environment = environmentCombo.selectedItem as? OCamlEnvironmentDescriptor ?: return
        val answer = Messages.showYesNoDialog(
            project,
            OCamlBundle.message("settings.install.confirm", environment.name),
            OCamlBundle.message("settings.install.confirm.title"),
            Messages.getQuestionIcon(),
        )
        if (answer == Messages.YES) {
            showDetecting()
            statusService.installRequiredTools(environment.id)
        }
    }

    private fun updateStatus(snapshot: OCamlToolchainStatusSnapshot) {
        val selectedBefore = (environmentCombo.selectedItem as? OCamlEnvironmentDescriptor)?.id
        environmentModel.replaceAll(snapshot.environments)
        val selectedId = selectedBefore ?: workspaceSettings.state.environmentId.orEmpty()
        environmentModel.selectedItem = snapshot.environments.firstOrNull { it.id == selectedId }
            ?: snapshot.selectedEnvironment
        environmentCombo.isEnabled = snapshot.environments.isNotEmpty()
        environmentCombo.toolTipText = if (snapshot.environments.isEmpty()) {
            OCamlBundle.message("settings.environment.empty")
        } else null
        updateSelectedEnvironmentPresentation()
        duneProject.text = snapshot.duneProject.root.ifBlank { "—" }
        duneProjectStatus.text = when (snapshot.duneProject.state) {
            DuneProjectSyncState.NOT_LOADED -> OCamlBundle.message("status.dune.not.loaded")
            DuneProjectSyncState.LOADING -> OCamlBundle.message("status.dune.loading")
            DuneProjectSyncState.READY -> OCamlBundle.message("status.dune.ready")
            DuneProjectSyncState.FAILED -> OCamlBundle.message("status.dune.failed", snapshot.duneProject.problem)
        }
        when (snapshot.detectionState) {
            OCamlEnvironmentDetectionState.DETECTING -> showDetecting()
            OCamlEnvironmentDetectionState.BLOCKED -> showToolStatus(OCamlBundle.message("status.blocked"))
            OCamlEnvironmentDetectionState.FAILED -> showToolStatus(
                OCamlBundle.message("status.error", snapshot.problem),
            )
            OCamlEnvironmentDetectionState.NOT_CHECKED,
            OCamlEnvironmentDetectionState.READY -> Unit
        }
    }

    private fun selectConfiguredEnvironment(snapshot: OCamlToolchainStatusSnapshot) {
        val configuredId = workspaceSettings.state.environmentId.orEmpty()
        environmentModel.selectedItem = snapshot.environments.firstOrNull { it.id == configuredId }
            ?: snapshot.selectedEnvironment
    }

    private fun updateSelectedEnvironmentPresentation() {
        val environment = environmentCombo.selectedItem as? OCamlEnvironmentDescriptor
        compilerStatus.text = environment?.compiler.statusText() ?: OCamlBundle.message("settings.environment.empty")
        lspStatus.text = environment?.languageServer.statusText() ?: OCamlBundle.message("status.not.checked")
        duneStatus.text = environment?.dune.statusText() ?: OCamlBundle.message("status.not.checked")
        formatterStatus.text = environment?.formatter.statusText() ?: OCamlBundle.message("status.not.checked")
        installButton.isEnabled = environment?.compiler?.isAvailable == true &&
            environment.canInstallTools &&
            !environment.hasAllTools
    }

    private fun showDetecting() {
        showToolStatus(OCamlBundle.message("status.detecting"))
        if (::installButton.isInitialized) installButton.isEnabled = false
    }

    private fun showToolStatus(text: String) {
        compilerStatus.text = text
        lspStatus.text = text
        duneStatus.text = text
        formatterStatus.text = text
    }

    private fun OCamlToolStatus.statusText(): String = when (availability) {
        OCamlToolAvailability.AVAILABLE -> OCamlBundle.message(
            "status.available",
            version.ifBlank { executable },
        )
        OCamlToolAvailability.MISSING -> OCamlBundle.message("status.missing")
        OCamlToolAvailability.ERROR -> OCamlBundle.message("status.error", detail)
        OCamlToolAvailability.BLOCKED -> OCamlBundle.message("status.blocked")
        OCamlToolAvailability.NOT_CHECKED -> OCamlBundle.message("status.not.checked")
    }
}
