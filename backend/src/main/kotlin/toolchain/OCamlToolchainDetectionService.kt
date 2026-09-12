package dev.munormae.toolchain

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorNotifications
import com.intellij.platform.lsp.api.LspClientManager
import dev.munormae.dune.DuneWatchService
import dev.munormae.dune.model.DuneProjectModelService
import dev.munormae.lsp.OCamlLspIntegrationProvider
import com.intellij.util.concurrency.AppExecutorUtil
import dev.munormae.OCamlBundle
import dev.munormae.settings.OCamlWorkspaceSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class OCamlToolchainDetectionService(private val project: Project) : Disposable {
    private val requestedGeneration = AtomicLong()
    private val mutableStatus = MutableStateFlow(OCamlToolchainStatusSnapshot.NOT_CHECKED)
    @Volatile
    private var activeEnvironmentId: String = ""
    internal val statusFlow: StateFlow<OCamlToolchainStatusSnapshot> = mutableStatus

    internal val status: OCamlToolchainStatusSnapshot
        get() = mutableStatus.value

    fun refresh() {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        val settings = EnvironmentDiscoverySettings.from(OCamlWorkspaceSettings.getInstance(project).state)
        refresh(settings)
    }

    internal fun refresh(settings: EnvironmentDiscoverySettings) {
        val generation = requestedGeneration.incrementAndGet()
        if (!TrustedProjects.isProjectTrusted(project)) {
            mutableStatus.value = blockedStatusSnapshot()
            return
        }

        mutableStatus.value = mutableStatus.value.copy(
            detectionState = OCamlEnvironmentDetectionState.DETECTING,
            problem = "",
        )

        AppExecutorUtil.getAppExecutorService().execute {
            val snapshot = runCatching {
                discoverOCamlEnvironments(settings, project.basePath).toStatusSnapshot()
            }.getOrElse { exception ->
                mutableStatus.value.copy(
                    detectionState = OCamlEnvironmentDetectionState.FAILED,
                    problem = exception.message ?: exception.javaClass.simpleName,
                )
            }
            applySnapshot(generation, snapshot)
        }
    }

    internal fun selectEnvironment(environmentId: String, environmentPrefix: String = "") {
        val workspace = OCamlWorkspaceSettings.getInstance(project).state
        updateEnvironmentSelection(
            workspace,
            resolveEnvironmentSelectionId(environmentId, environmentPrefix),
            environmentPrefix,
        )
        refresh()
    }

    internal fun resolveSelectedEnvironment(): OCamlEnvironmentDescriptor? {
        mutableStatus.value.selectedEnvironment?.let { return it }
        val workspace = OCamlWorkspaceSettings.getInstance(project).state
        val discovered = runCatching {
            discoverOCamlEnvironments(
                settings = EnvironmentDiscoverySettings.from(workspace),
                projectBasePath = project.basePath,
            )
        }.getOrNull() ?: return null
        val selected = discovered.environments.firstOrNull { it.id == discovered.selectedEnvironmentId }
        if (selected != null) {
            mutableStatus.value = discovered.toStatusSnapshot()
            if (workspace.environmentId.isNullOrBlank()) workspace.environmentId = selected.id
        }
        return selected
    }

    internal fun installRequiredTools(environmentId: String) {
        if (!TrustedProjects.isProjectTrusted(project)) return
        val environment = mutableStatus.value.environments.firstOrNull { it.id == environmentId } ?: return
        if (!environment.canInstallTools) return
        val workspace = OCamlWorkspaceSettings.getInstance(project).state
        val commandLine = GeneralCommandLine(workspace.opamExecutableOverride.orEmpty().ifBlank { "opam" }).apply {
            addParameters("install", "--yes")
            if (environment.switchName.isNotBlank()) addParameters("--switch", environment.switchName)
            addParameters("dune", "ocaml-lsp-server", "ocamlformat")
            project.basePath?.let(::withWorkDirectory)
        }
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            OCamlBundle.message("environment.progress.installing"),
            false,
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = OCamlBundle.message("environment.progress.installing")
                val result = runEnvironmentOperation(commandLine)
                if (result.isSuccess) {
                    refresh()
                } else {
                    publishOperationFailure(
                        OCamlBundle.message("environment.error.install", result.problemDescription()),
                    )
                }
            }
        })
    }

    internal fun createLocalEnvironment() {
        if (!TrustedProjects.isProjectTrusted(project)) return
        val workingDirectory = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() } ?: return
        val workspace = OCamlWorkspaceSettings.getInstance(project).state
        val opam = workspace.opamExecutableOverride.orEmpty().ifBlank { "opam" }
        val create = GeneralCommandLine(opam)
            .withParameters("switch", "create", ".", "--yes")
            .withWorkingDirectory(workingDirectory)
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            OCamlBundle.message("environment.progress.creating.local"),
            false,
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = OCamlBundle.message("environment.progress.creating.local")
                val created = runEnvironmentOperation(create)
                if (!created.isSuccess) {
                    publishOperationFailure(
                        OCamlBundle.message("environment.error.create.local", created.problemDescription()),
                    )
                    return
                }
                indicator.text = OCamlBundle.message("environment.progress.installing")
                val install = GeneralCommandLine(opam)
                    .withParameters(
                        "install", "--yes", "--switch", workingDirectory.toString(),
                        "dune", "ocaml-lsp-server", "ocamlformat",
                    )
                    .withWorkingDirectory(workingDirectory)
                val installed = runEnvironmentOperation(install)
                if (installed.isSuccess) {
                    refresh()
                } else {
                    publishOperationFailure(
                        OCamlBundle.message("environment.error.install", installed.problemDescription()),
                    )
                }
            }
        })
    }

    private fun runEnvironmentOperation(commandLine: GeneralCommandLine): EnvironmentCommandResult = try {
        val output = CapturingProcessHandler(commandLine).runProcess(TOOL_INSTALL_TIMEOUT_MS)
        EnvironmentCommandResult(
            exitCode = output.exitCode,
            stdout = output.stdout,
            stderr = output.stderr,
            timedOut = output.isTimeout,
        )
    } catch (exception: Exception) {
        EnvironmentCommandResult(
            exitCode = -1,
            failure = exception.message ?: exception.javaClass.simpleName,
        )
    }

    private fun publishOperationFailure(problem: String) {
        mutableStatus.value = mutableStatus.value.copy(
            detectionState = OCamlEnvironmentDetectionState.FAILED,
            problem = problem,
        )
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) EditorNotifications.getInstance(project).updateAllNotifications()
        }
    }

    private fun applySnapshot(generation: Long, snapshot: OCamlToolchainStatusSnapshot) {
        if (project.isDisposed || requestedGeneration.get() != generation) return
        mutableStatus.value = snapshot
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            EditorNotifications.getInstance(project).updateAllNotifications()
            val selectedEnvironmentId = snapshot.selectedEnvironmentId
            val lspClients = LspClientManager.getInstance(project)
            if (activeEnvironmentId.isNotBlank() && activeEnvironmentId != selectedEnvironmentId) {
                lspClients.stopAndRestartClientsIfNeeded(OCamlLspIntegrationProvider::class.java)
            } else if (snapshot.selectedEnvironment?.languageServer?.isAvailable == true) {
                lspClients.startClientsIfNeeded(OCamlLspIntegrationProvider::class.java)
            }
            activeEnvironmentId = selectedEnvironmentId
            DuneWatchService.getInstance(project).refresh()
            DuneProjectModelService.getInstance(project).requestRefresh(delayMs = 0)
        }
        snapshot.selectedEnvironment?.takeIf(OCamlEnvironmentDescriptor::isReady)?.let { environment ->
            OCamlEnvironmentSdkService.getInstance(project).synchronize(environment)
        }
    }

    override fun dispose() {
        requestedGeneration.incrementAndGet()
    }

    companion object {
        fun getInstance(project: Project): OCamlToolchainDetectionService = project.service()
    }
}

internal fun updateEnvironmentSelection(
    workspace: OCamlWorkspaceSettings.WorkspaceState,
    environmentId: String,
    environmentPrefix: String,
) {
    workspace.environmentId = environmentId
    workspace.environmentPrefixOverride = environmentPrefix
}

internal fun resolveEnvironmentSelectionId(environmentId: String, environmentPrefix: String): String =
    if (environmentId.isBlank() && environmentPrefix.isNotBlank()) {
        environmentCandidate(OCamlEnvironmentKind.CUSTOM, "", environmentPrefix).id
    } else {
        environmentId
    }

private fun EnvironmentCommandResult.problemDescription(): String = when {
    timedOut -> OCamlBundle.message("environment.error.timeout")
    failure.isNotBlank() -> failure
    firstOutputLine.isNotBlank() -> firstOutputLine
    else -> OCamlBundle.message("environment.error.exit", exitCode)
}

internal data class ToolchainSettingsSnapshot(
    val useOpam: Boolean,
    val opamExecutable: String,
    val opamSwitch: String,
    val lspExecutable: String,
    val duneExecutable: String,
    val ocamlformatExecutable: String,
)

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
    probeExecutor: Executor = AppExecutorUtil.getAppExecutorService(),
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
        val tools = runProbesInParallel(languageProbes, probeExecutor)
        return ToolchainDetectionSnapshot(opam, tools[0], tools[1], tools[2])
    }

    val results = runProbesInParallel(listOf(opamProbe) + languageProbes, probeExecutor)
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

private fun runProbesInParallel(
    probes: List<() -> ToolProbeResult>,
    executor: Executor,
): List<ToolProbeResult> = probes
    .map { probe -> CompletableFuture.supplyAsync(probe, executor) }
    .map(CompletableFuture<ToolProbeResult>::join)

private fun ToolchainDetectionSnapshot.toStatusSnapshot(): OCamlToolchainStatusSnapshot =
    OCamlToolchainStatusSnapshot(
        opam = opam.status,
        ocamllsp = ocamllsp.status,
        dune = dune.status,
        ocamlformat = ocamlformat.status,
    )

private fun OCamlEnvironmentDiscoveryResult.toStatusSnapshot(): OCamlToolchainStatusSnapshot {
    val selected = environments.firstOrNull { it.id == selectedEnvironmentId }
    return OCamlToolchainStatusSnapshot(
        opam = opamStatus,
        ocamllsp = selected?.languageServer.presentableStatus(),
        dune = selected?.dune.presentableStatus(),
        ocamlformat = selected?.formatter.presentableStatus(),
        detectionState = OCamlEnvironmentDetectionState.READY,
        environments = environments,
        selectedEnvironmentId = selectedEnvironmentId,
    )
}

private fun blockedStatusSnapshot(): OCamlToolchainStatusSnapshot {
    val blocked = "Blocked until the project is trusted"
    return OCamlToolchainStatusSnapshot(
        opam = blocked,
        ocamllsp = blocked,
        dune = blocked,
        ocamlformat = blocked,
        detectionState = OCamlEnvironmentDetectionState.BLOCKED,
        problem = blocked,
    )
}

private fun OCamlToolStatus?.presentableStatus(): String = when (this?.availability) {
    OCamlToolAvailability.AVAILABLE -> "OK: ${version.ifBlank { "version unavailable" }}"
    OCamlToolAvailability.BLOCKED -> "Blocked"
    OCamlToolAvailability.NOT_CHECKED, null -> "Not checked"
    OCamlToolAvailability.ERROR -> "Unavailable: ${detail.ifBlank { "probe failed" }}"
    OCamlToolAvailability.MISSING -> "Missing: ${detail.ifBlank { executable }}"
}

internal typealias ToolProbe = (GeneralCommandLine, String, Path?) -> ToolProbeResult

private fun executableSource(executable: String, configured: Boolean): String {
    if (configured) return executable
    return findExecutableOnPath(executable)?.toString() ?: "PATH"
}

internal fun findExecutableOnPath(executable: String): Path? {
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
private const val TOOL_INSTALL_TIMEOUT_MS = 10 * 60 * 1_000
private val DUNE_VERSION = Regex("""\b(\d+)\.(\d+)(?:\.\d+)?""")
