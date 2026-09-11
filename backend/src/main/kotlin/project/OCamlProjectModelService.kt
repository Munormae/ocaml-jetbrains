@file:Suppress("UnstableApiUsage")

package dev.munormae.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.ui.EditorNotifications
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.backend.workspace.toVirtualFileUrl
import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.jps.entities.ExcludeUrlEntity
import com.intellij.platform.workspace.jps.entities.InheritedSdkDependency
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.ModuleSourceDependency
import com.intellij.platform.workspace.jps.entities.SourceRootEntity
import com.intellij.platform.workspace.jps.entities.SourceRootTypeId
import com.intellij.platform.workspace.storage.entities
import com.intellij.workspaceModel.ide.legacyBridge.LegacyBridgeJpsEntitySourceFactory
import java.nio.file.Files
import java.nio.file.Path

internal data class OCamlProjectLayout(
    val root: Path,
    val sourceRoots: List<Path>,
    val testRoots: List<Path>,
    val excludedRoots: List<Path>,
    val isOCamlProject: Boolean,
)

internal fun deriveOCamlProjectLayout(root: Path): OCamlProjectLayout {
    val normalizedRoot = root.toAbsolutePath().normalize()
    val sourceRoots = SOURCE_DIRECTORY_NAMES
        .map(normalizedRoot::resolve)
        .filter(Files::isDirectory)
    val testRoots = TEST_DIRECTORY_NAMES
        .map(normalizedRoot::resolve)
        .filter(Files::isDirectory)
    val hasTopLevelSource = runCatching {
        Files.list(normalizedRoot).use { files ->
            files.anyMatch { file ->
                Files.isRegularFile(file) && file.fileName.toString().let { it.endsWith(".ml") || it.endsWith(".mli") }
            }
        }
    }.getOrDefault(false)
    val hasProjectMarker = PROJECT_MARKERS.any { Files.exists(normalizedRoot.resolve(it)) }
    val isOCamlProject = hasProjectMarker || hasTopLevelSource || sourceRoots.isNotEmpty() || testRoots.isNotEmpty()
    return OCamlProjectLayout(
        root = normalizedRoot,
        sourceRoots = sourceRoots.ifEmpty { if (hasTopLevelSource) listOf(normalizedRoot) else emptyList() },
        testRoots = testRoots,
        excludedRoots = EXCLUDED_DIRECTORY_NAMES.map(normalizedRoot::resolve),
        isOCamlProject = isOCamlProject,
    )
}

class OCamlProjectModelActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        OCamlProjectModelService.getInstance(project).ensureConfigured()
    }
}

class OCamlProjectModelService(private val project: Project) {
    fun ensureConfigured() {
        val rootPath = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() } ?: return
        val layout = deriveOCamlProjectLayout(rootPath)
        if (!layout.isOCamlProject) return
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(layout.root) ?: return
        ensureOCamlModule(project, root, project.name)
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) EditorNotifications.getInstance(project).updateAllNotifications()
        }
    }

    companion object {
        fun getInstance(project: Project): OCamlProjectModelService = project.service()
    }
}

internal fun ensureOCamlModule(
    project: Project,
    root: VirtualFile,
    requestedName: String,
    additionalSourceRoots: List<Path> = emptyList(),
) {
    val workspaceModel = WorkspaceModel.getInstance(project)
    val urlManager = workspaceModel.getVirtualFileUrlManager()
    val rootUrl = root.toVirtualFileUrl(urlManager)
    val layout = deriveOCamlProjectLayout(Path.of(root.path))
    if (!layout.isOCamlProject && layout.sourceRoots.isEmpty() && layout.testRoots.isEmpty()) return
    val usedNames = workspaceModel.currentSnapshot.entities<ModuleEntity>().mapTo(mutableSetOf()) { it.name }
    val moduleName = uniqueModuleName(requestedName.ifBlank { root.name }, usedNames)
    val existingModule = workspaceModel.currentSnapshot.entities<ModuleEntity>().firstOrNull { module ->
        module.contentRoots.any { it.url == rootUrl }
    }
    val entitySource = existingModule?.entitySource ?: LegacyBridgeJpsEntitySourceFactory.getInstance(project)
        .createEntitySourceForModule(rootUrl, null)

    fun pathUrl(path: Path) = urlManager.getOrCreateFromUrl(VfsUtilCore.pathToUrl(path.toString()))

    val normalizedTestRoots = layout.testRoots
        .map(Path::toAbsolutePath)
        .map(Path::normalize)
        .toSet()
    val sourceRoots = (layout.sourceRoots + additionalSourceRoots)
        .map(Path::toAbsolutePath)
        .map(Path::normalize)
        .distinct()
        .filterNot(normalizedTestRoots::contains)
        .filter(Files::isDirectory)
        .map { sourceRoot ->
            SourceRootEntity(pathUrl(sourceRoot), SOURCE_ROOT_TYPE, entitySource)
        } + normalizedTestRoots.map { testRoot ->
        SourceRootEntity(pathUrl(testRoot), TEST_ROOT_TYPE, entitySource)
    }
    val excludedRoots = layout.excludedRoots.map { excludedRoot ->
        ExcludeUrlEntity(pathUrl(excludedRoot), entitySource)
    }
    val contentRoot = ContentRootEntity(rootUrl, emptyList(), entitySource) {
        this.sourceRoots = sourceRoots
        this.excludedUrls = excludedRoots
    }
    val module = ModuleEntity(
        name = moduleName,
        dependencies = listOf(InheritedSdkDependency, ModuleSourceDependency),
        entitySource = entitySource,
    ) {
        contentRoots = listOf(contentRoot)
    }

    runProjectWriteAction {
        workspaceModel.updateProjectModel("Configure OCaml module") { storage ->
            val currentContentRoot = storage.entities<ContentRootEntity>().firstOrNull { it.url == rootUrl }
            if (currentContentRoot == null) {
                storage.addEntity(module)
            } else {
                storage.modifyEntity(ContentRootEntity.Builder::class.java, currentContentRoot) {
                    this.sourceRoots = (this.sourceRoots + sourceRoots).distinctBy { it.url }
                    this.excludedUrls = (this.excludedUrls + excludedRoots).distinctBy { it.url }
                }
            }
        }
    }
}

internal fun runProjectWriteAction(action: () -> Unit) {
    val application = ApplicationManager.getApplication()
    val writeAction = Runnable { application.runWriteAction(action) }
    if (application.isDispatchThread) writeAction.run() else application.invokeAndWait(writeAction)
}

private fun uniqueModuleName(baseName: String, usedNames: Set<String>): String =
    generateSequence(1) { it + 1 }
        .map { index -> if (index == 1) baseName else "$baseName ($index)" }
        .first { it !in usedNames }

private val SOURCE_DIRECTORY_NAMES = listOf("lib", "bin", "src")
private val TEST_DIRECTORY_NAMES = listOf("test", "tests")
private val EXCLUDED_DIRECTORY_NAMES = listOf("_build", "_opam")
private val PROJECT_MARKERS = listOf("dune-project", "dune-workspace", "_opam")
private val SOURCE_ROOT_TYPE = SourceRootTypeId("java-source")
private val TEST_ROOT_TYPE = SourceRootTypeId("java-test")
