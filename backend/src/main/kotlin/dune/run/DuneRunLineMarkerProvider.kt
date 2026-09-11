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
import java.nio.file.Path

internal data class DuneRunnableTarget(
    val offset: Int,
    val length: Int,
    val command: DuneCommand,
    val name: String,
    val target: String,
)

internal fun findDuneRunnableTargets(text: String, duneFile: Path, root: Path): List<DuneRunnableTarget> =
    DUNE_RUNNABLE_STANZA.findAll(text).mapNotNull { match ->
        val stanzaEnd = matchingParenthesis(text, match.range.first) ?: return@mapNotNull null
        val stanza = text.substring(match.range.first, stanzaEnd + 1)
        val localName = DUNE_NAME.find(stanza)?.groupValues?.get(1) ?: return@mapNotNull null
        val kind = match.groupValues[1]
        val relativeDirectory = root.relativize(duneFile.parent).toString().replace('\\', '/')
        val command = if (kind == "test") DuneCommand.TEST else DuneCommand.EXEC
        val target = if (command == DuneCommand.EXEC) {
            if (relativeDirectory.isBlank()) "./$localName.exe" else "./$relativeDirectory/$localName.exe"
        } else {
            relativeDirectory.ifBlank { "." }
        }
        DuneRunnableTarget(
            offset = match.range.first + match.value.lastIndexOf(kind),
            length = kind.length,
            command = command,
            name = localName,
            target = target,
        )
    }.toList()

private fun matchingParenthesis(text: String, start: Int): Int? {
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until text.length) {
        val character = text[index]
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
            continue
        }
        when (character) {
            '"' -> inString = true
            '(' -> depth++
            ')' -> if (--depth == 0) return index
        }
    }
    return null
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

private val DUNE_RUNNABLE_STANZA = Regex("""\(\s*(executable|test)\b""")
private val DUNE_NAME = Regex("""\(\s*name\s+([^\s()]+)""")
