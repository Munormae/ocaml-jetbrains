package dev.munormae.toolchain

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import dev.munormae.OCamlBundle
import dev.munormae.settings.OCamlWorkspaceSettings

internal data class OCamlEnvironmentCandidate(
    val id: String,
    val kind: OCamlEnvironmentKind,
    val switchName: String,
    val prefix: String,
)

internal data class EnvironmentDiscoverySettings(
    val environmentId: String = "",
    val environmentPrefixOverride: String = "",
    val opamExecutableOverride: String = "",
    val lspExecutableOverride: String = "",
    val duneExecutableOverride: String = "",
    val ocamlformatExecutableOverride: String = "",
    val additionalLspArguments: String = "",
) {
    companion object {
        fun from(state: OCamlWorkspaceSettings.WorkspaceState): EnvironmentDiscoverySettings =
            EnvironmentDiscoverySettings(
                environmentId = state.environmentId.orEmpty(),
                environmentPrefixOverride = state.environmentPrefixOverride.orEmpty(),
                opamExecutableOverride = state.opamExecutableOverride.orEmpty(),
                lspExecutableOverride = state.lspExecutableOverride.orEmpty(),
                duneExecutableOverride = state.duneExecutableOverride.orEmpty(),
                ocamlformatExecutableOverride = state.ocamlformatExecutableOverride.orEmpty(),
                additionalLspArguments = state.additionalLspArguments.orEmpty(),
            )

        fun from(dto: OCamlToolchainSettingsDto): EnvironmentDiscoverySettings =
            EnvironmentDiscoverySettings(
                environmentId = dto.environmentId,
                environmentPrefixOverride = dto.environmentPrefixOverride,
                opamExecutableOverride = dto.opamExecutableOverride,
                lspExecutableOverride = dto.lspExecutableOverride,
                duneExecutableOverride = dto.duneExecutableOverride,
                ocamlformatExecutableOverride = dto.ocamlformatExecutableOverride,
                additionalLspArguments = dto.additionalLspArguments,
            )
    }
}

internal data class EnvironmentCommandResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = false,
    val failure: String = "",
) {
    val isSuccess: Boolean
        get() = exitCode == 0 && !timedOut && failure.isEmpty()

    val firstOutputLine: String
        get() = sequenceOf(stdout, stderr)
            .flatMap(String::lineSequence)
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
            .orEmpty()
}

internal data class OCamlEnvironmentDiscoveryResult(
    val opamStatus: String,
    val opamAvailable: Boolean,
    val environments: List<OCamlEnvironmentDescriptor>,
    val selectedEnvironmentId: String,
    val currentOpamSwitch: String,
)

internal data class EnvironmentProbeCacheKey(
    val environmentId: String,
    val executableFingerprint: String,
    val settings: EnvironmentDiscoverySettings,
)

internal class EnvironmentProbeCache {
    private val values = object : LinkedHashMap<EnvironmentProbeCacheKey, OCamlEnvironmentDescriptor>(
        MAX_PROBE_CACHE_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<EnvironmentProbeCacheKey, OCamlEnvironmentDescriptor>?,
        ): Boolean = size > MAX_PROBE_CACHE_ENTRIES
    }

    @Synchronized
    fun getOrProbe(
        key: EnvironmentProbeCacheKey,
        probe: () -> OCamlEnvironmentDescriptor,
    ): OCamlEnvironmentDescriptor = values[key] ?: probe().also { values[key] = it }
}

internal fun interface EnvironmentCommandRunner {
    fun run(commandLine: GeneralCommandLine, workingDirectory: Path?): EnvironmentCommandResult
}

internal fun parseOpamSwitchList(output: String): List<String> = output
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()
    .toList()

internal fun environmentCandidate(
    kind: OCamlEnvironmentKind,
    switchName: String,
    prefix: String,
): OCamlEnvironmentCandidate {
    val normalizedPrefix = prefix.takeIf(String::isNotBlank)?.let(::normalizeEnvironmentPath).orEmpty()
    val identity = when (kind) {
        OCamlEnvironmentKind.LOCAL_OPAM_SWITCH -> normalizedPrefix
        OCamlEnvironmentKind.OPAM_SWITCH -> switchName.trim()
        OCamlEnvironmentKind.DUNE_PACKAGE_MANAGEMENT -> normalizedPrefix
        OCamlEnvironmentKind.CUSTOM -> normalizedPrefix
        OCamlEnvironmentKind.PATH -> normalizedPrefix.ifEmpty { "system" }
    }
    return OCamlEnvironmentCandidate(
        id = "${kind.name.lowercase(Locale.ROOT)}:$identity",
        kind = kind,
        switchName = switchName.trim(),
        prefix = normalizedPrefix,
    )
}

internal fun selectEnvironmentCandidate(
    candidates: List<OCamlEnvironmentCandidate>,
    requestedId: String,
    currentOpamSwitch: String,
): OCamlEnvironmentCandidate? =
    candidates.firstOrNull { it.id == requestedId } ?:
    candidates.firstOrNull { it.kind == OCamlEnvironmentKind.LOCAL_OPAM_SWITCH } ?:
    candidates.firstOrNull {
        it.kind == OCamlEnvironmentKind.OPAM_SWITCH && it.switchName == currentOpamSwitch
    } ?:
    candidates.firstOrNull { it.kind == OCamlEnvironmentKind.OPAM_SWITCH } ?:
    candidates.firstOrNull { it.kind == OCamlEnvironmentKind.DUNE_PACKAGE_MANAGEMENT } ?:
    candidates.firstOrNull { it.kind == OCamlEnvironmentKind.PATH }

internal fun createEnvironmentCommandLine(
    candidate: OCamlEnvironmentCandidate,
    opamExecutable: String,
    executable: String,
    arguments: List<String>,
    duneExecutable: String = "dune",
    duneManagedToolName: String = executable,
    duneManagedExecutableOverride: Boolean = false,
): GeneralCommandLine {
    val commandLine = when (candidate.kind) {
        OCamlEnvironmentKind.PATH,
        OCamlEnvironmentKind.CUSTOM -> GeneralCommandLine(executable).apply { addParameters(arguments) }

        OCamlEnvironmentKind.DUNE_PACKAGE_MANAGEMENT -> {
            if (duneManagedToolName == "dune") {
                GeneralCommandLine(executable).apply { addParameters(arguments) }
            } else {
                GeneralCommandLine(duneExecutable.ifBlank { "dune" }).apply {
                    if (duneManagedExecutableOverride || duneManagedToolName == "ocamlc") {
                        addParameters("exec", "--", executable)
                        addParameters(arguments)
                    } else {
                        addParameters("tools", "exec", duneManagedToolName)
                        if (arguments.isNotEmpty()) {
                            addParameter("--")
                            addParameters(arguments)
                        }
                    }
                }
            }
        }

        OCamlEnvironmentKind.LOCAL_OPAM_SWITCH,
        OCamlEnvironmentKind.OPAM_SWITCH -> GeneralCommandLine(opamExecutable.ifBlank { "opam" }).apply {
            addParameter("exec")
            if (candidate.switchName.isNotBlank()) addParameters("--switch", candidate.switchName)
            addParameters("--", executable)
            addParameters(arguments)
        }
    }
    return commandLine
}

internal fun discoverOCamlEnvironments(
    settings: EnvironmentDiscoverySettings,
    projectBasePath: String?,
    runner: EnvironmentCommandRunner = EnvironmentCommandRunner(::runEnvironmentCommand),
    probeCache: EnvironmentProbeCache? = null,
): OCamlEnvironmentDiscoveryResult {
    val workingDirectory = projectBasePath
        ?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
        ?.takeIf(Files::isDirectory)
    val opamExecutable = settings.opamExecutableOverride.ifBlank { "opam" }
    val opamVersion = runner.run(
        GeneralCommandLine(opamExecutable).withParameters("--version"),
        workingDirectory,
    )
    val opamAvailable = opamVersion.isSuccess
    val currentSwitch = if (opamAvailable) {
        runner.run(
            GeneralCommandLine(opamExecutable).withParameters("switch", "show", "--safe"),
            workingDirectory,
        ).firstOutputLine
    } else ""

    val candidates = mutableListOf<OCamlEnvironmentCandidate>()
    settings.environmentPrefixOverride
        .takeIf(String::isNotBlank)
        ?.let { prefix ->
            candidates += environmentCandidate(
                kind = OCamlEnvironmentKind.CUSTOM,
                switchName = "",
                prefix = prefix,
            )
        }
    val localPrefix = workingDirectory?.resolve("_opam")
    if (localPrefix != null && Files.isDirectory(localPrefix)) {
        candidates += environmentCandidate(
            kind = OCamlEnvironmentKind.LOCAL_OPAM_SWITCH,
            switchName = workingDirectory.toString(),
            prefix = localPrefix.toString(),
        )
    }

    if (opamAvailable) {
        val switches = runner.run(
            GeneralCommandLine(opamExecutable).withParameters("switch", "list", "--short", "--safe"),
            workingDirectory,
        ).takeIf(EnvironmentCommandResult::isSuccess)?.let { parseOpamSwitchList(it.stdout) }.orEmpty()
        val requestedSwitch = settings.environmentId
            .takeIf { it.startsWith("opam_switch:") }
            ?.removePrefix("opam_switch:")
            .orEmpty()
        val switchesNeedingPrefix = setOf(currentSwitch, requestedSwitch).filter(String::isNotBlank).toSet()
        for (switchName in switches) {
            val prefix = if (switchName in switchesNeedingPrefix) {
                runner.run(
                    GeneralCommandLine(opamExecutable).withParameters(
                        "var", "prefix", "--switch", switchName, "--safe",
                    ),
                    workingDirectory,
                ).takeIf(EnvironmentCommandResult::isSuccess)?.firstOutputLine.orEmpty()
            } else {
                ""
            }
            if (prefix.isNotBlank() && candidates.any { it.prefix.isNotBlank() && sameEnvironmentPath(it.prefix, prefix) }) {
                continue
            }
            candidates += environmentCandidate(
                kind = OCamlEnvironmentKind.OPAM_SWITCH,
                switchName = switchName,
                prefix = prefix,
            )
        }
    }

    val duneExecutable = settings.duneExecutableOverride.ifBlank { "dune" }
    if (
        workingDirectory != null &&
        (Files.isRegularFile(workingDirectory.resolve("dune-project")) ||
            Files.isRegularFile(workingDirectory.resolve("dune-workspace"))) &&
        executableExists(duneExecutable) &&
        runner.run(
            GeneralCommandLine(duneExecutable).withParameters("tools", "--help"),
            workingDirectory,
        ).isSuccess
    ) {
        candidates += environmentCandidate(
            kind = OCamlEnvironmentKind.DUNE_PACKAGE_MANAGEMENT,
            switchName = "",
            prefix = workingDirectory.toString(),
        )
    }

    val pathCompiler = findExecutableOnPath("ocamlc")
    val pathPrefix = pathCompiler?.parent?.parent?.toString().orEmpty()
    if (pathCompiler != null) {
        candidates += environmentCandidate(OCamlEnvironmentKind.PATH, "", pathPrefix)
    }

    val selected = selectEnvironmentCandidate(candidates, settings.environmentId, currentSwitch)
    val descriptors = candidates.distinctBy(OCamlEnvironmentCandidate::id).map { candidate ->
        if (candidate.id == selected?.id) {
            val probe = {
                probeEnvironment(candidate, settings, opamExecutable, workingDirectory, runner, opamAvailable)
            }
            probeCache?.getOrProbe(environmentProbeCacheKey(candidate, settings), probe) ?: probe()
        } else {
            unprobedEnvironment(candidate, opamAvailable)
        }
    }
    return OCamlEnvironmentDiscoveryResult(
        opamStatus = if (opamAvailable) {
            "OK: ${opamVersion.firstOutputLine.ifEmpty { "version unavailable" }}"
        } else {
            "Unavailable: ${opamVersion.problemDescription()}"
        },
        opamAvailable = opamAvailable,
        environments = descriptors,
        selectedEnvironmentId = selected?.id.orEmpty(),
        currentOpamSwitch = currentSwitch,
    )
}

private fun environmentProbeCacheKey(
    candidate: OCamlEnvironmentCandidate,
    settings: EnvironmentDiscoverySettings,
): EnvironmentProbeCacheKey {
    val executableNames = listOf("ocamlc", "dune", "ocamllsp", "ocamlformat", "utop")
    val paths = buildList {
        if (candidate.prefix.isNotBlank()) {
            executableNames.forEach { name ->
                add(Path.of(candidate.prefix, "bin", platformExecutableName(name)))
            }
            add(Path.of(candidate.prefix, "_build", ".dev-tools.locks"))
        }
        executableNames.mapNotNullTo(this) { findExecutableOnPath(it) }
        listOf(
            settings.lspExecutableOverride,
            settings.duneExecutableOverride,
            settings.ocamlformatExecutableOverride,
        ).filter(String::isNotBlank).mapNotNullTo(this) { runCatching { Path.of(it) }.getOrNull() }
    }
    val fingerprint = buildString {
        append(System.getenv("PATH").orEmpty())
        paths.distinct().forEach { path ->
            append('|').append(path.toAbsolutePath().normalize())
            append(':').append(runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(-1))
            append(':').append(runCatching { Files.size(path) }.getOrDefault(-1))
        }
    }
    return EnvironmentProbeCacheKey(candidate.id, fingerprint, settings)
}

private fun probeEnvironment(
    candidate: OCamlEnvironmentCandidate,
    settings: EnvironmentDiscoverySettings,
    opamExecutable: String,
    workingDirectory: Path?,
    runner: EnvironmentCommandRunner,
    opamAvailable: Boolean,
): OCamlEnvironmentDescriptor {
    fun probe(toolName: String, override: String): OCamlToolStatus {
        val executable = override.ifBlank {
            if (candidate.kind == OCamlEnvironmentKind.CUSTOM && candidate.prefix.isNotBlank()) {
                Path.of(candidate.prefix, "bin", platformExecutableName(toolName)).toString()
            } else {
                toolName
            }
        }
        val usesDuneToolWhich =
            candidate.kind == OCamlEnvironmentKind.DUNE_PACKAGE_MANAGEMENT &&
            toolName in DUNE_MANAGED_DEVELOPER_TOOLS &&
            override.isBlank()
        val commandLine = if (usesDuneToolWhich) {
            GeneralCommandLine(settings.duneExecutableOverride.ifBlank { "dune" })
                .withParameters("tools", "which", toolName)
        } else {
            createEnvironmentCommandLine(
                candidate = candidate,
                opamExecutable = opamExecutable,
                executable = executable,
                arguments = listOf("--version"),
                duneExecutable = settings.duneExecutableOverride.ifBlank { "dune" },
                duneManagedToolName = toolName,
                duneManagedExecutableOverride = override.isNotBlank(),
            )
        }
        val result = runner.run(commandLine, workingDirectory)
        val displayedExecutable = when {
            override.isNotBlank() -> override
            candidate.kind == OCamlEnvironmentKind.PATH -> findExecutableOnPath(toolName)?.toString() ?: toolName
            candidate.kind == OCamlEnvironmentKind.DUNE_PACKAGE_MANAGEMENT -> toolName
            candidate.prefix.isNotBlank() -> Path.of(candidate.prefix, "bin", platformExecutableName(toolName)).toString()
            else -> executable
        }
        return if (result.isSuccess) {
            OCamlToolStatus(
                availability = OCamlToolAvailability.AVAILABLE,
                version = if (usesDuneToolWhich) "" else result.firstOutputLine.ifEmpty { "version unavailable" },
                executable = if (usesDuneToolWhich) {
                    result.firstOutputLine.ifEmpty { displayedExecutable }
                } else {
                    displayedExecutable
                },
            )
        } else {
            OCamlToolStatus(
                availability = OCamlToolAvailability.MISSING,
                executable = displayedExecutable,
                detail = result.problemDescription(),
            )
        }
    }

    val compiler = probe("ocamlc", "")
    val version = compiler.version.ifBlank { OCamlBundle.message("status.unavailable.short") }
    val qualifier = when (candidate.kind) {
        OCamlEnvironmentKind.LOCAL_OPAM_SWITCH -> OCamlBundle.message("environment.kind.local.opam")
        OCamlEnvironmentKind.OPAM_SWITCH -> OCamlBundle.message("environment.kind.opam", candidate.switchName)
        OCamlEnvironmentKind.DUNE_PACKAGE_MANAGEMENT -> OCamlBundle.message("environment.kind.dune.package.management")
        OCamlEnvironmentKind.CUSTOM -> OCamlBundle.message("environment.kind.custom")
        OCamlEnvironmentKind.PATH -> OCamlBundle.message("environment.kind.path")
    }
    return OCamlEnvironmentDescriptor(
        id = candidate.id,
        name = OCamlBundle.message("environment.name", version, qualifier),
        kind = candidate.kind,
        switchName = candidate.switchName,
        prefix = candidate.prefix,
        compiler = compiler,
        dune = probe("dune", settings.duneExecutableOverride),
        languageServer = probe("ocamllsp", settings.lspExecutableOverride),
        formatter = probe("ocamlformat", settings.ocamlformatExecutableOverride),
        utop = probe("utop", ""),
        canInstallTools = candidate.kind == OCamlEnvironmentKind.DUNE_PACKAGE_MANAGEMENT ||
            opamAvailable && candidate.kind in setOf(
                OCamlEnvironmentKind.LOCAL_OPAM_SWITCH,
                OCamlEnvironmentKind.OPAM_SWITCH,
            ),
    )
}

private fun unprobedEnvironment(
    candidate: OCamlEnvironmentCandidate,
    opamAvailable: Boolean,
): OCamlEnvironmentDescriptor {
    val qualifier = when (candidate.kind) {
        OCamlEnvironmentKind.LOCAL_OPAM_SWITCH -> OCamlBundle.message("environment.kind.local.opam")
        OCamlEnvironmentKind.OPAM_SWITCH -> OCamlBundle.message("environment.kind.opam", candidate.switchName)
        OCamlEnvironmentKind.DUNE_PACKAGE_MANAGEMENT -> OCamlBundle.message("environment.kind.dune.package.management")
        OCamlEnvironmentKind.CUSTOM -> OCamlBundle.message("environment.kind.custom")
        OCamlEnvironmentKind.PATH -> OCamlBundle.message("environment.kind.path")
    }
    return OCamlEnvironmentDescriptor(
        id = candidate.id,
        name = qualifier,
        kind = candidate.kind,
        switchName = candidate.switchName,
        prefix = candidate.prefix,
        canInstallTools = candidate.kind == OCamlEnvironmentKind.DUNE_PACKAGE_MANAGEMENT ||
            opamAvailable && candidate.kind in setOf(
                OCamlEnvironmentKind.LOCAL_OPAM_SWITCH,
                OCamlEnvironmentKind.OPAM_SWITCH,
            ),
    )
}

private fun executableExists(executable: String): Boolean {
    val configuredPath = runCatching { Path.of(executable) }.getOrNull()
    if (configuredPath != null && (configuredPath.isAbsolute || configuredPath.parent != null)) {
        return Files.isRegularFile(configuredPath)
    }
    return findExecutableOnPath(executable) != null
}

private fun runEnvironmentCommand(commandLine: GeneralCommandLine, workingDirectory: Path?): EnvironmentCommandResult {
    if (workingDirectory != null) commandLine.withWorkingDirectory(workingDirectory)
    return try {
        val output = CapturingProcessHandler(commandLine).runProcess(ENVIRONMENT_PROBE_TIMEOUT_MS)
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
}

private fun EnvironmentCommandResult.problemDescription(): String = when {
    timedOut -> "timed out"
    failure.isNotBlank() -> failure
    firstOutputLine.isNotBlank() -> firstOutputLine
    else -> "exit code $exitCode"
}

private fun normalizeEnvironmentPath(path: String): String = runCatching {
    Path.of(path).toAbsolutePath().normalize().toString().replace('\\', '/')
}.getOrDefault(path.trim().replace('\\', '/'))

private fun sameEnvironmentPath(first: String, second: String): Boolean =
    normalizeEnvironmentPath(first).equals(
        normalizeEnvironmentPath(second),
        ignoreCase = File.separatorChar == '\\',
    )

private fun platformExecutableName(name: String): String =
    if (File.separatorChar == '\\' && !name.endsWith(".exe", ignoreCase = true)) "$name.exe" else name

internal const val ENVIRONMENT_PROBE_TIMEOUT_MS = 5_000
private const val MAX_PROBE_CACHE_ENTRIES = 64
private val DUNE_MANAGED_DEVELOPER_TOOLS = setOf("ocamllsp", "ocamlformat", "utop")
