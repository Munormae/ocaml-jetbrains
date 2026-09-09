package dev.munormae.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class OCamlSettingsConfigurable(private val project: Project) : Configurable {
    private var component: JPanel? = null
    private var lspEnabled: JBCheckBox? = null
    private var useOpam: JBCheckBox? = null
    private var opamExecutable: JBTextField? = null
    private var opamSwitch: JBTextField? = null
    private var lspExecutable: JBTextField? = null
    private var additionalLspArguments: JBTextField? = null
    private var duneExecutable: JBTextField? = null
    private var ocamlformatExecutable: JBTextField? = null

    override fun getDisplayName(): String = "OCaml"

    override fun createComponent(): JComponent {
        lspEnabled = JBCheckBox("Enable ocamllsp language support")
        useOpam = JBCheckBox("Run language tools through opam exec")
        opamExecutable = JBTextField()
        opamSwitch = JBTextField()
        lspExecutable = JBTextField()
        additionalLspArguments = JBTextField()
        duneExecutable = JBTextField()
        ocamlformatExecutable = JBTextField()

        lspEnabled!!.addActionListener { updateEnabledState() }
        useOpam!!.addActionListener { updateEnabledState() }

        val form = FormBuilder.createFormBuilder()
            .addComponent(lspEnabled!!)
            .addComponent(useOpam!!)
            .addLabeledComponent("opam executable:", opamExecutable!!)
            .addLabeledComponent("opam switch:", opamSwitch!!)
            .addLabeledComponent("ocamllsp executable:", lspExecutable!!)
            .addLabeledComponent("Additional LSP arguments:", additionalLspArguments!!)
            .addSeparator(12)
            .addLabeledComponent("dune executable:", duneExecutable!!)
            .addLabeledComponent("ocamlformat executable:", ocamlformatExecutable!!)
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
        duneExecutable = null
        ocamlformatExecutable = null
    }

    private fun updateEnabledState() {
        val enabled = lspEnabled?.isSelected == true
        useOpam?.isEnabled = enabled
        opamExecutable?.isEnabled = enabled && useOpam?.isSelected == true
        opamSwitch?.isEnabled = enabled && useOpam?.isSelected == true
        lspExecutable?.isEnabled = enabled
        additionalLspArguments?.isEnabled = enabled
    }

    private fun JBTextField?.textValue(): String = this?.text?.trim().orEmpty()
}
