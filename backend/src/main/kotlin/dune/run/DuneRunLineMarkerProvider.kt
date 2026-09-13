package dev.munormae.dune.run

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import dev.munormae.OCamlBundle
import dev.munormae.dune.findDuneRoot
import dev.munormae.dune.model.parseDuneFileMetadata
import java.nio.file.Path

internal data class DuneRunnableTarget(
    val offset: Int,
    val length: Int,
    val command: DuneCommand,
    val name: String,
    val target: String,
)

internal fun findDuneRunnableTargets(text: String, duneFile: Path, root: Path): List<DuneRunnableTarget> =
    parseDuneFileMetadata(text, root, duneFile).let { metadata ->
        val relativeDirectory = root.toAbsolutePath().normalize()
            .relativize(duneFile.toAbsolutePath().normalize().parent)
            .joinToString("/")
            .ifBlank { "." }
        buildList {
            metadata.executables.forEach { executable ->
                add(
                    DuneRunnableTarget(
                        executable.sourceOffset,
                        executable.sourceLength,
                        DuneCommand.EXEC,
                        executable.name,
                        executable.target,
                    ),
                )
            }
            metadata.tests.firstOrNull()?.let { test ->
                add(
                    DuneRunnableTarget(
                        test.sourceOffset,
                        test.sourceLength,
                        DuneCommand.TEST,
                        relativeDirectory,
                        relativeDirectory,
                    ),
                )
            }
        }
    }

class DuneRunLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        elements.mapNotNull { it.containingFile }.distinct().forEach { psiFile ->
            val file = psiFile.virtualFile ?: return@forEach
            if (file.name != "dune") return@forEach
            val root = findDuneRoot(file.path) ?: return@forEach
            findDuneRunnableTargets(psiFile.text, Path.of(file.path), root).forEach { target ->
                val tooltip = if (target.command == DuneCommand.EXEC) {
                    OCamlBundle.message("dune.gutter.run", target.name)
                } else {
                    OCamlBundle.message("dune.gutter.test", target.name)
                }
                result += LineMarkerInfo(
                    psiFile,
                    TextRange(target.offset, target.offset + target.length),
                    AllIcons.RunConfigurations.TestState.Run,
                    { tooltip },
                    { _, element -> runTarget(element, root, target) },
                    GutterIconRenderer.Alignment.CENTER,
                    { tooltip },
                )
            }
        }
    }

    private fun runTarget(element: PsiElement, root: Path, target: DuneRunnableTarget) {
        val project = element.project
        val type = ConfigurationTypeUtil.findConfigurationType(DuneRunConfigurationType::class.java)
        val factory = type.configurationFactories.filterIsInstance<DuneConfigurationFactory>()
            .firstOrNull { it.command == target.command } ?: return
        val settings = RunManager.getInstance(project).createConfiguration(
            if (target.command == DuneCommand.EXEC) {
                OCamlBundle.message("dune.gutter.configuration.run", target.name)
            } else {
                OCamlBundle.message("dune.gutter.configuration.test", target.name)
            },
            factory,
        )
        (settings.configuration as DuneRunConfiguration).apply {
            this.target = target.target
            workingDirectory = root.toString()
        }
        RunManager.getInstance(project).setTemporaryConfiguration(settings)
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }
}
