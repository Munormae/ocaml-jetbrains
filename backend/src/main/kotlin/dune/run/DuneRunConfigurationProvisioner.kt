package dev.munormae.dune.run

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.munormae.OCamlBundle
import dev.munormae.dune.DuneWatchService
import dev.munormae.dune.createDuneCommandLine
import dev.munormae.dune.findDuneRoot
import dev.munormae.dune.model.discoverDuneSourceModel
import java.nio.file.Path

data class DuneRunConfigurationSpec(
    val command: DuneCommand,
    val name: String,
    val target: String = "",
    val workingDirectory: String = "",
    val legacyTarget: String = "",
)

fun provisionDuneRunConfigurations(
    project: Project,
    specs: List<DuneRunConfigurationSpec>,
) {
    if (project.isDisposed) return

    val configurationType = ConfigurationTypeUtil.findConfigurationType(DuneRunConfigurationType::class.java)
    val runManager = RunManager.getInstance(project)
    val factories = configurationType.configurationFactories
        .filterIsInstance<DuneConfigurationFactory>()
        .associateBy { it.command }
    val desired = specs
        .map { spec -> DesiredDuneConfiguration(spec, modelId(project, spec)) }
        .distinctBy(DesiredDuneConfiguration::modelId)
    val desiredByModelId = desired.associateBy(DesiredDuneConfiguration::modelId)
    val desiredModelIds = desired.mapTo(mutableSetOf(), DesiredDuneConfiguration::modelId)
    normalizeManagedOwnership(project, runManager.allSettings, desiredByModelId)

    for ((spec, desiredModelId) in desired) {
        val managedSettings = runManager.allSettings.firstOrNull { settings ->
            val configuration = settings.configuration as? DuneRunConfiguration ?: return@firstOrNull false
            configuration.managedByPlugin && configuration.modelId == desiredModelId
        }
        if (managedSettings != null) {
            val configuration = managedSettings.configuration as DuneRunConfiguration
            val generatedName = if (configuration.lastGeneratedModelName == spec.name) {
                managedSettings.name
            } else {
                runManager.suggestUniqueName(spec.name, configurationType)
            }
            updateManagedConfiguration(managedSettings, spec, desiredModelId, generatedName)
            continue
        }

        val relocatedSettings = findRelocatedManagedConfiguration(
            runManager.allSettings,
            desired,
            desiredModelIds,
            spec,
        )
        if (relocatedSettings != null) {
            updateManagedConfiguration(relocatedSettings, spec, desiredModelId, relocatedSettings.name)
            continue
        }

        val legacySettings = spec.legacyTarget.takeIf { it.isNotEmpty() }?.let { legacyTarget ->
            runManager.allSettings
                .firstOrNull { settings ->
                    val configuration = settings.configuration as? DuneRunConfiguration ?: return@firstOrNull false
                    configuration.managedByPlugin &&
                        configuration.command == spec.command &&
                        configuration.target == legacyTarget &&
                        canonicalWorkingDirectory(project, configuration.workingDirectory) ==
                        canonicalWorkingDirectory(project, spec.workingDirectory)
                }
        }
        if (legacySettings != null) {
            updateManagedConfiguration(legacySettings, spec, desiredModelId, legacySettings.name)
            continue
        }

        val preOwnershipSettings = runManager.allSettings.firstOrNull { settings ->
            isPreOwnershipGeneratedConfiguration(project, settings, spec)
        }
        if (preOwnershipSettings != null) {
            updateManagedConfiguration(preOwnershipSettings, spec, desiredModelId, preOwnershipSettings.name)
            continue
        }

        val equivalentUserConfigurationExists = runManager.allSettings.any { settings ->
            val configuration = settings.configuration as? DuneRunConfiguration ?: return@any false
            !configuration.managedByPlugin &&
                configuration.command == spec.command &&
                configuration.target.trim() == spec.target.trim() &&
                canonicalWorkingDirectory(project, configuration.workingDirectory) ==
                canonicalWorkingDirectory(project, spec.workingDirectory)
        }
        if (equivalentUserConfigurationExists) continue

        val factory = factories[spec.command] ?: continue
        val uniqueName = runManager.suggestUniqueName(spec.name, configurationType)
        val settings = runManager.createConfiguration(uniqueName, factory)
        updateManagedConfiguration(settings, spec, desiredModelId, uniqueName)
        runManager.addConfiguration(settings)
    }

    runManager.allSettings
        .filter { settings ->
            val configuration = settings.configuration as? DuneRunConfiguration ?: return@filter false
            configuration.managedByPlugin && configuration.modelId !in desiredModelIds
        }
        .forEach(runManager::removeConfiguration)

    if (runManager.selectedConfiguration == null) {
        selectPreferredDuneConfiguration(runManager, configurationType)
    }
}

private data class DesiredDuneConfiguration(
    val spec: DuneRunConfigurationSpec,
    val modelId: String,
)

private fun findRelocatedManagedConfiguration(
    settings: List<RunnerAndConfigurationSettings>,
    desired: List<DesiredDuneConfiguration>,
    desiredModelIds: Set<String>,
    spec: DuneRunConfigurationSpec,
): RunnerAndConfigurationSettings? {
    if (desired.count { it.spec.command == spec.command && it.spec.name == spec.name } != 1) return null

    return settings.filter { entry ->
        val configuration = entry.configuration as? DuneRunConfiguration ?: return@filter false
        configuration.managedByPlugin &&
            configuration.modelId !in desiredModelIds &&
            configuration.command == spec.command &&
            configuration.lastGeneratedModelName == spec.name
    }.singleOrNull()
}

private fun normalizeManagedOwnership(
    project: Project,
    settings: List<RunnerAndConfigurationSettings>,
    desiredByModelId: Map<String, DesiredDuneConfiguration>,
) {
    for (entry in settings) {
        val configuration = entry.configuration as? DuneRunConfiguration ?: continue
        if (!configuration.managedByPlugin) continue

        if (configuration.lastGeneratedName.isNotEmpty()) {
            if (isCustomized(entry, configuration)) detach(configuration)
            continue
        }

        val desired = desiredByModelId[configuration.modelId]
        if (desired != null && matchesUntouchedGeneratedConfiguration(project, entry, configuration, desired.spec)) {
            recordGeneratedBaseline(entry, configuration, desired.spec)
        } else {
            detach(configuration)
        }
    }
}

private fun isCustomized(
    settings: RunnerAndConfigurationSettings,
    configuration: DuneRunConfiguration,
): Boolean =
    settings.name != configuration.lastGeneratedName ||
        configuration.target != configuration.lastGeneratedTarget ||
        configuration.workingDirectory != configuration.lastGeneratedWorkingDirectory ||
        configuration.duneArguments.isNotBlank() ||
        configuration.programArguments.isNotBlank() ||
        configuration.customDuneExecutable.isNotBlank() ||
        configuration.environmentVariables.isNotEmpty() ||
        !configuration.passParentEnvironment

private fun matchesUntouchedGeneratedConfiguration(
    project: Project,
    settings: RunnerAndConfigurationSettings,
    configuration: DuneRunConfiguration,
    spec: DuneRunConfigurationSpec,
): Boolean =
    settings.name == spec.name &&
        configuration.command == spec.command &&
        configuration.target.trim() == spec.target.trim() &&
        canonicalWorkingDirectory(project, configuration.workingDirectory) ==
        canonicalWorkingDirectory(project, spec.workingDirectory) &&
        configuration.duneArguments.isBlank() &&
        configuration.programArguments.isBlank() &&
        configuration.customDuneExecutable.isBlank() &&
        configuration.environmentVariables.isEmpty() &&
        configuration.passParentEnvironment

private fun isPreOwnershipGeneratedConfiguration(
    project: Project,
    settings: RunnerAndConfigurationSettings,
    spec: DuneRunConfigurationSpec,
): Boolean {
    val configuration = settings.configuration as? DuneRunConfiguration ?: return false
    val configuredTarget = configuration.target.trim()
    val matchesGeneratedTarget = configuredTarget == spec.target.trim() ||
        spec.legacyTarget.isNotEmpty() && configuredTarget == spec.legacyTarget.trim()
    return !configuration.managedByPlugin &&
        configuration.modelId.isEmpty() &&
        configuration.lastGeneratedName.isEmpty() &&
        settings.name == spec.name &&
        configuration.command == spec.command &&
        configuration.duneArguments.isBlank() &&
        configuration.programArguments.isBlank() &&
        configuration.customDuneExecutable.isBlank() &&
        configuration.environmentVariables.isEmpty() &&
        configuration.passParentEnvironment &&
        matchesGeneratedTarget &&
        canonicalWorkingDirectory(project, configuration.workingDirectory) ==
        canonicalWorkingDirectory(project, spec.workingDirectory)
}

private fun detach(configuration: DuneRunConfiguration) {
    configuration.managedByPlugin = false
    configuration.modelId = ""
    configuration.lastGeneratedName = ""
    configuration.lastGeneratedModelName = ""
    configuration.lastGeneratedTarget = ""
    configuration.lastGeneratedWorkingDirectory = ""
}

private fun updateManagedConfiguration(
    settings: RunnerAndConfigurationSettings,
    spec: DuneRunConfigurationSpec,
    modelId: String,
    generatedName: String,
) {
    val configuration = settings.configuration as DuneRunConfiguration
    settings.name = generatedName
    configuration.target = spec.target
    configuration.workingDirectory = spec.workingDirectory
    configuration.managedByPlugin = true
    configuration.modelId = modelId
    recordGeneratedBaseline(settings, configuration, spec)
}

private fun recordGeneratedBaseline(
    settings: RunnerAndConfigurationSettings,
    configuration: DuneRunConfiguration,
    spec: DuneRunConfigurationSpec,
) {
    configuration.lastGeneratedName = settings.name
    configuration.lastGeneratedModelName = spec.name
    configuration.lastGeneratedTarget = configuration.target
    configuration.lastGeneratedWorkingDirectory = configuration.workingDirectory
}

internal fun modelId(project: Project, spec: DuneRunConfigurationSpec): String = buildString {
    append("dune-model-v1|")
    append(canonicalWorkingDirectory(project, spec.workingDirectory))
    append('|')
    append(spec.command.name)
    append('|')
    append(spec.target.trim())
}

private fun canonicalWorkingDirectory(project: Project, configuredPath: String): String {
    val projectRoot = project.basePath
        ?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
    val configured = configuredPath.trim()
    val resolved = when {
        configured.isEmpty() -> projectRoot
        else -> runCatching { Path.of(configured) }.getOrNull()?.let { path ->
            if (path.isAbsolute) path.normalize() else projectRoot?.resolve(path)?.normalize() ?: path.normalize()
        }
    }
    return resolved?.toString()?.replace('\\', '/') ?: configured.replace('\\', '/')
}

private fun selectPreferredDuneConfiguration(
    runManager: RunManager,
    configurationType: DuneRunConfigurationType,
) {
    val configurations = runManager.allSettings.filter { it.type == configurationType }
    val preferred = configurations.firstOrNull { it.duneCommand == DuneCommand.EXEC }
        ?: configurations.firstOrNull { it.duneCommand == DuneCommand.BUILD }
    if (preferred != null) runManager.selectedConfiguration = preferred
}

private val RunnerAndConfigurationSettings.duneCommand: DuneCommand?
    get() = (configuration as? DuneRunConfiguration)?.command

internal fun discoverDuneRunConfigurations(duneRoot: Path): List<DuneRunConfigurationSpec> {
    return discoverDuneSourceModel(duneRoot).runConfigurations
}

internal fun discoverDuneRunConfigurations(
    project: Project,
    duneRoot: Path,
): List<DuneRunConfigurationSpec> {
    val sourceModel = discoverDuneSourceModel(duneRoot).runConfigurations
    val describedExecutables = describeDuneWorkspace(project, duneRoot) ?: return sourceModel
    val sourceExecutables = sourceModel
        .filter { it.command == DuneCommand.EXEC }
        .associateBy(DuneRunConfigurationSpec::target)
    val executables = (describedExecutables.map { sourceExecutables[it.target] ?: it } + sourceExecutables.values)
        .distinctBy(DuneRunConfigurationSpec::target)

    return buildList {
        addAll(sourceModel.filter { it.command == DuneCommand.BUILD })
        addAll(executables)
        addAll(sourceModel.filter { it.command == DuneCommand.TEST })
    }
}

internal fun describeDuneWorkspace(project: Project, duneRoot: Path): List<DuneRunConfigurationSpec>? {
    val pauseRoot = findDuneRoot(duneRoot.toString()) ?: duneRoot
    val pauseLease = DuneWatchService.getInstance(project).acquirePause(pauseRoot)
    return try {
        val commandLine = createDuneCommandLine(
            project = project,
            workingDirectory = duneRoot,
            arguments = listOf("describe", "workspace", "--format=sexp", "--no-print-directory"),
        )
        val output = CapturingProcessHandler(commandLine).runProcess(DUNE_DESCRIBE_TIMEOUT_MS)
        if (output.isTimeout || output.exitCode != 0) {
            LOG.debug("Dune describe unavailable; using source model: ${output.stderr}")
            null
        } else {
            parseDuneDescribeRunConfigurations(output.stdout, duneRoot)
        }
    } catch (exception: Exception) {
        LOG.debug("Dune describe unavailable; using source model", exception)
        null
    } finally {
        pauseLease.close()
    }
}

private const val DUNE_DESCRIBE_TIMEOUT_MS = 15_000
private val LOG = Logger.getInstance(DuneRunConfigurationProvisioningActivity::class.java)
