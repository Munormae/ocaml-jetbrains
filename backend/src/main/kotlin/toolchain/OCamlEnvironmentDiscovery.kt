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
            )

        fun from(dto: OCamlToolchainSettingsDto): EnvironmentDiscoverySettings =
            EnvironmentDiscoverySettings(
                environmentId = dto.environmentId,
                environmentPrefixOverride = dto.environmentPrefixOverride,
                opamExecutableOverride = dto.opamExecutableOverride,
                lspExecutableOverride = dto.lspExecutableOverride,
                duneExecutableOverride = dto.duneExecutableOverride,
                ocamlformatExecutableOverride = dto.ocamlformatExecutableOverride,
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
    val normalizedPrefix = normalizeEnvironmentPath(prefix)
    val identity = when (kind) {
        OCamlEnvironmentKind.LOCAL_OPAM_SWITCH -> normalizedPrefix
        OCamlEnvironmentKind.OPAM_SWITCH -> switchName.trim()
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
    candidates.firstOrNull { it.kind == OCamlEnvironmentKind.PATH }

internal fun createEnvironmentCommandLine(
    candidate: OCamlEnvironmentCandidate,
    opamExecutable: String,
    executable: String,
    arguments: List<String>,
): GeneralCommandLine {
    val commandLine = if (candidate.kind == OCamlEnvironmentKind.PATH || candidate.kind == OCamlEnvironmentKind.CUSTOM) {
        GeneralCommandLine(executable)
    } else {
        GeneralCommandLine(opamExecutable.ifBlank { "opam" }).apply {
            addParameter("exec")
            if (candidate.switchName.isNotBlank()) addParameters("--switch", candidate.switchName)
            addParameters("--", executable)
        }
    }
    commandLine.addParameters(arguments)
    return commandLine
}

internal fun discoverOCamlEnvironments(
    settings: EnvironmentDiscoverySettings,
    projectBasePath: String?,
    runner: EnvironmentCommandRunner = EnvironmentCommandRunner(::runEnvironmentCommand),
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
        for (switchName in switches) {
            val prefixResult = runner.run(
                GeneralCommandLine(opamExecutable).withParameters(
                    "var", "prefix", "--switch", switchName, "--safe",
                ),
                workingDirectory,
            )
            if (!prefixResult.isSuccess) continue
            val prefix = prefixResult.firstOutputLine
            if (prefix.isBlank() || candidates.any { sameEnvironmentPath(it.prefix, prefix) }) continue
            candidates += environmentCandidate(
                kind = OCamlEnvironmentKind.OPAM_SWITCH,
                switchName = switchName,
                prefix = prefix,
            )
        }
    }

    val pathCompiler = findExecutableOnPath("ocamlc")
    val pathPrefix = pathCompiler?.parent?.parent?.toString().orEmpty()
    if (pathCompiler != null) {
        candidates += environmentCandidate(OCamlEnvironmentKind.PATH, "", pathPrefix)
    }

    val selected = selectEnvironmentCandidate(candidates, settings.environmentId, currentSwitch)
    val descriptors = candidates.distinctBy(OCamlEnvironmentCandidate::id).map { candidate ->
        probeEnvironment(candidate, settings, opamExecutable, workingDirectory, runner, opamAvailable)
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
        val commandLine = createEnvironmentCommandLine(
            candidate = candidate,
            opamExecutable = opamExecutable,
            executable = executable,
            arguments = listOf("--version"),
        )
        val result = runner.run(commandLine, workingDirectory)
        val displayedExecutable = when {
            override.isNotBlank() -> override
            candidate.kind == OCamlEnvironmentKind.PATH -> findExecutableOnPath(toolName)?.toString() ?: toolName
            candidate.prefix.isNotBlank() -> Path.of(candidate.prefix, "bin", platformExecutableName(toolName)).toString()
            else -> executable
        }
        return if (result.isSuccess) {
            OCamlToolStatus(
                availability = OCamlToolAvailability.AVAILABLE,
                version = result.firstOutputLine.ifEmpty { "version unavailable" },
                executable = displayedExecutable,
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
        canInstallTools = opamAvailable && candidate.kind in setOf(
            OCamlEnvironmentKind.LOCAL_OPAM_SWITCH,
            OCamlEnvironmentKind.OPAM_SWITCH,
        ),
    )
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
