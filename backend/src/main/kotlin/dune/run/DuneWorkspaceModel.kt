package dev.munormae.dune.run

import dev.munormae.dune.SAtom
import dev.munormae.dune.SExpression
import dev.munormae.dune.SList
import dev.munormae.dune.parseSExpressions
import java.nio.file.Path

internal fun parseDuneDescribeRunConfigurations(
    output: String,
    duneRoot: Path,
): List<DuneRunConfigurationSpec> {
    val root = parseSExpressions(output).singleOrNull() as? SList ?: return emptyList()
    val buildContext = root.field("build_context")
        ?.atomValuesAfterHead()
        ?.firstOrNull()
        ?.normalizePath()
        ?: "_build/default"
    val executableSections = root.values
        .filterIsInstance<SList>()
        .filter { it.head == "executables" }

    return executableSections
        .flatMap(::executableDescriptions)
        .flatMap { description ->
            val names = description.field("names")?.atomValuesAfterHead().orEmpty()
            val implementationPaths = description.descendantFields("impl")
                .mapNotNull { it.atomValuesAfterHead().firstOrNull() }
            names.mapNotNull { name ->
                val implementation = implementationPaths.firstOrNull { path ->
                    path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
                        .equals(name, ignoreCase = true)
                } ?: implementationPaths.firstOrNull() ?: return@mapNotNull null
                val relativeSource = implementation.normalizePath()
                    .removePrefix(buildContext.trimEnd('/') + "/")
                val directory = relativeSource.substringBeforeLast('/', "")
                val target = if (directory.isEmpty()) "./$name.exe" else "./$directory/$name.exe"
                DuneRunConfigurationSpec(
                    command = DuneCommand.EXEC,
                    name = "Dune Run $name",
                    target = target,
                    workingDirectory = duneRoot.toAbsolutePath().normalize().toString(),
                )
            }
        }
        .distinctBy(DuneRunConfigurationSpec::target)
}

private fun executableDescriptions(section: SList): List<SList> = section.values
    .drop(1)
    .filterIsInstance<SList>()
    .flatMap { candidate ->
        when {
            candidate.field("names") != null -> listOf(candidate)
            else -> candidate.values.filterIsInstance<SList>().filter { it.field("names") != null }
        }
    }

private fun SList.descendantFields(name: String): List<SList> = buildList {
    fun visit(expression: SExpression) {
        if (expression !is SList) return
        if (expression.head == name) add(expression)
        expression.values.forEach(::visit)
    }
    visit(this@descendantFields)
}

private fun String.normalizePath(): String = replace('\\', '/').removePrefix("./")
