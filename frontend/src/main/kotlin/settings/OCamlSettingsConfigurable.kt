package dev.munormae.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.JPanel
import dev.munormae.toolchain.OCamlToolchainStatusChangedListener
import dev.munormae.toolchain.OCamlToolchainStatusService
import dev.munormae.toolchain.OCamlToolchainStatusSnapshot
import dev.munormae.toolchain.OCamlToolchainSettingsDto

class OCamlSettingsConfigurable(private val project: Project) : Configurable {
    private var component: JPanel? = null
    private var lspEnabled: JBCheckBox? = null
    private var useOpam: JBCheckBox? = null
    private var opamExecutable: JBTextField? = null
    private var opamSwitch: JBTextField? = null
    private var lspExecutable: JBTextField? = null
    private var additionalLspArguments: JBTextField? = null
    private var duneWatchEnabled: JBCheckBox? = null
    private var duneExecutable: JBTextField? = null
    private var ocamlformatExecutable: JBTextField? = null
    private var opamStatus: JBLabel? = null
    private var lspStatus: JBLabel? = null
    private var duneStatus: JBLabel? = null
    private var ocamlformatStatus: JBLabel? = null
    private var statusConnection: MessageBusConnection? = null

    override fun getDisplayName(): String = "OCaml"

    override fun createComponent(): JComponent {
        lspEnabled = JBCheckBox("Enable ocamllsp language support")
        useOpam = JBCheckBox("Run language tools through opam exec")
        opamExecutable = JBTextField()
        opamSwitch = JBTextField()
        lspExecutable = JBTextField()
        additionalLspArguments = JBTextField()
        duneWatchEnabled = JBCheckBox("Run dune build --watch for richer LSP diagnostics")
        duneExecutable = JBTextField()
        ocamlformatExecutable = JBTextField()
        opamStatus = JBLabel()
        lspStatus = JBLabel()
        duneStatus = JBLabel()
        ocamlformatStatus = JBLabel()

        val autoDetect = JButton("Use OPAM/PATH").apply {
            addActionListener {
                opamExecutable?.text = ""
                lspExecutable?.text = ""
                duneExecutable?.text = ""
                ocamlformatExecutable?.text = ""
                requestToolchainRefresh()
            }
        }
        val refreshStatus = JButton("Refresh status").apply {
            addActionListener { requestToolchainRefresh() }
        }

        statusConnection?.disconnect()
        statusConnection = project.messageBus.connect().apply {
            subscribe(
                OCamlToolchainStatusService.CHANGED_TOPIC,
                OCamlToolchainStatusChangedListener(::updateStatusLabels),
            )
        }

        lspEnabled!!.addActionListener { updateEnabledState() }
        useOpam!!.addActionListener { updateEnabledState() }
        duneWatchEnabled!!.addActionListener { updateEnabledState() }

        val form = FormBuilder.createFormBuilder()
            .addComponent(lspEnabled!!)
            .addComponent(useOpam!!)
            .addLabeledComponent("opam executable:", opamExecutable!!)
            .addLabeledComponent("opam switch:", opamSwitch!!)
            .addLabeledComponent("ocamllsp executable:", lspExecutable!!)
            .addLabeledComponent("Additional LSP arguments:", additionalLspArguments!!)
            .addSeparator(12)
            .addComponent(duneWatchEnabled!!)
            .addLabeledComponent("dune executable:", duneExecutable!!)
            .addLabeledComponent("ocamlformat executable:", ocamlformatExecutable!!)
            .addSeparator(12)
            .addComponent(JBLabel("Detected toolchain"))
            .addLabeledComponent("opam:", opamStatus!!)
            .addLabeledComponent("ocamllsp:", lspStatus!!)
            .addLabeledComponent("dune:", duneStatus!!)
            .addLabeledComponent("ocamlformat:", ocamlformatStatus!!)
            .addComponent(JPanel().apply {
                add(autoDetect)
                add(refreshStatus)
            })
            .addComponent(JBLabel("Refresh tests the values above without applying or saving them."))
            .addComponent(
                JBLabel(
                    "<html>Leave executable fields empty to use <code>opam</code>, " +
                        "<code>ocamllsp</code>, <code>dune</code>, and <code>ocamlformat</code> from PATH.</html>",
                ),
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel

        component = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(JBScrollPane(form).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        }
        reset()
        return component!!
    }

    override fun isModified(): Boolean {
        val state = OCamlProjectSettings.getInstance(project).state
        return lspEnabled?.isSelected != state.lspEnabled ||
            useOpam?.isSelected != state.useOpam ||
            opamExecutable.textValue() != state.opamExecutable ||
            opamSwitch.textValue() != state.opamSwitch ||
            lspExecutable.textValue() != state.lspExecutable ||
            additionalLspArguments.textValue() != state.additionalLspArguments ||
            duneWatchEnabled?.isSelected != state.duneWatchEnabled ||
            duneExecutable.textValue() != state.duneExecutable ||
            ocamlformatExecutable.textValue() != state.ocamlformatExecutable
    }

    override fun apply() {
        val settings = OCamlProjectSettings.getInstance(project)
        settings.state.apply {
            lspEnabled = this@OCamlSettingsConfigurable.lspEnabled?.isSelected == true
            useOpam = this@OCamlSettingsConfigurable.useOpam?.isSelected == true
            opamExecutable = this@OCamlSettingsConfigurable.opamExecutable.textValue()
            opamSwitch = this@OCamlSettingsConfigurable.opamSwitch.textValue()
            lspExecutable = this@OCamlSettingsConfigurable.lspExecutable.textValue()
            additionalLspArguments = this@OCamlSettingsConfigurable.additionalLspArguments.textValue()
            duneWatchEnabled = this@OCamlSettingsConfigurable.duneWatchEnabled?.isSelected == true
            duneExecutable = this@OCamlSettingsConfigurable.duneExecutable.textValue()
            ocamlformatExecutable = this@OCamlSettingsConfigurable.ocamlformatExecutable.textValue()
        }
        settings.notifyChanged()
    }

    override fun reset() {
        val state = OCamlProjectSettings.getInstance(project).state
        lspEnabled?.isSelected = state.lspEnabled
        useOpam?.isSelected = state.useOpam
        opamExecutable?.text = state.opamExecutable
        opamSwitch?.text = state.opamSwitch
        lspExecutable?.text = state.lspExecutable
        additionalLspArguments?.text = state.additionalLspArguments
        duneWatchEnabled?.isSelected = state.duneWatchEnabled
        duneExecutable?.text = state.duneExecutable
        ocamlformatExecutable?.text = state.ocamlformatExecutable
        updateEnabledState()
    }

    override fun disposeUIResources() {
        component = null
        lspEnabled = null
        useOpam = null
        opamExecutable = null
        opamSwitch = null
        lspExecutable = null
        additionalLspArguments = null
        duneWatchEnabled = null
        duneExecutable = null
        ocamlformatExecutable = null
        opamStatus = null
        lspStatus = null
        duneStatus = null
        ocamlformatStatus = null
        statusConnection?.disconnect()
        statusConnection = null
    }

    private fun updateEnabledState() {
        val enabled = lspEnabled?.isSelected == true
        useOpam?.isEnabled = true
        opamExecutable?.isEnabled = useOpam?.isSelected == true
        opamSwitch?.isEnabled = useOpam?.isSelected == true
        lspExecutable?.isEnabled = enabled
        additionalLspArguments?.isEnabled = enabled
        duneWatchEnabled?.isEnabled = enabled
        duneExecutable?.isEnabled = true
        ocamlformatExecutable?.isEnabled = true
        updateStatusLabels(OCamlToolchainStatusService.getInstance(project).snapshot)
    }

    private fun updateStatusLabels(snapshot: OCamlToolchainStatusSnapshot) {
        opamStatus?.text = snapshot.opam
        lspStatus?.text = snapshot.ocamllsp
        duneStatus?.text = snapshot.dune
        ocamlformatStatus?.text = snapshot.ocamlformat
    }

    private fun requestToolchainRefresh() {
        showDetectingStatus()
        OCamlToolchainStatusService.getInstance(project).refresh(
            OCamlToolchainSettingsDto(
                useOpam = useOpam?.isSelected == true,
                opamExecutable = opamExecutable.textValue(),
                opamSwitch = opamSwitch.textValue(),
                lspExecutable = lspExecutable.textValue(),
                duneExecutable = duneExecutable.textValue(),
                ocamlformatExecutable = ocamlformatExecutable.textValue(),
            ),
        )
    }

    private fun showDetectingStatus() {
        opamStatus?.text = "Detecting..."
        lspStatus?.text = "Detecting..."
        duneStatus?.text = "Detecting..."
        ocamlformatStatus?.text = "Detecting..."
    }

    private fun JBTextField?.textValue(): String = this?.text?.trim().orEmpty()
}
