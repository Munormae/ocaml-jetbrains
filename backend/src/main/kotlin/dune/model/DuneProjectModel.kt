package dev.munormae.dune.model

import dev.munormae.OCamlBundle
import dev.munormae.dune.SList
import dev.munormae.dune.parseSExpressions
import dev.munormae.dune.run.DuneCommand
import dev.munormae.dune.run.DuneRunConfigurationSpec
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

enum class DuneTargetKind {
    EXECUTABLE,
    LIBRARY,
    TEST,
}

data class DuneTarget(
    val kind: DuneTargetKind,
    val name: String,
    val target: String = "",
    val publicName: String = "",
    val directory: Path,
)

data class DuneSourceMetadata(
    val libraries: List<DuneTarget> = emptyList(),
    val tests: List<DuneTarget> = emptyList(),
    val packages: List<String> = emptyList(),
    val sourceRoots: List<Path> = emptyList(),
)

data class DuneProjectModel(
    val root: Path,
    val executables: List<DuneTarget> = emptyList(),
    val libraries: List<DuneTarget> = emptyList(),
    val tests: List<DuneTarget> = emptyList(),
    val packages: List<String> = emptyList(),
    val sourceRoots: List<Path> = emptyList(),
) {
    val runConfigurations: List<DuneRunConfigurationSpec>
        get() = buildList {
            add(
                DuneRunConfigurationSpec(
                    command = DuneCommand.BUILD,
                    name = OCamlBundle.message("run.command.build"),
                    workingDirectory = root.toString(),
                ),
            )
            executables.forEach { executable ->
                add(
                    DuneRunConfigurationSpec(
                        command = DuneCommand.EXEC,
                        name = OCamlBundle.message(
                            "run.configuration.exec",
                            executable.publicName.ifBlank { executable.name },
                        ),
                        target = executable.target,
                        workingDirectory = root.toString(),
                        legacyTarget = executable.publicName,
                    ),
                )
            }
            if (tests.isNotEmpty()) {
                add(
                    DuneRunConfigurationSpec(
                        command = DuneCommand.TEST,
                        name = OCamlBundle.message("run.command.test"),
                        workingDirectory = root.toString(),
                    ),
                )
            }
        }
}

sealed interface DuneProjectModelState {
    val runConfigurations: List<DuneRunConfigurationSpec>
        get() = (this as? Ready)?.model?.runConfigurations.orEmpty()

    data object NotLoaded : DuneProjectModelState
    data object Loading : DuneProjectModelState
    data class Ready(val model: DuneProjectModel) : DuneProjectModelState
    data class Failed(val problem: String) : DuneProjectModelState
}

internal fun discoverDuneSourceMetadata(root: Path): DuneSourceMetadata {
    val normalizedRoot = root.toAbsolutePath().normalize()
    val libraries = mutableListOf<DuneTarget>()
    val tests = mutableListOf<DuneTarget>()
    val sourceRoots = linkedSetOf<Path>()
    val packages = linkedSetOf<String>()

    val projectFile = normalizedRoot.resolve("dune-project")
    if (Files.isRegularFile(projectFile)) {
        runCatching { parseSExpressions(Files.readString(projectFile)).filterIsInstance<SList>() }
            .getOrDefault(emptyList())
            .filter { it.head == "package" }
            .mapNotNull { it.field("name")?.atomValuesAfterHead()?.firstOrNull() }
            .forEach(packages::add)
    }

    runCatching {
        Files.walkFileTree(normalizedRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (directory != normalizedRoot && directory.fileName.toString() in IGNORED_DIRECTORIES) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (!attributes.isRegularFile || file.fileName.toString() != "dune") return FileVisitResult.CONTINUE
                val forms = runCatching { parseSExpressions(Files.readString(file)).filterIsInstance<SList>() }
                    .getOrDefault(emptyList())
                for (form in forms) {
                    when (form.head) {
                        "library" -> {
                            val name = form.field("name")?.atomValuesAfterHead()?.firstOrNull() ?: continue
                            libraries += DuneTarget(
                                kind = DuneTargetKind.LIBRARY,
                                name = name,
                                publicName = form.field("public_name")?.atomValuesAfterHead()?.firstOrNull().orEmpty(),
                                directory = file.parent,
                            )
                            sourceRoots += file.parent
                        }

                        "test" -> {
                            val name = form.field("name")?.atomValuesAfterHead()?.firstOrNull() ?: continue
                            tests += DuneTarget(DuneTargetKind.TEST, name, directory = file.parent)
                            sourceRoots += file.parent
                        }

                        "tests" -> {
                            form.field("names")?.atomValuesAfterHead().orEmpty().forEach { name ->
                                tests += DuneTarget(DuneTargetKind.TEST, name, directory = file.parent)
                            }
                            sourceRoots += file.parent
                        }

                        "cram" -> {
                            tests += DuneTarget(DuneTargetKind.TEST, file.parent.fileName.toString(), directory = file.parent)
                            sourceRoots += file.parent
                        }

                        "executable", "executables" -> sourceRoots += file.parent
                    }
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    return DuneSourceMetadata(
        libraries = libraries.distinctBy { it.directory to it.name },
        tests = tests.distinctBy { it.directory to it.name },
        packages = packages.toList(),
        sourceRoots = sourceRoots.toList(),
    )
}

private val IGNORED_DIRECTORIES = setOf("_build", "_opam", ".git", ".idea")
