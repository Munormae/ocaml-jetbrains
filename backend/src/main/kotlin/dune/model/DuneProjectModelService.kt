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
import dev.munormae.dune.findDuneRoot
import dev.munormae.dune.external.DUNE_SYSTEM_ID
import dev.munormae.dune.external.DuneExternalSystemSettings
import dev.munormae.dune.run.DuneCommand
import dev.munormae.dune.run.LatestRefreshGeneration
import dev.munormae.dune.run.discoverDuneRunConfigurations
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
                    if (events.any { isDuneModelPath(project.basePath, it.path) }) requestRefresh()
                }
            },
        )
        requestRefresh(delayMs = 0)
    }

    fun requestRefresh(delayMs: Long = MODEL_REFRESH_DELAY_MS) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        synchronized(schedulingLock) {
            val generation = generations.next()
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
        val root = findDuneRoot(project.basePath)
            ?: return DuneProjectModelState.NotLoaded.also { applyState(generation, it) }
        val loaded = runCatching { DuneProjectModelState.Ready(loadModel(root)) }
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
        val root = findDuneRoot(project.basePath)
        if (root == null) {
            applyState(generation, DuneProjectModelState.NotLoaded)
            return
        }
        val state = runCatching { DuneProjectModelState.Ready(loadModel(root)) }
            .getOrElse { exception ->
                DuneProjectModelState.Failed(exception.message ?: exception.javaClass.simpleName)
            }
        applyState(generation, state)
    }

    private fun loadModel(root: Path): DuneProjectModel {
        val metadata = discoverDuneSourceMetadata(root)
        val runConfigurations = discoverDuneRunConfigurations(project, root)
        val executables = runConfigurations
            .filter { it.command == DuneCommand.EXEC }
            .map { spec ->
                DuneTarget(
                    kind = DuneTargetKind.EXECUTABLE,
                    name = duneExecutableModelName(spec.target, spec.legacyTarget),
                    target = spec.target,
                    publicName = spec.legacyTarget,
                    directory = root.resolve(spec.target.removePrefix("./")).parent ?: root,
                )
            }
        return DuneProjectModel(
            root = root.toAbsolutePath().normalize(),
            executables = executables,
            libraries = metadata.libraries,
            tests = metadata.tests,
            packages = metadata.packages,
            sourceRoots = metadata.sourceRoots,
        )
    }

    private fun applyState(generation: Long, state: DuneProjectModelState) {
        if (!generations.isCurrent(generation) || project.isDisposed) return
        mutableState.value = state
        if (state is DuneProjectModelState.Ready) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && generations.isCurrent(generation)) {
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(state.model.root)?.let { root ->
                        ensureOCamlModule(project, root, project.name, state.model.sourceRoots)
                    }
                    if (DuneExternalSystemSettings.getInstance(project).ensureLinked(state.model.root)) {
                        ExternalSystemUtil.refreshProject(
                            state.model.root.toString(),
                            ImportSpecBuilder(project, DUNE_SYSTEM_ID),
                        )
                    }
                    provisionDuneRunConfigurations(project, state.model.runConfigurations)
                }
            }
        }
    }

    override fun dispose() {
        synchronized(schedulingLock) {
            generations.invalidate()
            scheduledRefresh?.cancel(false)
            scheduledRefresh = null
        }
    }

    companion object {
        fun getInstance(project: Project): DuneProjectModelService = project.service()
    }
}

internal fun duneExecutableModelName(target: String, publicName: String): String =
    publicName.ifBlank {
        runCatching { Path.of(target).fileName?.toString() }
            .getOrNull()
            .orEmpty()
            .ifBlank { target.substringAfterLast('/') }
            .removeSuffix(".exe")
    }

private const val MODEL_REFRESH_DELAY_MS = 600L
