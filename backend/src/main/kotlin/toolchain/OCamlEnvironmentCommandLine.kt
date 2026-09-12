package dev.munormae.toolchain

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import dev.munormae.OCamlBundle
import dev.munormae.settings.OCamlWorkspaceSettings
import java.io.File
import java.nio.file.Path

internal enum class OCamlEnvironmentTool(val executableName: String) {
    COMPILER("ocamlc"),
    DUNE("dune"),
    LANGUAGE_SERVER("ocamllsp"),
    FORMATTER("ocamlformat"),
    UTOP("utop"),
}

internal fun createOCamlEnvironmentCommandLine(
    project: Project,
    tool: OCamlEnvironmentTool,
    arguments: List<String> = emptyList(),
    workingDirectory: Path? = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() },
    executableOverride: String = "",
): GeneralCommandLine {
    val workspace = OCamlWorkspaceSettings.getInstance(project).state
    val environment = OCamlToolchainDetectionService.getInstance(project).resolveSelectedEnvironment()
        ?: throw ExecutionException(OCamlBundle.message("environment.error.not.configured"))
    val configuredOverride = executableOverride.ifBlank { when (tool) {
        OCamlEnvironmentTool.COMPILER -> ""
        OCamlEnvironmentTool.DUNE -> workspace.duneExecutableOverride.orEmpty()
        OCamlEnvironmentTool.LANGUAGE_SERVER -> workspace.lspExecutableOverride.orEmpty()
        OCamlEnvironmentTool.FORMATTER -> workspace.ocamlformatExecutableOverride.orEmpty()
        OCamlEnvironmentTool.UTOP -> ""
    } }
    val executable = configuredOverride.ifBlank { when (tool) {
        OCamlEnvironmentTool.COMPILER -> environment.compiler.executable.ifBlank { tool.executableName }
        OCamlEnvironmentTool.DUNE -> environment.dune.executable.ifBlank {
            tool.executableName
        }
        OCamlEnvironmentTool.LANGUAGE_SERVER -> environment.languageServer.executable.ifBlank {
            tool.executableName
        }
        OCamlEnvironmentTool.FORMATTER -> environment.formatter.executable.ifBlank {
            tool.executableName
        }
        OCamlEnvironmentTool.UTOP -> environment.utop.executable.ifBlank {
            tool.executableName
        }
    } }
    val candidate = OCamlEnvironmentCandidate(
        id = environment.id,
        kind = environment.kind,
        switchName = environment.switchName,
        prefix = environment.prefix,
    )
    return createEnvironmentCommandLine(
        candidate = candidate,
        opamExecutable = workspace.opamExecutableOverride.orEmpty().ifBlank { "opam" },
        executable = executable,
        arguments = arguments,
        duneExecutable = workspace.duneExecutableOverride.orEmpty().ifBlank {
            environment.dune.executable.ifBlank { "dune" }
        },
        duneManagedToolName = tool.executableName,
        duneManagedExecutableOverride = configuredOverride.isNotBlank(),
    ).apply {
        if (workingDirectory != null) withWorkingDirectory(workingDirectory)
        val toolDirectories = buildList {
            environment.prefix.takeIf(String::isNotBlank)?.let { prefix ->
                add(Path.of(prefix, "bin").toString())
            }
            addAll(
                listOf(
                    environment.compiler.executable,
                    environment.dune.executable,
                    environment.languageServer.executable,
                    environment.formatter.executable,
                    environment.utop.executable,
                    workspace.lspExecutableOverride.orEmpty(),
                    workspace.duneExecutableOverride.orEmpty(),
                    workspace.ocamlformatExecutableOverride.orEmpty(),
                ).mapNotNull(String::executableDirectory),
            )
        }.distinct()
        if (toolDirectories.isNotEmpty()) {
            val inheritedPath = System.getenv("PATH").orEmpty()
            withEnvironment(
                "PATH",
                (toolDirectories + inheritedPath).filter(String::isNotBlank).joinToString(File.pathSeparator),
            )
        }
    }
}

private fun String.executableDirectory(): String? = takeIf(String::isNotBlank)?.let { executable ->
    runCatching { Path.of(executable.trim()).parent?.toAbsolutePath()?.normalize()?.toString() }.getOrNull()
}
