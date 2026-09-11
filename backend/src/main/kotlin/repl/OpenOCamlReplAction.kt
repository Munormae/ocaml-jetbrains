package dev.munormae.repl

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.console.ConsoleExecuteAction
import com.intellij.execution.console.LanguageConsoleImpl
import com.intellij.execution.console.ProcessBackedConsoleExecuteActionHandler
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import dev.munormae.OCamlBundle
import dev.munormae.dune.DuneWatchService
import dev.munormae.dune.findDuneRoot
import dev.munormae.dune.run.DuneConsoleFilter
import dev.munormae.lang.OCamlLanguage
import dev.munormae.toolchain.OCamlEnvironmentTool
import dev.munormae.toolchain.OCamlToolchainDetectionService
import dev.munormae.toolchain.createOCamlEnvironmentCommandLine
import java.nio.file.Path

class OpenOCamlReplAction : DumbAwareAction(
    OCamlBundle.message("repl.action.text"),
    OCamlBundle.message("repl.action.description"),
    null,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val root = findDuneRoot(event.getData(CommonDataKeys.VIRTUAL_FILE)?.path ?: project?.basePath)
        event.presentation.isEnabled = project != null &&
            root != null &&
            TrustedProjects.isProjectTrusted(project) &&
            OCamlToolchainDetectionService.getInstance(project).status.selectedEnvironment?.dune?.isAvailable == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val contextFile = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val root = findDuneRoot(contextFile?.path ?: project.basePath) ?: return
        val contextDirectory = contextFile?.let { file -> if (file.isDirectory) file else file.parent }
        val contextPath = contextDirectory?.let { runCatching { Path.of(it.path) }.getOrNull() }
        val utopDirectory = contextPath
            ?.takeIf { it.startsWith(root) }
            ?.let(root::relativize)
            ?.toString()
            ?.replace('\\', '/')
            ?.ifBlank { "." }
            ?: "."
        startRepl(project, root, utopDirectory)
    }

    private fun startRepl(project: com.intellij.openapi.project.Project, root: Path, directory: String) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            OCamlBundle.message("repl.starting"),
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = OCamlBundle.message("repl.starting")
                val pauseLease = DuneWatchService.getInstance(project).acquirePause()
                try {
                    val commandLine = createOCamlEnvironmentCommandLine(
                        project = project,
                        tool = OCamlEnvironmentTool.DUNE,
                        arguments = listOf("utop", directory),
                        workingDirectory = root,
                    )
                    val handler = ColoredProcessHandler(commandLine)
                    handler.addProcessListener(object : ProcessListener {
                        override fun processTerminated(event: ProcessEvent) = pauseLease.close()
                    })
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) {
                            pauseLease.close()
                            handler.destroyProcess()
                            return@invokeLater
                        }
                        val title = OCamlBundle.message("repl.title", directory)
                        val console = LanguageConsoleImpl(project, title, OCamlLanguage).apply { prompt = "# " }
                        val executeHandler = ProcessBackedConsoleExecuteActionHandler(handler, false)
                        ConsoleExecuteAction(console, executeHandler).registerCustomShortcutSet(
                            CommonShortcuts.ENTER,
                            console.consoleEditor.contentComponent,
                        )
                        RunContentExecutor(project, handler)
                            .withTitle(title)
                            .withConsole(console)
                            .withFilter(DuneConsoleFilter(project, root))
                            .withRerun { startRepl(project, root, directory) }
                            .run()
                    }
                } catch (exception: Exception) {
                    pauseLease.close()
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            exception.message ?: exception.javaClass.simpleName,
                            OCamlBundle.message("repl.error.title"),
                        )
                    }
                }
            }
        })
    }
}
