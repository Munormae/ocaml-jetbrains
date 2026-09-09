package dev.munormae.dune.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import dev.munormae.icons.OCamlIcons

class DuneRunConfigurationType : ConfigurationTypeBase(
    ID,
    "Dune",
    "Build, run, and test OCaml projects with Dune",
    OCamlIcons.Dune,
), DumbAware {
    init {
        DuneCommand.entries.forEach { addFactory(DuneConfigurationFactory(this, it)) }
    }

    companion object {
        const val ID = "OCamlDuneRunConfiguration"
    }
}

internal class DuneConfigurationFactory(
    type: DuneRunConfigurationType,
    val command: DuneCommand,
) : ConfigurationFactory(type) {
    override fun getId(): String = "OCamlDune${command.idSuffix}"

    override fun getName(): String = command.displayName

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        DuneRunConfiguration(project, this, command.defaultConfigurationName)

    override fun isEditableInDumbMode(): Boolean = true

    override fun getOptionsClass() = DuneRunConfigurationOptions::class.java
}

internal enum class DuneCommand(
    val cliName: String,
    val displayName: String,
    val defaultConfigurationName: String,
    val idSuffix: String,
    val targetLabel: String,
    val targetComment: String,
) {
    BUILD(
        cliName = "build",
        displayName = "Dune Build",
        defaultConfigurationName = "Dune Build",
        idSuffix = "Build",
        targetLabel = "Targets:",
        targetComment = "Optional. Space-separated Dune targets; empty builds the default alias.",
    ),
    EXEC(
        cliName = "exec",
        displayName = "Dune Exec",
        defaultConfigurationName = "Dune Exec",
        idSuffix = "Exec",
        targetLabel = "Executable:",
        targetComment = "Required. For example: ./bin/main.exe or my-package.",
    ),
    TEST(
        cliName = "test",
        displayName = "Dune Test",
        defaultConfigurationName = "Dune Test",
        idSuffix = "Test",
        targetLabel = "Tests:",
        targetComment = "Optional. Space-separated test targets; empty uses Dune's default test scope.",
    ),
}
