package dev.munormae.toolchain

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.munormae.settings.OCamlProjectSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

class OCamlToolchainDetectionService(private val project: Project) : Disposable {
    private val requestedGeneration = AtomicLong()

    fun refresh() {
        val generation = requestedGeneration.incrementAndGet()
        if (ApplicationManager.getApplication().isUnitTestMode) return
        val settings = ToolchainSettingsSnapshot.from(OCamlProjectSettings.getInstance(project).state)
        if (!TrustedProjects.isProjectTrusted(project)) {
            applySnapshot(generation, ToolchainDetectionSnapshot.blocked())
            return
        }

        AppExecutorUtil.getAppExecutorService().execute {
            val snapshot = detectToolchain(settings, project.basePath)
            applySnapshot(generation, snapshot)
        }
    }

    private fun applySnapshot(generation: Long, snapshot: ToolchainDetectionSnapshot) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || requestedGeneration.get() != generation) return@invokeLater
            OCamlProjectSettings.getInstance(project).state.apply {
                opamStatus = snapshot.opam.status
                lspStatus = snapshot.ocamllsp.status
                duneStatus = snapshot.dune.status
                ocamlformatStatus = snapshot.ocamlformat.status
            }
        }
    }

    override fun dispose() {
        requestedGeneration.incrementAndGet()
    }

    companion object {
        fun getInstance(project: Project): OCamlToolchainDetectionService = project.service()
    }
}

internal data class ToolchainSettingsSnapshot(
    val useOpam: Boolean,
    val opamExecutable: String,
    val opamSwitch: String,
    val lspExecutable: String,
    val duneExecutable: String,
    val ocamlformatExecutable: String,
) {
    companion object {
        fun from(state: OCamlProjectSettings.SettingsState): ToolchainSettingsSnapshot =
            ToolchainSettingsSnapshot(
                useOpam = state.useOpam,
                opamExecutable = state.opamExecutable.orEmpty(),
                opamSwitch = state.opamSwitch.orEmpty(),
                lspExecutable = state.lspExecutable.orEmpty(),
                duneExecutable = state.duneExecutable.orEmpty(),
                ocamlformatExecutable = state.ocamlformatExecutable.orEmpty(),
            )
    }
}

internal data class ToolProbeResult(
    val status: String,
    val version: String? = null,
)

internal data class ToolchainDetectionSnapshot(
    val opam: ToolProbeResult,
    val ocamllsp: ToolProbeResult,
    val dune: ToolProbeResult,
    val ocamlformat: ToolProbeResult,
) {
    companion object {
        fun blocked(): ToolchainDetectionSnapshot {
            val blocked = ToolProbeResult("Blocked until the project is trusted")
            return ToolchainDetectionSnapshot(blocked, blocked, blocked, blocked)
        }
    }
}

internal fun detectToolchain(
    settings: ToolchainSettingsSnapshot,
    projectBasePath: String?,
): ToolchainDetectionSnapshot {
    val workingDirectory = projectBasePath
        ?.let { runCatching { Path.of(it) }.getOrNull() }
        ?.takeIf(Files::isDirectory)
    val opamExecutable = settings.opamExecutable.ifBlank { "opam" }
    val opam = probeTool(
        commandLine = GeneralCommandLine(opamExecutable).withParameters("--version"),
        source = executableSource(opamExecutable, configured = settings.opamExecutable.isNotBlank()),
        workingDirectory = workingDirectory,
    )

    fun languageTool(executable: String, defaultName: String): ToolProbeResult {
        val resolvedExecutable = executable.ifBlank { defaultName }
        val commandLine = if (settings.useOpam) {
            GeneralCommandLine(opamExecutable).apply {
                addParameter("exec")
                if (settings.opamSwitch.isNotBlank()) {
                    addParameters("--switch", settings.opamSwitch.trim())
                }
                addParameters("--", resolvedExecutable, "--version")
            }
        } else {
            GeneralCommandLine(resolvedExecutable).withParameters("--version")
        }
        val source = if (settings.useOpam) {
            "opam exec${settings.opamSwitch.takeIf(String::isNotBlank)?.let { " (${it.trim()})" }.orEmpty()}"
        } else {
            executableSource(resolvedExecutable, configured = executable.isNotBlank())
        }
        return probeTool(commandLine, source, workingDirectory)
    }

    return ToolchainDetectionSnapshot(
        opam = opam,
        ocamllsp = languageTool(settings.lspExecutable, "ocamllsp"),
        dune = languageTool(settings.duneExecutable, "dune"),
        ocamlformat = languageTool(settings.ocamlformatExecutable, "ocamlformat"),
    )
}

internal fun detectDefaultDuneLanguageVersion(): String? {
    val commands = listOf(
        GeneralCommandLine("opam").withParameters("exec", "--", "dune", "--version"),
        GeneralCommandLine("dune").withParameters("--version"),
    )
    return commands.firstNotNullOfOrNull { command ->
        probeTool(command, "auto", null).version?.let(::parseDuneLanguageVersion)
    }
}

internal fun parseDuneLanguageVersion(versionOutput: String): String? =
    DUNE_VERSION.find(versionOutput)?.let { match ->
        "${match.groupValues[1]}.${match.groupValues[2]}"
    }

private fun probeTool(
    commandLine: GeneralCommandLine,
    source: String,
    workingDirectory: Path?,
): ToolProbeResult {
    if (workingDirectory != null) commandLine.withWorkingDirectory(workingDirectory)
    return try {
        val output = CapturingProcessHandler(commandLine).runProcess(TOOL_PROBE_TIMEOUT_MS)
        if (output.isTimeout) return ToolProbeResult("Timed out ($source)")
        val text = sequenceOf(output.stdout, output.stderr)
            .flatMap { it.lineSequence() }
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
            .orEmpty()
        if (output.exitCode == 0) {
            val version = text.ifEmpty { "version unavailable" }
            ToolProbeResult("OK: $version ($source)", version)
        } else {
            ToolProbeResult("Unavailable: ${text.ifEmpty { "exit code ${output.exitCode}" }} ($source)")
        }
    } catch (exception: Exception) {
        ToolProbeResult("Unavailable: ${exception.message ?: exception.javaClass.simpleName} ($source)")
    }
}

private fun executableSource(executable: String, configured: Boolean): String {
    if (configured) return executable
    return findExecutableOnPath(executable)?.toString() ?: "PATH"
}

private fun findExecutableOnPath(executable: String): Path? {
    val candidates = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        val extensions = System.getenv("PATHEXT")
            ?.split(';')
            ?.filter(String::isNotBlank)
            .orEmpty()
            .ifEmpty { listOf(".COM", ".EXE", ".BAT", ".CMD") }
        if (extensions.any { executable.endsWith(it, ignoreCase = true) }) {
            listOf(executable)
        } else {
            extensions.map { executable + it.lowercase() }
        }
    } else {
        listOf(executable)
    }
    return System.getenv("PATH")
        .orEmpty()
        .split(File.pathSeparatorChar)
        .asSequence()
        .filter(String::isNotBlank)
        .flatMap { directory -> candidates.asSequence().map { candidate -> directory to candidate } }
        .mapNotNull { (directory, candidate) ->
            runCatching { Path.of(directory, candidate).toAbsolutePath().normalize() }.getOrNull()
        }
        .firstOrNull { Files.isRegularFile(it) && (File.separatorChar == '\\' || Files.isExecutable(it)) }
}

internal const val DEFAULT_DUNE_LANGUAGE_VERSION = "3.0"
private const val TOOL_PROBE_TIMEOUT_MS = 5_000
private val DUNE_VERSION = Regex("""\b(\d+)\.(\d+)(?:\.\d+)?""")
