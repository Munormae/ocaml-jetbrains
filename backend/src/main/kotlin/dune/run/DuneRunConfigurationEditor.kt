package dev.munormae.dune.run

import com.intellij.openapi.options.SettingsEditor
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

internal class DuneRunConfigurationEditor(
    private val command: DuneCommand,
    projectBasePath: String,
) : SettingsEditor<DuneRunConfiguration>() {
    private val target = JBTextField()
    private val duneArguments = JBTextField()
    private val programArguments = JBTextField()
    private val workingDirectory = JBTextField()
    private val panel: JPanel

    init {
        workingDirectory.emptyText.text = projectBasePath

        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent(command.targetLabel, target)
            .addTooltip(command.targetComment)
            .addLabeledComponent("Dune arguments:", duneArguments)

        if (command == DuneCommand.EXEC) {
            builder.addLabeledComponent("Program arguments:", programArguments)
        }

        panel = builder
            .addLabeledComponent("Working directory:", workingDirectory)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun resetEditorFrom(configuration: DuneRunConfiguration) {
        target.text = configuration.target
        duneArguments.text = configuration.duneArguments
        programArguments.text = configuration.programArguments
        workingDirectory.text = configuration.workingDirectory
    }

    override fun applyEditorTo(configuration: DuneRunConfiguration) {
        configuration.target = target.text.trim()
        configuration.duneArguments = duneArguments.text.trim()
        configuration.programArguments = programArguments.text.trim()
        configuration.workingDirectory = workingDirectory.text.trim()
    }

    override fun createEditor(): JComponent = panel
}
