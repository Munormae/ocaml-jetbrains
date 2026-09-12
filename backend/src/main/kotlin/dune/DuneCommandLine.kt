package dev.munormae.dune

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import dev.munormae.toolchain.OCamlEnvironmentTool
import dev.munormae.toolchain.createOCamlEnvironmentCommandLine
import java.nio.file.Files
import java.nio.file.Path

internal fun findDuneRoot(startPath: String?): Path? {
    var current = startPath?.let(Path::of)?.toAbsolutePath()?.normalize() ?: return null
    while (true) {
        if (
            Files.isRegularFile(current.resolve("dune-project")) ||
            Files.isRegularFile(current.resolve("dune-workspace"))
        ) {
            return current
        }
        current = current.parent ?: return null
    }
}

internal fun createDuneCommandLine(
    workingDirectory: Path,
    useOpam: Boolean,
    opamExecutable: String?,
    opamSwitch: String?,
    duneExecutable: String?,
    arguments: List<String>,
): GeneralCommandLine {
    val resolvedDuneExecutable = duneExecutable.orEmpty().ifBlank { "dune" }
    return if (useOpam) {
        GeneralCommandLine(opamExecutable.orEmpty().ifBlank { "opam" }).apply {
            addParameter("exec")
            if (!opamSwitch.isNullOrBlank()) {
                addParameters("--switch", opamSwitch.trim())
            }
            addParameter("--")
            addParameter(resolvedDuneExecutable)
        }
    } else {
        GeneralCommandLine(resolvedDuneExecutable)
    }.apply {
        addParameters(arguments)
        withWorkingDirectory(workingDirectory)
    }
}

internal fun createDuneCommandLine(
    project: Project,
    workingDirectory: Path,
    arguments: List<String>,
    executableOverride: String = "",
): GeneralCommandLine = createOCamlEnvironmentCommandLine(
    project = project,
    tool = OCamlEnvironmentTool.DUNE,
    arguments = arguments,
    workingDirectory = workingDirectory,
    executableOverride = executableOverride,
)
