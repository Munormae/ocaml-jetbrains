package dev.munormae.project

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.munormae.OCamlBundle
import dev.munormae.dune.DuneWatchService
import dev.munormae.dune.model.DuneProjectModelService
import dev.munormae.settings.OCamlProjectSettings
import dev.munormae.settings.OCamlWorkspaceSettings
import dev.munormae.toolchain.EnvironmentDiscoverySettings
import dev.munormae.toolchain.OCamlEnvironmentDescriptor
import dev.munormae.toolchain.OCamlEnvironmentKind
import dev.munormae.toolchain.OCamlEnvironmentSdkService
import dev.munormae.toolchain.discoverOCamlEnvironments
import dev.munormae.toolchain.createRequiredToolInstallCommandLines
import dev.munormae.toolchain.OCamlToolAvailability
import dev.munormae.toolchain.OCamlToolStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

internal data class OCamlProjectBootstrapRequest(
    val rootPath: String,
    val projectName: String,
    val template: OCamlProjectTemplate,
    val addTests: Boolean,
    val duneLanguageVersion: String,
    val environment: OCamlEnvironmentDescriptor?,
    val createLocalEnvironment: Boolean,
    val installMissingTools: Boolean,
)

internal class OCamlProjectBootstrapper(private val project: Project) {
    fun bootstrap(request: OCamlProjectBootstrapRequest) {
        val failure = AtomicReference<Throwable?>()
        val completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            Runnable {
                runCatching { bootstrapInBackground(request) }
                    .onFailure(failure::set)
            },
            OCamlBundle.message("wizard.progress.title"),
            true,
            project,
        )
        val error = failure.get()
        if (!completed || error != null) {
            if (error != null) {
                Messages.showErrorDialog(
                    project,
                    error.message ?: error.javaClass.simpleName,
                    OCamlBundle.message("wizard.error.title"),
                )
            }
        }
    }

    private fun bootstrapInBackground(request: OCamlProjectBootstrapRequest) {
        val rootPath = Path.of(request.rootPath).toAbsolutePath().normalize()
        ProgressManager.getInstance().progressIndicator?.text = OCamlBundle.message("wizard.progress.environment")
        Files.createDirectories(rootPath)

        if ((request.createLocalEnvironment || request.installMissingTools) &&
            !TrustedProjects.isProjectTrusted(project)
        ) {
            error(OCamlBundle.message("status.blocked"))
        }
        if (request.createLocalEnvironment) createLocalEnvironment(rootPath)

        ProgressManager.getInstance().progressIndicator?.text = OCamlBundle.message("wizard.progress.files")
        val generatedFiles = createProjectFiles(
            projectName = request.projectName,
            template = request.template,
            addTests = request.addTests,
            duneLanguageVersion = request.duneLanguageVersion,
        )
        val root = writeProjectFiles(rootPath, generatedFiles)

        if (request.installMissingTools) installRequiredTools(rootPath, request)

        ProgressManager.getInstance().progressIndicator?.text = OCamlBundle.message("wizard.progress.module")
        ensureOCamlModule(project, root, request.projectName)

        val environments = discoverOCamlEnvironments(
            settings = EnvironmentDiscoverySettings(
                environmentId = if (request.createLocalEnvironment) "" else request.environment?.id.orEmpty(),
                environmentPrefixOverride = request.environment
                    ?.takeIf { it.kind == OCamlEnvironmentKind.CUSTOM }
                    ?.prefix
                    .orEmpty(),
            ),
            projectBasePath = rootPath.toString(),
        )
        val selected = environments.environments.firstOrNull { it.id == environments.selectedEnvironmentId }
            ?: error(OCamlBundle.message("wizard.environment.invalid"))
        val workspace = OCamlWorkspaceSettings.getInstance(project).state
        workspace.environmentId = selected.id
        workspace.environmentPrefixOverride = selected
            .takeIf { it.kind == OCamlEnvironmentKind.CUSTOM }
            ?.prefix
            .orEmpty()
        workspace.manageDuneWatch = true
        OCamlEnvironmentSdkService.getInstance(project).synchronize(selected)

        val shared = OCamlProjectSettings.getInstance(project)
        shared.state.lspEnabled = true
        shared.state.formatWithOcamlformat = true
        shared.notifyChanged()

        ProgressManager.getInstance().progressIndicator?.text = OCamlBundle.message("wizard.progress.dune")
        DuneProjectModelService.getInstance(project).refreshSynchronously()
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            DuneWatchService.getInstance(project).refresh()
            val entry = root.findFileByRelativePath(request.template.entryFile(request.projectName))
            if (entry != null && entry.isValid) FileEditorManager.getInstance(project).openFile(entry, true)
        }
    }

    private fun createLocalEnvironment(root: Path) {
        val opam = OCamlWorkspaceSettings.getInstance(project).state.opamExecutableOverride.orEmpty().ifBlank { "opam" }
        runCommand(
            GeneralCommandLine(opam)
                .withParameters("switch", "create", ".", "--yes")
                .withWorkingDirectory(root),
            BOOTSTRAP_TIMEOUT_MS,
        )
    }

    private fun installRequiredTools(root: Path, request: OCamlProjectBootstrapRequest) {
        val available = OCamlToolStatus(OCamlToolAvailability.AVAILABLE)
        val environment = if (request.createLocalEnvironment) {
            OCamlEnvironmentDescriptor(
                id = "local_opam_switch:${root.toString().replace('\\', '/')}/_opam",
                name = OCamlBundle.message("environment.kind.local.opam"),
                kind = OCamlEnvironmentKind.LOCAL_OPAM_SWITCH,
                switchName = root.toString(),
                prefix = root.resolve("_opam").toString(),
                compiler = available,
                canInstallTools = true,
            )
        } else {
            request.environment ?: error(OCamlBundle.message("wizard.environment.invalid"))
        }
        val workspace = OCamlWorkspaceSettings.getInstance(project).state
        createRequiredToolInstallCommandLines(
            environment = environment,
            opamExecutable = workspace.opamExecutableOverride.orEmpty().ifBlank { "opam" },
            duneExecutable = workspace.duneExecutableOverride.orEmpty().ifBlank {
                environment.dune.executable.ifBlank { "dune" }
            },
            workingDirectory = root,
        ).forEach { commandLine -> runCommand(commandLine, BOOTSTRAP_TIMEOUT_MS) }
    }

    private fun runCommand(commandLine: GeneralCommandLine, timeoutMs: Int) {
        val handler = BootstrapProcessHandler(commandLine)
        val indicator = ProgressManager.getInstance().progressIndicator
        val output = try {
            if (indicator == null) handler.runProcess(timeoutMs)
            else handler.runProcessWithProgressIndicator(indicator, timeoutMs)
        } catch (exception: ProcessCanceledException) {
            handler.terminateTree()
            throw exception
        }
        if (output.isTimeout) handler.terminateTree()
        if (output.isTimeout || output.exitCode != 0) {
            val detail = sequenceOf(output.stderr, output.stdout)
                .flatMap(String::lineSequence)
                .map(String::trim)
                .firstOrNull(String::isNotEmpty)
                .orEmpty()
            error(detail.ifEmpty { OCamlBundle.message("wizard.error.opam.exit", output.exitCode) })
        }
    }

    private fun writeProjectFiles(rootPath: Path, files: Map<String, String>): VirtualFile {
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rootPath)
            ?: error(OCamlBundle.message("wizard.error.directory", rootPath))
        runProjectWriteAction {
            for ((relativePath, contents) in files) {
                val parentPath = relativePath.substringBeforeLast('/', "")
                val fileName = relativePath.substringAfterLast('/')
                val directory = if (parentPath.isEmpty()) root else createRelativeDirectory(root, parentPath)
                val file = directory.findChild(fileName) ?: directory.createChildData(this, fileName)
                VfsUtil.saveText(file, contents)
            }
        }
        return root
    }

    private fun createRelativeDirectory(root: VirtualFile, relativePath: String): VirtualFile {
        var current = root
        for (name in relativePath.split('/')) {
            current = current.findChild(name) ?: current.createChildDirectory(this, name)
        }
        return current
    }
}

private class BootstrapProcessHandler(commandLine: GeneralCommandLine) : CapturingProcessHandler(commandLine) {
    fun terminateTree() {
        val children = runCatching { process.toHandle().descendants().toList() }.getOrDefault(emptyList())
        if (!isProcessTerminated) {
            destroyProcess()
            if (!waitFor(PROCESS_STOP_TIMEOUT_MS)) {
                killProcessTree(process)
                waitFor(PROCESS_FORCE_KILL_TIMEOUT_MS)
            }
        }
        children.filter { it.isAlive }.forEach { it.destroyForcibly() }
    }
}

private const val BOOTSTRAP_TIMEOUT_MS = 20 * 60 * 1_000
private const val PROCESS_STOP_TIMEOUT_MS = 5_000L
private const val PROCESS_FORCE_KILL_TIMEOUT_MS = 2_000L
