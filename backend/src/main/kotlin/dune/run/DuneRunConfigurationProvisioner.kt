package dev.munormae.dune.run

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.munormae.dune.DuneWatchService
import dev.munormae.dune.SList
import dev.munormae.dune.createDuneCommandLine
import dev.munormae.dune.parseSExpressions
import dev.munormae.settings.OCamlProjectSettings
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

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
        configuration.programArguments.isNotBlank()

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
        configuration.programArguments.isBlank()

private fun isPreOwnershipGeneratedConfiguration(
    project: Project,
    settings: RunnerAndConfigurationSettings,
    spec: DuneRunConfigurationSpec,
): Boolean {
    val legacyTarget = spec.legacyTarget.takeIf(String::isNotEmpty) ?: return false
    val configuration = settings.configuration as? DuneRunConfiguration ?: return false
    return !configuration.managedByPlugin &&
        configuration.modelId.isEmpty() &&
        configuration.lastGeneratedName.isEmpty() &&
        settings.name == spec.name &&
        configuration.command == spec.command &&
        configuration.duneArguments.isBlank() &&
        configuration.programArguments.isBlank() &&
        configuration.target.trim() == legacyTarget.trim() &&
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
    val normalizedRoot = duneRoot.toAbsolutePath().normalize()
    val specs = mutableListOf(
        DuneRunConfigurationSpec(
            command = DuneCommand.BUILD,
            name = "Dune Build",
            workingDirectory = normalizedRoot.toString(),
        ),
    )
    var hasTests = false

    try {
        Files.walkFileTree(normalizedRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (directory != normalizedRoot && directory.fileName.toString() in IGNORED_DIRECTORIES) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (attributes.isRegularFile && file.fileName.toString() == "dune") {
                    val forms = parseSExpressions(Files.readString(file)).filterIsInstance<SList>()
                    for (form in forms) {
                        when (form.head) {
                            "executable" -> executableTargets(form, normalizedRoot, file.parent)
                                .forEach { executable -> specs += execSpec(executable, normalizedRoot) }

                            "executables" -> executableTargets(form, normalizedRoot, file.parent, plural = true)
                                .forEach { executable -> specs += execSpec(executable, normalizedRoot) }

                            "test", "tests", "cram" -> hasTests = true
                        }
                    }
                }
                return FileVisitResult.CONTINUE
            }
        })
    } catch (exception: Exception) {
        LOG.warn("Unable to inspect Dune files below $normalizedRoot", exception)
    }

    if (hasTests) {
        specs += DuneRunConfigurationSpec(
            command = DuneCommand.TEST,
            name = "Dune Test",
            workingDirectory = normalizedRoot.toString(),
        )
    }
    return specs.distinctBy { it.command to it.target }
}

internal fun discoverDuneRunConfigurations(
    project: Project,
    duneRoot: Path,
): List<DuneRunConfigurationSpec> {
    val sourceModel = discoverDuneRunConfigurations(duneRoot)
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

private fun describeDuneWorkspace(project: Project, duneRoot: Path): List<DuneRunConfigurationSpec>? {
    val state = OCamlProjectSettings.getInstance(project).state
    val commandLine = createDuneCommandLine(
        workingDirectory = duneRoot,
        useOpam = state.useOpam,
        opamExecutable = state.opamExecutable,
        opamSwitch = state.opamSwitch,
        duneExecutable = state.duneExecutable,
        arguments = listOf("describe", "workspace", "--format=sexp", "--no-print-directory"),
    )
    val pauseLease = DuneWatchService.getInstance(project).acquirePause()
    return try {
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

private fun execSpec(executable: DuneExecutableTarget, root: Path): DuneRunConfigurationSpec =
    DuneRunConfigurationSpec(
        command = DuneCommand.EXEC,
        name = "Dune Run ${executable.displayName}",
        target = executable.localTarget,
        workingDirectory = root.toString(),
        legacyTarget = executable.publicName.orEmpty(),
    )

private data class DuneExecutableTarget(
    val localTarget: String,
    val displayName: String,
    val publicName: String?,
)

private fun executableTargets(
    form: SList,
    root: Path,
    stanzaDirectory: Path,
    plural: Boolean = false,
): List<DuneExecutableTarget> {
    val publicNames = form.field(if (plural) "public_names" else "public_name")
        ?.atomValuesAfterHead()
        .orEmpty()
    val localNames = form.field(if (plural) "names" else "name")
        ?.atomValuesAfterHead()
        .orEmpty()
    return localNames.mapIndexedNotNull { index, localName ->
        if (localName.contains("%{")) return@mapIndexedNotNull null
        val publicName = publicNames.getOrNull(index)
            ?.takeUnless { it == "-" || it.contains("%{") }
        DuneExecutableTarget(
            localTarget = localExecutableTarget(root, stanzaDirectory, localName),
            displayName = publicName ?: localName,
            publicName = publicName,
        )
    }
}

private fun localExecutableTarget(root: Path, stanzaDirectory: Path, name: String): String {
    val relativeDirectory = root.relativize(stanzaDirectory).joinToString("/")
    val prefix = if (relativeDirectory.isEmpty()) "./" else "./$relativeDirectory/"
    return "$prefix$name.exe"
}

private val IGNORED_DIRECTORIES = setOf("_build", "_opam", ".git", ".idea")
private const val DUNE_DESCRIBE_TIMEOUT_MS = 15_000
private val LOG = Logger.getInstance(DuneRunConfigurationProvisioningActivity::class.java)
