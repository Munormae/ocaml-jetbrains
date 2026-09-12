package dev.munormae.dune.model

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.BulkFileListenerBackgroundable
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.concurrency.AppExecutorUtil
import dev.munormae.OCamlBundle
import dev.munormae.dune.DuneWatchService
import dev.munormae.dune.external.DUNE_SYSTEM_ID
import dev.munormae.dune.external.DuneExternalSystemSettings
import dev.munormae.dune.run.LatestRefreshGeneration
import dev.munormae.dune.run.describeDuneWorkspace
import dev.munormae.dune.run.isDuneModelPath
import dev.munormae.dune.run.provisionDuneRunConfigurations
import dev.munormae.project.ensureOCamlModule
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DuneProjectModelService(private val project: Project) : Disposable {
    private val started = AtomicBoolean()
    private val generations = LatestRefreshGeneration()
    private val schedulingLock = Any()
    private var scheduledRefresh: ScheduledFuture<*>? = null
    private val pendingChangedPaths = linkedSetOf<Path>()
    private var sourceIndex: DuneWorkspaceSourceIndex? = null
    private var sourceIndexRoot: Path? = null
    @Volatile
    private var lastReadyWorkspace: DuneWorkspaceModel? = null
    private val mutableState = MutableStateFlow<DuneProjectModelState>(DuneProjectModelState.NotLoaded)
    val stateFlow: StateFlow<DuneProjectModelState> = mutableState

    val state: DuneProjectModelState
        get() = mutableState.value

    fun start() {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        if (!started.compareAndSet(false, true)) return
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES_BG,
            object : BulkFileListenerBackgroundable {
                override fun after(events: List<VFileEvent>) {
                    val changedPaths = events.asSequence()
                        .filter { isDuneModelPath(project.basePath, it.path) }
                        .mapNotNull { event -> runCatching { Path.of(event.path) }.getOrNull() }
                        .toSet()
                    if (changedPaths.isNotEmpty()) requestRefresh(changedPaths = changedPaths)
                }
            },
        )
        requestRefresh(delayMs = 0)
    }

    fun requestRefresh(
        delayMs: Long = MODEL_REFRESH_DELAY_MS,
        changedPaths: Set<Path> = emptySet(),
    ) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        synchronized(schedulingLock) {
            val generation = generations.next()
            pendingChangedPaths.addAll(changedPaths)
            scheduledRefresh?.cancel(false)
            mutableState.value = DuneProjectModelState.Loading
            scheduledRefresh = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                { refreshNow(generation) },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    fun refreshSynchronously(): DuneProjectModelState {
        val generation = generations.next()
        mutableState.value = DuneProjectModelState.Loading
        if (!TrustedProjects.isProjectTrusted(project)) {
            return DuneProjectModelState.Failed(OCamlBundle.message("status.blocked")).also {
                applyState(generation, it)
            }
        }
        val workspaceRoot = workspaceRoot()
            ?: return DuneProjectModelState.NotLoaded.also { applyState(generation, it) }
        val loaded = runCatching { loadWorkspace(workspaceRoot, emptySet()).toState() }
            .getOrElse { exception ->
                DuneProjectModelState.Failed(exception.message ?: exception.javaClass.simpleName)
            }
        applyState(generation, loaded)
        return loaded
    }

    private fun refreshNow(generation: Long) {
        if (!generations.isCurrent(generation) || project.isDisposed) return
        if (!TrustedProjects.isProjectTrusted(project)) {
            applyState(generation, DuneProjectModelState.Failed(OCamlBundle.message("status.blocked")))
            return
        }
        val workspaceRoot = workspaceRoot()
        if (workspaceRoot == null) {
            applyState(generation, DuneProjectModelState.NotLoaded)
            return
        }
        val changedPaths = synchronized(schedulingLock) {
            pendingChangedPaths.toSet().also { pendingChangedPaths.clear() }
        }
        val state = runCatching { loadWorkspace(workspaceRoot, changedPaths).toState() }
            .getOrElse { exception ->
                DuneProjectModelState.Failed(exception.message ?: exception.javaClass.simpleName)
            }
        applyState(generation, state)
    }

    private fun workspaceRoot(): Path? = project.basePath
        ?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }

    private fun loadWorkspace(root: Path, changedPaths: Set<Path>): DuneWorkspaceModel {
        val index = synchronized(schedulingLock) {
            if (sourceIndex == null || sourceIndexRoot != root) {
                sourceIndex = DuneWorkspaceSourceIndex(root)
                sourceIndexRoot = root
                lastReadyWorkspace = null
            }
            requireNotNull(sourceIndex)
        }
        val sourceWorkspace = index.refresh(changedPaths)
        val rootsToDescribe = rootsNeedingDuneDescribe(sourceWorkspace.roots, changedPaths)
        val previousWorkspace = lastReadyWorkspace
        val pauseLease = if (rootsToDescribe.isNotEmpty()) DuneWatchService.getInstance(project).acquirePause() else null
        val projects = try {
            sourceWorkspace.projects.mapValues { (duneRoot, sourceModel) ->
                val previousModel = previousWorkspace?.projects?.get(duneRoot)
                if (duneRoot !in rootsToDescribe && previousModel != null) return@mapValues previousModel
                val describedExecutables = describeDuneWorkspace(project, duneRoot)
                    .orEmpty()
                    .map { spec ->
                        DuneTarget(
                            kind = DuneTargetKind.EXECUTABLE,
                            name = duneExecutableModelName(spec.target, spec.legacyTarget),
                            target = spec.target,
                            publicName = spec.legacyTarget,
                            directory = duneRoot.resolve(spec.target.removePrefix("./")).parent ?: duneRoot,
                        )
                    }
                val sourceExecutables = sourceModel.executables.associateBy(DuneTarget::target)
                val executables = (
                    describedExecutables.map { sourceExecutables[it.target] ?: it } + sourceExecutables.values
                ).distinctBy(DuneTarget::target)
                sourceModel.copy(executables = executables)
            }
        } finally {
            pauseLease?.close()
        }
        return sourceWorkspace.copy(projects = projects)
    }

    private fun applyState(generation: Long, state: DuneProjectModelState) {
        if (!generations.isCurrent(generation) || project.isDisposed) return
        mutableState.value = state
        lastReadyWorkspace = (state as? DuneProjectModelState.Ready)?.workspace
        DuneWatchService.getInstance(project).refresh()
        if (state is DuneProjectModelState.Ready) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && generations.isCurrent(generation)) {
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(state.workspace.workspaceRoot)?.let { root ->
                        ensureOCamlModule(project, root, project.name)
                    }
                    val addedExternalRoots = DuneExternalSystemSettings.getInstance(project)
                        .synchronizeLinked(state.workspace.workspaceRoot, state.workspace.roots)
                    state.workspace.projects.values.forEach { model ->
                        if (model.root in addedExternalRoots) {
                            ExternalSystemUtil.refreshProject(
                                model.root.toString(),
                                ImportSpecBuilder(project, DUNE_SYSTEM_ID),
                            )
                        }
                    }
                    provisionDuneRunConfigurations(project, state.workspace.runConfigurations)
                }
            }
        } else if (state == DuneProjectModelState.NotLoaded) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && generations.isCurrent(generation)) {
                    workspaceRoot()?.let { root ->
                        DuneExternalSystemSettings.getInstance(project).synchronizeLinked(root, emptyList())
                    }
                    provisionDuneRunConfigurations(project, emptyList())
                }
            }
        }
    }

    override fun dispose() {
        synchronized(schedulingLock) {
            generations.invalidate()
            scheduledRefresh?.cancel(false)
            scheduledRefresh = null
            pendingChangedPaths.clear()
            sourceIndex = null
            sourceIndexRoot = null
            lastReadyWorkspace = null
        }
    }

    companion object {
        fun getInstance(project: Project): DuneProjectModelService = project.service()
    }
}

internal fun rootsNeedingDuneDescribe(roots: List<Path>, changedPaths: Set<Path>): Set<Path> {
    if (changedPaths.isEmpty() || changedPaths.any {
            it.fileName?.toString() == "dune-project" || it.fileName?.toString() == "dune-workspace"
        }
    ) return roots.toSet()
    return changedPaths.mapNotNull { changed ->
        val path = changed.toAbsolutePath().normalize()
        roots.filter(path::startsWith).maxByOrNull(Path::getNameCount)
    }.toSet()
}

private fun DuneWorkspaceModel.toState(): DuneProjectModelState =
    if (projects.isEmpty()) DuneProjectModelState.NotLoaded else DuneProjectModelState.Ready(this)

internal fun duneExecutableModelName(target: String, publicName: String): String =
    publicName.ifBlank {
        runCatching { Path.of(target).fileName?.toString() }
            .getOrNull()
            .orEmpty()
            .ifBlank { target.substringAfterLast('/') }
            .removeSuffix(".exe")
    }

private const val MODEL_REFRESH_DELAY_MS = 600L
