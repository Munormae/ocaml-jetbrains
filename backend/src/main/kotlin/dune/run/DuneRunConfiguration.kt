package dev.munormae.dune.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
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
import dev.munormae.toolchain.OCamlToolchainDetectionService
import dev.munormae.OCamlBundle
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

class DuneRunConfigurationOptions : RunConfigurationOptions() {
    var target by string("")
    var duneArguments by string("")
    var programArguments by string("")
    var workingDirectory by string("")
    var customDuneExecutable by string("")
    var environmentVariables by map<String, String>()
    var passParentEnvironment by property(true)
    var managedByPlugin by property(false)
    var modelId by string("")
    var lastGeneratedName by string("")
    var lastGeneratedModelName by string("")
    var lastGeneratedTarget by string("")
    var lastGeneratedWorkingDirectory by string("")
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

    var customDuneExecutable: String
        get() = options.customDuneExecutable.orEmpty()
        set(value) {
            options.customDuneExecutable = value
        }

    var environmentVariables: MutableMap<String, String>
        get() = options.environmentVariables
        set(value) {
            options.environmentVariables = value
        }

    var passParentEnvironment: Boolean
        get() = options.passParentEnvironment
        set(value) {
            options.passParentEnvironment = value
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

    var lastGeneratedName: String
        get() = options.lastGeneratedName.orEmpty()
        set(value) {
            options.lastGeneratedName = value
        }

    var lastGeneratedModelName: String
        get() = options.lastGeneratedModelName.orEmpty()
        set(value) {
            options.lastGeneratedModelName = value
        }

    var lastGeneratedTarget: String
        get() = options.lastGeneratedTarget.orEmpty()
        set(value) {
            options.lastGeneratedTarget = value
        }

    var lastGeneratedWorkingDirectory: String
        get() = options.lastGeneratedWorkingDirectory.orEmpty()
        set(value) {
            options.lastGeneratedWorkingDirectory = value
        }

    override fun getConfigurationEditor(): SettingsEditor<DuneRunConfiguration> =
        DuneRunConfigurationEditor(this)

    override fun checkConfiguration() {
        if (!TrustedProjects.isProjectTrusted(project)) {
            throw RuntimeConfigurationError(OCamlBundle.message("run.error.untrusted"))
        }
        if (command == DuneCommand.EXEC && target.isBlank()) {
            throw RuntimeConfigurationError(OCamlBundle.message("run.error.executable"))
        }
        val detectedDune = OCamlToolchainDetectionService.getInstance(project)
            .status.selectedEnvironment?.dune?.isAvailable == true
        if (!isDuneExecutableAvailable(detectedDune, customDuneExecutable)) {
            throw RuntimeConfigurationError(OCamlBundle.message("run.error.dune.missing"))
        }

        val directory = resolveWorkingDirectory(project.basePath, workingDirectory)
        if (!Files.isDirectory(directory)) {
            throw RuntimeConfigurationError(OCamlBundle.message("run.error.directory.missing", directory))
        }
        if (findDuneRoot(directory.toString()) == null) {
            throw RuntimeConfigurationError(OCamlBundle.message("run.error.root.missing", directory))
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        checkConfiguration()
        val directory = resolveWorkingDirectory(project.basePath, workingDirectory)
        val commandLine = createDuneCommandLine(
            project = project,
            workingDirectory = directory,
            arguments = buildArguments(command, target, duneArguments, programArguments),
            executableOverride = customDuneExecutable,
        )
        commandLine.withEnvironment(environmentVariables)
        commandLine.withParentEnvironmentType(
            if (passParentEnvironment) GeneralCommandLine.ParentEnvironmentType.CONSOLE
            else GeneralCommandLine.ParentEnvironmentType.NONE,
        )

        return object : CommandLineState(environment) {
            init {
                addConsoleFilters(DuneConsoleFilter(project, directory))
            }
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

internal fun isDuneExecutableAvailable(detected: Boolean, customExecutable: String): Boolean =
    detected || customExecutable.isNotBlank()

internal fun resolveWorkingDirectory(projectBasePath: String?, configuredPath: String): Path {
    val projectDirectory = try {
        projectBasePath?.let(Path::of)?.toAbsolutePath()?.normalize()
    } catch (_: InvalidPathException) {
        null
    } ?: throw RuntimeConfigurationError(OCamlBundle.message("run.error.project.directory"))

    if (configuredPath.isBlank()) return projectDirectory

    val configuredDirectory = try {
        Path.of(configuredPath.trim())
    } catch (_: InvalidPathException) {
        throw RuntimeConfigurationError(OCamlBundle.message("run.error.directory.invalid", configuredPath))
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
