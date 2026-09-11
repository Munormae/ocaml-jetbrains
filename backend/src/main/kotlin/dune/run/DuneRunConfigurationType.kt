package dev.munormae.dune.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import dev.munormae.icons.OCamlIcons
import dev.munormae.OCamlBundle

class DuneRunConfigurationType : ConfigurationTypeBase(
    ID,
    OCamlBundle.message("run.type.name"),
    OCamlBundle.message("run.type.description"),
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

enum class DuneCommand(
    val cliName: String,
    val displayName: String,
    val defaultConfigurationName: String,
    val idSuffix: String,
    val targetLabel: String,
    val targetComment: String,
) {
    BUILD(
        cliName = "build",
        displayName = OCamlBundle.message("run.command.build"),
        defaultConfigurationName = OCamlBundle.message("run.command.build"),
        idSuffix = "Build",
        targetLabel = OCamlBundle.message("run.targets.label"),
        targetComment = OCamlBundle.message("run.targets.comment"),
    ),
    EXEC(
        cliName = "exec",
        displayName = OCamlBundle.message("run.command.exec"),
        defaultConfigurationName = OCamlBundle.message("run.command.exec"),
        idSuffix = "Exec",
        targetLabel = OCamlBundle.message("run.executable.label"),
        targetComment = OCamlBundle.message("run.executable.comment"),
    ),
    TEST(
        cliName = "test",
        displayName = OCamlBundle.message("run.command.test"),
        defaultConfigurationName = OCamlBundle.message("run.command.test"),
        idSuffix = "Test",
        targetLabel = OCamlBundle.message("run.tests.label"),
        targetComment = OCamlBundle.message("run.tests.comment"),
    ),
}
