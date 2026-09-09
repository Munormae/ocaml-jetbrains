package dev.munormae.dune.run

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.munormae.dune.findDuneRoot
import java.nio.file.Files
import java.nio.file.Path

class DuneRunConfigurationProvisioningActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val duneRoot = findDuneRoot(project.basePath) ?: return
        val specs = discoverDuneRunConfigurations(duneRoot)
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) provisionDuneRunConfigurations(project, specs)
        }
    }
}

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
        Files.walk(normalizedRoot, MAX_DUNE_SCAN_DEPTH).use { paths ->
            paths
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString() == "dune" }
                .filter { path -> normalizedRoot.relativize(path).none { it.toString() in IGNORED_DIRECTORIES } }
                .forEach { duneFile ->
                    val forms = topLevelDuneForms(Files.readString(duneFile))
                    for (form in forms) {
                        when (duneFormHead(form)) {
                            "executable" -> executableTargets(form, normalizedRoot, duneFile.parent)
                                .forEach { executable -> specs += execSpec(executable, normalizedRoot) }

                            "executables" -> executableTargets(form, normalizedRoot, duneFile.parent, plural = true)
                                .forEach { executable -> specs += execSpec(executable, normalizedRoot) }

                            "test", "tests", "cram" -> hasTests = true
                        }
                    }
                }
        }
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
    form: String,
    root: Path,
    stanzaDirectory: Path,
    plural: Boolean = false,
): List<DuneExecutableTarget> {
    val publicNames = duneFieldValues(form, if (plural) "public_names" else "public_name")
    val localNames = duneFieldValues(form, if (plural) "names" else "name")
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

internal fun topLevelDuneForms(text: String): List<String> {
    val forms = mutableListOf<String>()
    var depth = 0
    var formStart = -1
    var inString = false
    var escaped = false
    var inComment = false

    text.forEachIndexed { index, character ->
        if (inComment) {
            if (character == '\n' || character == '\r') inComment = false
            return@forEachIndexed
        }
        if (inString) {
            if (character == '"' && !escaped) inString = false
            escaped = character == '\\' && !escaped
            if (character != '\\') escaped = false
            return@forEachIndexed
        }

        when (character) {
            ';' -> inComment = true
            '"' -> inString = true
            '(' -> {
                if (depth == 0) formStart = index
                depth++
            }
            ')' -> if (depth > 0) {
                depth--
                if (depth == 0 && formStart >= 0) {
                    forms += text.substring(formStart, index + 1)
                    formStart = -1
                }
            }
        }
    }
    return forms
}

private fun duneFormHead(form: String): String? =
    FORM_HEAD.find(form)?.groupValues?.get(1)

private fun duneFieldValues(form: String, field: String): List<String> {
    val values = Regex("""\(\s*${Regex.escape(field)}\s+([^()]*)\)""")
        .find(form)
        ?.groupValues
        ?.get(1)
        ?: return emptyList()
    return DUNE_VALUE.findAll(values)
        .map { match -> match.groups[1]?.value?.replace("\\\"", "\"") ?: match.value }
        .toList()
}

private const val MAX_DUNE_SCAN_DEPTH = 8
private val IGNORED_DIRECTORIES = setOf("_build", "_opam", ".git", ".idea")
private val FORM_HEAD = Regex("""^\(\s*([A-Za-z_]+)""")
private val DUNE_VALUE = Regex(""""((?:\\.|[^"\\])*)"|[^\s]+""")
private val LOG = Logger.getInstance(DuneRunConfigurationProvisioningActivity::class.java)
