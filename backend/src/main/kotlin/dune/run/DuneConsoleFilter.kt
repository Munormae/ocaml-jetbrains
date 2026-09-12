package dev.munormae.dune.run

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

internal data class OCamlCompilerLocation(
    val path: String,
    val line: Int,
    val column: Int,
    val startOffset: Int,
    val endOffset: Int,
)

internal fun parseOCamlCompilerLocation(line: String): OCamlCompilerLocation? {
    val match = OCAML_LOCATION.find(line) ?: return null
    return OCamlCompilerLocation(
        path = match.groupValues[1],
        line = match.groupValues[2].toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: return null,
        column = match.groupValues[3].toIntOrNull() ?: 0,
        startOffset = match.range.first,
        endOffset = match.range.last + 1,
    )
}

internal class DuneConsoleFilter(
    private val project: Project,
    private val workingDirectory: Path,
) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val location = parseOCamlCompilerLocation(line) ?: return null
        val configuredPath = runCatching { Path.of(location.path) }.getOrNull() ?: return null
        val resolvedPath = if (configuredPath.isAbsolute) configuredPath.normalize()
        else workingDirectory.resolve(configuredPath).normalize()
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolvedPath) ?: return null
        val lineStart = entireLength - line.length
        return Filter.Result(
            lineStart + location.startOffset,
            lineStart + location.endOffset,
            OpenFileHyperlinkInfo(project, file, location.line, location.column),
        )
    }
}

private val OCAML_LOCATION = Regex("""File \"([^\"]+)\", line (\d+)(?:, characters? (\d+)-(\d+))?""")
