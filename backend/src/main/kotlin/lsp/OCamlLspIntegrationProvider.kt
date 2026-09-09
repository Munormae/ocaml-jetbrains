package dev.munormae.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.ExecutionException
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.util.execution.ParametersListUtil
import dev.munormae.settings.OCamlProjectSettings
import java.io.File
import java.nio.file.Path

class OCamlLspIntegrationProvider : LspIntegrationProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        clientStarter: LspIntegrationProvider.LspClientStarter,
    ) {
        if (!shouldStartOCamlLsp(
                lspEnabled = OCamlProjectSettings.getInstance(project).state.lspEnabled,
                trusted = TrustedProjects.isProjectTrusted(project),
                extension = file.extension,
            )
        ) return

        clientStarter.ensureClientStarted(OCamlLspClientDescriptor(project))
    }
}

internal fun shouldStartOCamlLsp(lspEnabled: Boolean, trusted: Boolean, extension: String?): Boolean =
    lspEnabled && trusted && extension?.lowercase() in OCAML_EXTENSIONS

private val OCAML_EXTENSIONS = setOf("ml", "mli")

private class OCamlLspClientDescriptor(project: Project) :
    ProjectWideLspClientDescriptor(project, "OCaml Language Server") {

    override fun isSupportedFile(file: VirtualFile): Boolean = shouldStartOCamlLsp(
        lspEnabled = OCamlProjectSettings.getInstance(project).state.lspEnabled,
        trusted = TrustedProjects.isProjectTrusted(project),
        extension = file.extension,
    )

    override fun getLanguageId(file: VirtualFile): String = "ocaml"

    override fun createCommandLine(): GeneralCommandLine {
        if (!TrustedProjects.isProjectTrusted(project)) {
            throw ExecutionException("ocamllsp cannot start until the project is trusted")
        }
        val state = OCamlProjectSettings.getInstance(project).state
        val lspExecutable = state.lspExecutable.orEmpty().ifBlank { "ocamllsp" }
        val commandLine = if (state.useOpam) {
            GeneralCommandLine(state.opamExecutable.orEmpty().ifBlank { "opam" }).apply {
                addParameter("exec")
                if (!state.opamSwitch.isNullOrBlank()) {
                    addParameters("--switch", state.opamSwitch.orEmpty().trim())
                }
                addParameter("--")
                addParameter(lspExecutable)
            }
        } else {
            GeneralCommandLine(lspExecutable)
        }

        val additionalArguments = ParametersListUtil.parse(state.additionalLspArguments.orEmpty())
        if (additionalArguments.isNotEmpty()) commandLine.addParameters(additionalArguments)
        val toolDirectories = listOf(state.duneExecutable, state.ocamlformatExecutable)
            .mapNotNull { executable -> executable?.takeIf(String::isNotBlank)?.executableDirectory() }
            .distinct()
        if (toolDirectories.isNotEmpty()) {
            val inheritedPath = System.getenv("PATH").orEmpty()
            commandLine.withEnvironment(
                "PATH",
                (toolDirectories + inheritedPath).filter(String::isNotBlank).joinToString(File.pathSeparator),
            )
        }
        project.basePath?.let(commandLine::withWorkDirectory)
        return commandLine
    }

    private fun String.executableDirectory(): String? = runCatching {
        Path.of(trim()).parent?.toAbsolutePath()?.normalize()?.toString()
    }.getOrNull()
}
