package dev.munormae.dune.model

import dev.munormae.OCamlBundle
import dev.munormae.dune.SList
import dev.munormae.dune.SAtom
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
    val sourceOffset: Int = 0,
    val sourceLength: Int = 0,
)

data class DuneSourceMetadata(
    val executables: List<DuneTarget> = emptyList(),
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

data class DuneWorkspaceModel(
    val workspaceRoot: Path,
    val projects: Map<Path, DuneProjectModel>,
) {
    val roots: List<Path>
        get() = projects.keys.toList()

    val primaryModel: DuneProjectModel?
        get() = projects[workspaceRoot] ?: projects.values.firstOrNull()

    val runConfigurations: List<DuneRunConfigurationSpec>
        get() = projects.values.flatMap(DuneProjectModel::runConfigurations)
}

sealed interface DuneProjectModelState {
    val runConfigurations: List<DuneRunConfigurationSpec>
        get() = (this as? Ready)?.workspace?.runConfigurations.orEmpty()

    data object NotLoaded : DuneProjectModelState
    data object Loading : DuneProjectModelState
    data class Ready(val workspace: DuneWorkspaceModel) : DuneProjectModelState {
        constructor(model: DuneProjectModel) : this(
            DuneWorkspaceModel(model.root, linkedMapOf(model.root to model)),
        )

        val model: DuneProjectModel
            get() = requireNotNull(workspace.primaryModel)
    }
    data class Failed(val problem: String) : DuneProjectModelState
}

internal fun discoverDuneSourceMetadata(root: Path): DuneSourceMetadata {
    val model = discoverDuneSourceModel(root)
    return DuneSourceMetadata(
        executables = model.executables,
        libraries = model.libraries,
        tests = model.tests,
        packages = model.packages,
        sourceRoots = model.sourceRoots,
    )
}

internal fun discoverDuneSourceModel(root: Path): DuneProjectModel {
    val normalizedRoot = root.toAbsolutePath().normalize()
    val workspace = discoverDuneWorkspaceSourceModel(normalizedRoot)
    return workspace.projects[normalizedRoot]
        ?: DuneProjectModel(root = normalizedRoot)
}

internal fun discoverDuneWorkspaceSourceModel(workspaceRoot: Path): DuneWorkspaceModel =
    DuneWorkspaceSourceIndex(workspaceRoot).refresh()

internal class DuneWorkspaceSourceIndex(workspaceRoot: Path) {
    private val workspaceRoot = workspaceRoot.toAbsolutePath().normalize()
    private val roots = linkedSetOf<Path>()
    private val packagesByRoot = mutableMapOf<Path, List<String>>()
    private val duneFiles = linkedMapOf<Path, IndexedDuneFile>()
    private var initialized = false

    @Synchronized
    fun refresh(changedPaths: Set<Path> = emptySet()): DuneWorkspaceModel {
        if (!initialized || changedPaths.any(::isRootTopologyFile)) {
            scanAll()
        } else {
            changedPaths.asSequence()
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .filter { it.fileName?.toString() == "dune" }
                .forEach(::refreshDuneFile)
        }
        return buildWorkspace()
    }

    private fun scanAll() {
        roots.clear()
        packagesByRoot.clear()
        duneFiles.clear()
        val discoveredDuneFiles = mutableListOf<Path>()
        val projectFiles = mutableListOf<Path>()

        Files.walkFileTree(workspaceRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (directory != workspaceRoot && directory.fileName.toString() in IGNORED_DIRECTORIES) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (!attributes.isRegularFile) return FileVisitResult.CONTINUE
                val normalizedFile = file.toAbsolutePath().normalize()
                when (file.fileName.toString()) {
                    "dune" -> discoveredDuneFiles.add(normalizedFile)
                    "dune-project", "dune-workspace" -> {
                        roots.add(normalizedFile.parent)
                        if (file.fileName.toString() == "dune-project") projectFiles.add(normalizedFile)
                    }
                }
                return FileVisitResult.CONTINUE
            }
        })

        if (discoveredDuneFiles.any { file -> roots.none(file::startsWith) }) roots.add(workspaceRoot)
        projectFiles.sorted().forEach(::refreshProjectFile)
        discoveredDuneFiles.sorted().forEach(::refreshDuneFile)
        initialized = true
    }

    private fun refreshProjectFile(projectFile: Path) {
        val packages = runCatching { parseSExpressions(Files.readString(projectFile)).filterIsInstance<SList>() }
            .getOrDefault(emptyList())
            .filter { it.head == "package" }
            .mapNotNull { it.field("name")?.atomValuesAfterHead()?.firstOrNull() }
            .distinct()
        packagesByRoot[projectFile.parent] = packages
    }

    private fun refreshDuneFile(duneFile: Path) {
        if (!Files.isRegularFile(duneFile)) {
            duneFiles.remove(duneFile)
            return
        }
        if (roots.isEmpty()) roots.add(workspaceRoot)
        val root = orderedRoots().lastOrNull(duneFile::startsWith) ?: return
        val metadata = runCatching { parseDuneFileMetadata(Files.readString(duneFile), root, duneFile) }
            .getOrNull()
            ?: run {
                duneFiles.remove(duneFile)
                return
            }
        duneFiles[duneFile] = IndexedDuneFile(root, duneFile.parent, metadata)
    }

    private fun buildWorkspace(): DuneWorkspaceModel {
        val orderedRoots = orderedRoots()
        val accumulators = orderedRoots.associateWith { root ->
            MutableDuneProjectModel(root).apply { packages.addAll(packagesByRoot[root].orEmpty()) }
        }
        duneFiles.values.forEach { indexed ->
            accumulators[indexed.root]?.add(indexed.directory, indexed.metadata)
        }
        return DuneWorkspaceModel(
            workspaceRoot = workspaceRoot,
            projects = orderedRoots.associateWith { accumulators.getValue(it).build() },
        )
    }

    private fun orderedRoots(): List<Path> = roots.sortedWith(compareBy<Path>({ it.nameCount }, { it.toString() }))

    private fun isRootTopologyFile(path: Path): Boolean =
        path.fileName?.toString() == "dune-project" || path.fileName?.toString() == "dune-workspace"

    private data class IndexedDuneFile(
        val root: Path,
        val directory: Path,
        val metadata: DuneFileMetadata,
    )
}

private class MutableDuneProjectModel(private val root: Path) {
    val packages = linkedSetOf<String>()
    private val executables = mutableListOf<DuneTarget>()
    private val libraries = mutableListOf<DuneTarget>()
    private val tests = mutableListOf<DuneTarget>()
    private val sourceRoots = linkedSetOf<Path>()

    fun add(directory: Path, metadata: DuneFileMetadata) {
        executables += metadata.executables
        libraries += metadata.libraries
        tests += metadata.tests
        if (metadata.hasSourceStanza) sourceRoots.add(directory)
    }

    fun build(): DuneProjectModel = DuneProjectModel(
        root = root,
        executables = executables.distinctBy(DuneTarget::target),
        libraries = libraries.distinctBy { it.directory to it.name },
        tests = tests.distinctBy { it.directory to it.name },
        packages = packages.toList(),
        sourceRoots = sourceRoots.toList(),
    )
}

internal data class DuneFileMetadata(
    val executables: List<DuneTarget> = emptyList(),
    val libraries: List<DuneTarget> = emptyList(),
    val tests: List<DuneTarget> = emptyList(),
    val hasSourceStanza: Boolean = false,
)

internal fun parseDuneFileMetadata(text: String, root: Path, duneFile: Path): DuneFileMetadata {
    val normalizedRoot = root.toAbsolutePath().normalize()
    val directory = duneFile.toAbsolutePath().normalize().parent ?: normalizedRoot
    val executables = mutableListOf<DuneTarget>()
    val libraries = mutableListOf<DuneTarget>()
    val tests = mutableListOf<DuneTarget>()
    var hasSourceStanza = false

    for (form in parseSExpressions(text).filterIsInstance<SList>()) {
        val head = form.values.firstOrNull() as? SAtom ?: continue
        when (head.value) {
            "executable", "executables" -> {
                hasSourceStanza = true
                val plural = head.value == "executables"
                val names = form.field(if (plural) "names" else "name")?.atomsAfterHead().orEmpty()
                val publicNames = form.field(if (plural) "public_names" else "public_name")
                    ?.atomsAfterHead()
                    .orEmpty()
                names.forEachIndexed { index, name ->
                    if (name.value.contains("%{")) return@forEachIndexed
                    val publicName = publicNames.getOrNull(index)?.value
                        ?.takeUnless { it == "-" || it.contains("%{") }
                        .orEmpty()
                    executables += DuneTarget(
                        kind = DuneTargetKind.EXECUTABLE,
                        name = name.value,
                        target = localExecutableTarget(normalizedRoot, directory, name.value),
                        publicName = publicName,
                        directory = directory,
                        sourceOffset = if (plural) name.range.first else head.range.first,
                        sourceLength = if (plural) name.range.count() else head.range.count(),
                    )
                }
            }

            "library" -> {
                hasSourceStanza = true
                val name = form.field("name")?.atomsAfterHead()?.firstOrNull() ?: continue
                libraries += DuneTarget(
                    kind = DuneTargetKind.LIBRARY,
                    name = name.value,
                    publicName = form.field("public_name")?.atomValuesAfterHead()?.firstOrNull().orEmpty(),
                    directory = directory,
                    sourceOffset = head.range.first,
                    sourceLength = head.range.count(),
                )
            }

            "test", "tests" -> {
                hasSourceStanza = true
                val plural = head.value == "tests"
                val names = form.field(if (plural) "names" else "name")?.atomsAfterHead().orEmpty()
                names.forEach { name ->
                    tests += DuneTarget(
                        kind = DuneTargetKind.TEST,
                        name = name.value,
                        directory = directory,
                        sourceOffset = if (plural) name.range.first else head.range.first,
                        sourceLength = if (plural) name.range.count() else head.range.count(),
                    )
                }
            }

            "cram" -> {
                hasSourceStanza = true
                tests += DuneTarget(
                    kind = DuneTargetKind.TEST,
                    name = directory.fileName?.toString().orEmpty().ifBlank { "." },
                    directory = directory,
                    sourceOffset = head.range.first,
                    sourceLength = head.range.count(),
                )
            }
        }
    }

    return DuneFileMetadata(executables, libraries, tests, hasSourceStanza)
}

private fun localExecutableTarget(root: Path, directory: Path, name: String): String {
    val relativeDirectory = root.relativize(directory).joinToString("/")
    val prefix = if (relativeDirectory.isEmpty()) "./" else "./$relativeDirectory/"
    return "$prefix$name.exe"
}

private val IGNORED_DIRECTORIES = setOf("_build", "_opam", ".git", ".idea")
