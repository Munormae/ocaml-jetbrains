package dev.munormae.dune.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import dev.munormae.dune.DuneWatchService
import dev.munormae.dune.createDuneCommandLine
import dev.munormae.dune.findDuneRoot
import dev.munormae.settings.OCamlProjectSettings
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

class DuneRunConfigurationOptions : RunConfigurationOptions() {
    var target by string("")
    var duneArguments by string("")
    var programArguments by string("")
    var workingDirectory by string("")
    var managedByPlugin by property(false)
    var modelId by string("")
}

class DuneRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : RunConfigurationBase<DuneRunConfigurationOptions>(project, factory, name) {
    val command: DuneCommand
        get() = (factory as DuneConfigurationFactory).command

    override fun getOptions(): DuneRunConfigurationOptions =
        super.getOptions() as DuneRunConfigurationOptions

    var target: String
        get() = options.target.orEmpty()
        set(value) {
            options.target = value
        }

    var duneArguments: String
        get() = options.duneArguments.orEmpty()
        set(value) {
            options.duneArguments = value
        }

    var programArguments: String
        get() = options.programArguments.orEmpty()
        set(value) {
            options.programArguments = value
        }

    var workingDirectory: String
        get() = options.workingDirectory.orEmpty()
        set(value) {
            options.workingDirectory = value
        }

    var managedByPlugin: Boolean
        get() = options.managedByPlugin
        set(value) {
            options.managedByPlugin = value
        }

    var modelId: String
        get() = options.modelId.orEmpty()
        set(value) {
            options.modelId = value
        }

    override fun getConfigurationEditor(): SettingsEditor<DuneRunConfiguration> =
        DuneRunConfigurationEditor(command, project.basePath.orEmpty())

    override fun checkConfiguration() {
        if (!TrustedProjects.isProjectTrusted(project)) {
            throw RuntimeConfigurationError("Dune commands can run only in trusted projects")
        }
        if (command == DuneCommand.EXEC && target.isBlank()) {
            throw RuntimeConfigurationError("Specify the executable to run")
        }

        val directory = resolveWorkingDirectory(project.basePath, workingDirectory)
        if (!Files.isDirectory(directory)) {
            throw RuntimeConfigurationError("Working directory does not exist: $directory")
        }
        if (findDuneRoot(directory.toString()) == null) {
            throw RuntimeConfigurationError("No dune-project or dune-workspace found at or above $directory")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        checkConfiguration()
        val directory = resolveWorkingDirectory(project.basePath, workingDirectory)
        val settings = OCamlProjectSettings.getInstance(project).state
        val commandLine = createDuneCommandLine(
            workingDirectory = directory,
            useOpam = settings.useOpam,
            opamExecutable = settings.opamExecutable,
            opamSwitch = settings.opamSwitch,
            duneExecutable = settings.duneExecutable,
            arguments = buildArguments(command, target, duneArguments, programArguments),
        )

        return object : CommandLineState(environment) {
            @Throws(ExecutionException::class)
            override fun startProcess(): ProcessHandler {
                val pauseLease = DuneWatchService.getInstance(project).acquirePause()
                try {
                    return ColoredProcessHandler(commandLine).also { handler ->
                        handler.addProcessListener(object : ProcessListener {
                            override fun processTerminated(event: ProcessEvent) {
                                pauseLease.close()
                            }
                        })
                    }
                } catch (exception: Throwable) {
                    pauseLease.close()
                    throw exception
                }
            }
        }
    }
}

internal fun resolveWorkingDirectory(projectBasePath: String?, configuredPath: String): Path {
    val projectDirectory = try {
        projectBasePath?.let(Path::of)?.toAbsolutePath()?.normalize()
    } catch (_: InvalidPathException) {
        null
    } ?: throw RuntimeConfigurationError("The project has no valid base directory")

    if (configuredPath.isBlank()) return projectDirectory

    val configuredDirectory = try {
        Path.of(configuredPath.trim())
    } catch (_: InvalidPathException) {
        throw RuntimeConfigurationError("Invalid working directory: $configuredPath")
    }
    return if (configuredDirectory.isAbsolute) {
        configuredDirectory.normalize()
    } else {
        projectDirectory.resolve(configuredDirectory).normalize()
    }
}

internal fun buildArguments(
    command: DuneCommand,
    target: String,
    duneArguments: String,
    programArguments: String,
): List<String> = buildList {
    add(command.cliName)
    addAll(ParametersListUtil.parse(duneArguments))
    when (command) {
        DuneCommand.BUILD, DuneCommand.TEST -> addAll(ParametersListUtil.parse(target))
        DuneCommand.EXEC -> {
            add(target.trim())
            val parsedProgramArguments = ParametersListUtil.parse(programArguments)
            if (parsedProgramArguments.isNotEmpty()) {
                add("--")
                addAll(parsedProgramArguments)
            }
        }
    }
}
