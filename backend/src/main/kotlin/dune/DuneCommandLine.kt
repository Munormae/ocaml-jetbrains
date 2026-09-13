package dev.munormae.dune

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import dev.munormae.toolchain.OCamlEnvironmentTool
import dev.munormae.toolchain.createOCamlEnvironmentCommandLine
import java.nio.file.Files
import java.nio.file.Path

internal fun findDuneRoot(startPath: String?): Path? {
    var current = startPath?.let(Path::of)?.toAbsolutePath()?.normalize() ?: return null
    var outermostWorkspace: Path? = null
    var outermostProject: Path? = null
    while (true) {
        if (Files.isRegularFile(current.resolve("dune-workspace"))) outermostWorkspace = current
        if (Files.isRegularFile(current.resolve("dune-project"))) outermostProject = current
        current = current.parent ?: return outermostWorkspace ?: outermostProject
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
