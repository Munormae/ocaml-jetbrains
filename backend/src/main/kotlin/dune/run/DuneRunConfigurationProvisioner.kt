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
    if (specs.isEmpty() || project.isDisposed) return

    val configurationType = ConfigurationTypeUtil.findConfigurationType(DuneRunConfigurationType::class.java)
    val runManager = RunManager.getInstance(project)
    val factories = configurationType.configurationFactories
        .filterIsInstance<DuneConfigurationFactory>()
        .associateBy { it.command }

    for (spec in specs.distinctBy { it.command to it.target }) {
        val alreadyExists = runManager.allSettings.any { settings ->
            val configuration = settings.configuration as? DuneRunConfiguration
            configuration?.command == spec.command &&
                configuration.target == spec.target
        }
        if (alreadyExists) continue

        val legacyConfiguration = spec.legacyTarget.takeIf { it.isNotEmpty() }?.let { legacyTarget ->
            runManager.allSettings
                .mapNotNull { it.configuration as? DuneRunConfiguration }
                .firstOrNull { configuration ->
                    configuration.command == spec.command && configuration.target == legacyTarget
                }
        }
        if (legacyConfiguration != null) {
            legacyConfiguration.target = spec.target
            if (legacyConfiguration.workingDirectory.isBlank()) {
                legacyConfiguration.workingDirectory = spec.workingDirectory
            }
            continue
        }

        val factory = factories[spec.command] ?: continue
        val uniqueName = runManager.suggestUniqueName(spec.name, configurationType)
        val settings = runManager.createConfiguration(uniqueName, factory)
        val configuration = settings.configuration as DuneRunConfiguration
        configuration.target = spec.target
        configuration.workingDirectory = spec.workingDirectory
        runManager.addConfiguration(settings)
    }

    if (runManager.selectedConfiguration == null) {
        selectPreferredDuneConfiguration(runManager, configurationType)
    }
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
    val watchService = DuneWatchService.getInstance(project)
    val watchWasRunning = watchService.pauseForRunConfiguration()
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
        watchService.resumeAfterRunConfiguration(watchWasRunning)
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
