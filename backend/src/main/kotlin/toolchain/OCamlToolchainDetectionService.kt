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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class OCamlToolchainDetectionService(private val project: Project) : Disposable {
    private val requestedGeneration = AtomicLong()
    private val mutableStatus = MutableStateFlow(OCamlToolchainStatusSnapshot.NOT_CHECKED)
    internal val statusFlow: StateFlow<OCamlToolchainStatusSnapshot> = mutableStatus

    fun refresh() {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        val settings = ToolchainSettingsSnapshot.from(OCamlProjectSettings.getInstance(project).state)
        refresh(settings)
    }

    internal fun refresh(settings: ToolchainSettingsSnapshot) {
        val generation = requestedGeneration.incrementAndGet()
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
        if (project.isDisposed || requestedGeneration.get() != generation) return
        mutableStatus.value = snapshot.toStatusSnapshot()
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

        fun from(dto: OCamlToolchainSettingsDto): ToolchainSettingsSnapshot =
            ToolchainSettingsSnapshot(
                useOpam = dto.useOpam,
                opamExecutable = dto.opamExecutable,
                opamSwitch = dto.opamSwitch,
                lspExecutable = dto.lspExecutable,
                duneExecutable = dto.duneExecutable,
                ocamlformatExecutable = dto.ocamlformatExecutable,
            )
    }
}

internal data class ToolProbeResult(
    val status: String,
    val version: String? = null,
    val isAvailable: Boolean = false,
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
    probe: ToolProbe = ::probeTool,
): ToolchainDetectionSnapshot {
    val workingDirectory = projectBasePath
        ?.let { runCatching { Path.of(it) }.getOrNull() }
        ?.takeIf(Files::isDirectory)
    val opamExecutable = settings.opamExecutable.ifBlank { "opam" }
    val opamProbe = {
        probe(
            GeneralCommandLine(opamExecutable).withParameters("--version"),
            executableSource(opamExecutable, configured = settings.opamExecutable.isNotBlank()),
            workingDirectory,
        )
    }

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
        return probe(commandLine, source, workingDirectory)
    }

    val languageProbes = listOf(
        { languageTool(settings.lspExecutable, "ocamllsp") },
        { languageTool(settings.duneExecutable, "dune") },
        { languageTool(settings.ocamlformatExecutable, "ocamlformat") },
    )
    if (settings.useOpam) {
        val opam = opamProbe()
        if (!opam.isAvailable) {
            val unavailable = ToolProbeResult("Unavailable because opam could not be started")
            return ToolchainDetectionSnapshot(opam, unavailable, unavailable, unavailable)
        }
        val tools = runProbesInParallel(languageProbes)
        return ToolchainDetectionSnapshot(opam, tools[0], tools[1], tools[2])
    }

    val results = runProbesInParallel(listOf(opamProbe) + languageProbes)
    return ToolchainDetectionSnapshot(results[0], results[1], results[2], results[3])
}

internal fun detectDuneLanguageVersion(useOpam: Boolean, opamSwitch: String): String? {
    val command = createDuneVersionProbeCommand(useOpam, opamSwitch)
    return probeTool(command, "wizard", null).version?.let(::parseDuneLanguageVersion)
}

internal fun createDuneVersionProbeCommand(useOpam: Boolean, opamSwitch: String): GeneralCommandLine =
    if (useOpam) {
        GeneralCommandLine("opam").apply {
            addParameter("exec")
            if (opamSwitch.isNotBlank()) addParameters("--switch", opamSwitch.trim())
            addParameters("--", "dune", "--version")
        }
    } else {
        GeneralCommandLine("dune").withParameters("--version")
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
            ToolProbeResult("OK: $version ($source)", version, isAvailable = true)
        } else {
            ToolProbeResult("Unavailable: ${text.ifEmpty { "exit code ${output.exitCode}" }} ($source)")
        }
    } catch (exception: Exception) {
        ToolProbeResult("Unavailable: ${exception.message ?: exception.javaClass.simpleName} ($source)")
    }
}

private fun runProbesInParallel(probes: List<() -> ToolProbeResult>): List<ToolProbeResult> = probes
    .map { probe -> CompletableFuture.supplyAsync(probe) }
    .map(CompletableFuture<ToolProbeResult>::join)

private fun ToolchainDetectionSnapshot.toStatusSnapshot(): OCamlToolchainStatusSnapshot =
    OCamlToolchainStatusSnapshot(
        opam = opam.status,
        ocamllsp = ocamllsp.status,
        dune = dune.status,
        ocamlformat = ocamlformat.status,
    )

internal typealias ToolProbe = (GeneralCommandLine, String, Path?) -> ToolProbeResult

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
