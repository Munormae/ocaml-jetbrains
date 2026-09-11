package dev.munormae.dune.run

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.execution.ui.RunConfigurationFragmentedEditor
import com.intellij.execution.ui.SettingsEditorFragment
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.LabeledComponent
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBTextField
import dev.munormae.OCamlBundle
import dev.munormae.dune.model.DuneProjectModelService
import dev.munormae.dune.model.DuneProjectModelState
import java.awt.BorderLayout
import javax.swing.JComboBox

internal class DuneRunConfigurationEditor(configuration: DuneRunConfiguration) :
    RunConfigurationFragmentedEditor<DuneRunConfiguration>(configuration) {

    private val command = configuration.command

    override fun createRunFragments(): List<SettingsEditorFragment<DuneRunConfiguration, *>> = buildList {
        add(targetFragment())
        if (command == DuneCommand.EXEC) add(programArgumentsFragment())
        add(duneArgumentsFragment())
        add(workingDirectoryFragment())
        add(customDuneExecutableFragment())
        add(environmentFragment())
    }

    private fun targetFragment(): SettingsEditorFragment<DuneRunConfiguration, *> {
        val model = (DuneProjectModelService.getInstance(project).state as? DuneProjectModelState.Ready)?.model
        val targets = when (command) {
            DuneCommand.EXEC -> model?.executables.orEmpty().map { target ->
                DuneTargetChoice(
                    target.publicName.ifBlank { target.name },
                    target.target,
                )
            }
            DuneCommand.TEST -> buildList {
                add(DuneTargetChoice(OCamlBundle.message("run.tests.all"), ""))
                model?.tests.orEmpty()
                    .map { target ->
                        model?.root?.relativize(target.directory)
                            ?.toString()
                            ?.replace('\\', '/')
                            ?.ifBlank { "." }
                            .orEmpty()
                    }
                    .filter(String::isNotBlank)
                    .distinct()
                    .forEach { directory ->
                        add(
                            DuneTargetChoice(
                                OCamlBundle.message("run.tests.directory", directory),
                                directory,
                            ),
                        )
                    }
            }
            DuneCommand.BUILD -> emptyList()
        }
        val component = if (command == DuneCommand.EXEC || command == DuneCommand.TEST) {
            JComboBox(CollectionComboBoxModel(targets)).apply { isEditable = targets.isEmpty() }
        } else {
            JBTextField()
        }
        val labeled = LabeledComponent.create(component, command.targetLabel, BorderLayout.WEST)
        return SettingsEditorFragment<DuneRunConfiguration, LabeledComponent<*>>(
            "dune.target",
            command.targetLabel.removeSuffix(":"),
            null,
            labeled,
            { settings, field ->
                when (val editor = field.component) {
                    is JComboBox<*> -> editor.selectedItem = targets
                        .firstOrNull { it.target == settings.target }
                        ?: settings.target
                    is JBTextField -> editor.text = settings.target
                }
            },
            { settings, field ->
                settings.target = when (val editor = field.component) {
                    is JComboBox<*> -> when (
                        val selected = if (editor.isEditable) editor.editor.item else editor.selectedItem
                    ) {
                        is DuneTargetChoice -> selected.target
                        else -> selected?.toString().orEmpty().trim()
                    }
                    is JBTextField -> editor.text.trim()
                    else -> ""
                }
            },
            { true },
        ).apply {
            setRemovable(false)
            setHint(command.targetComment)
        }
    }

    private fun programArgumentsFragment(): SettingsEditorFragment<DuneRunConfiguration, RawCommandLineEditor> {
        val editor = RawCommandLineEditor()
        return SettingsEditorFragment(
            "dune.program.arguments",
            OCamlBundle.message("run.program.arguments"),
            null,
            editor,
            100,
            { settings, component -> component.text = settings.programArguments },
            { settings, component -> settings.programArguments = component.text.trim() },
            { true },
        ).apply {
            setRemovable(false)
            setEditorGetter { it.editorField }
        }
    }

    private fun duneArgumentsFragment(): SettingsEditorFragment<DuneRunConfiguration, RawCommandLineEditor> {
        val editor = RawCommandLineEditor()
        return SettingsEditorFragment(
            "dune.arguments",
            OCamlBundle.message("run.dune.arguments"),
            OCamlBundle.message("run.modify.options"),
            editor,
            110,
            { settings, component -> component.text = settings.duneArguments },
            { settings, component -> settings.duneArguments = component.text.trim() },
            { it.duneArguments.isNotBlank() },
        ).apply {
            setCanBeHidden(true)
            setEditorGetter { it.editorField }
        }
    }

    private fun workingDirectoryFragment(): SettingsEditorFragment<DuneRunConfiguration, LabeledComponent<TextFieldWithBrowseButton>> {
        val editor = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(
                project,
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    .withTitle(OCamlBundle.message("run.working.directory.select")),
            )
        }
        val labeled = LabeledComponent.create(
            editor,
            OCamlBundle.message("run.working.directory.label"),
            BorderLayout.WEST,
        )
        return SettingsEditorFragment(
            "dune.working.directory",
            OCamlBundle.message("run.working.directory"),
            null,
            labeled,
            { settings, component -> component.component.text = settings.workingDirectory },
            { settings, component -> settings.workingDirectory = component.component.text.trim() },
            { true },
        ).apply { setRemovable(false) }
    }

    private fun customDuneExecutableFragment(): SettingsEditorFragment<DuneRunConfiguration, LabeledComponent<TextFieldWithBrowseButton>> {
        val editor = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(
                project,
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                    .withTitle(OCamlBundle.message("run.dune.executable.select")),
            )
        }
        val labeled = LabeledComponent.create(
            editor,
            OCamlBundle.message("run.dune.executable.label"),
            BorderLayout.WEST,
        )
        return SettingsEditorFragment(
            "dune.executable",
            OCamlBundle.message("run.dune.executable"),
            OCamlBundle.message("run.modify.options"),
            labeled,
            { settings, component -> component.component.text = settings.customDuneExecutable },
            { settings, component -> settings.customDuneExecutable = component.component.text.trim() },
            { it.customDuneExecutable.isNotBlank() },
        ).apply { setCanBeHidden(true) }
    }

    private fun environmentFragment(): SettingsEditorFragment<DuneRunConfiguration, EnvironmentVariablesComponent> {
        val editor = EnvironmentVariablesComponent(project)
        return SettingsEditorFragment(
            "dune.environment",
            OCamlBundle.message("run.environment"),
            OCamlBundle.message("run.modify.options"),
            editor,
            { settings, component ->
                component.envs = settings.environmentVariables
                component.isPassParentEnvs = settings.passParentEnvironment
            },
            { settings, component ->
                settings.environmentVariables = component.envs.toMutableMap()
                settings.passParentEnvironment = component.isPassParentEnvs
            },
            { it.environmentVariables.isNotEmpty() || !it.passParentEnvironment },
        ).apply { setCanBeHidden(true) }
    }
}

internal data class DuneTargetChoice(
    val name: String,
    val target: String,
) {
    override fun toString(): String = "$name — $target"
}
