package dev.munormae.dune.run

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListenerBackgroundable
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.concurrency.AppExecutorUtil
import dev.munormae.dune.findDuneRoot
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DuneRunConfigurationProvisioningActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        DuneRunConfigurationProvisioningService.getInstance(project).start()
    }
}

class DuneRunConfigurationProvisioningService(private val project: Project) : Disposable {
    private val started = AtomicBoolean()
    private val schedulingLock = Any()
    private var scheduledRefresh: ScheduledFuture<*>? = null

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
            scheduledRefresh?.cancel(false)
            scheduledRefresh = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                ::refreshNow,
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun refreshNow() {
        if (project.isDisposed || !TrustedProjects.isProjectTrusted(project)) return
        val duneRoot = findDuneRoot(project.basePath)
        val specs = duneRoot?.let { discoverDuneRunConfigurations(project, it) }.orEmpty()
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed && TrustedProjects.isProjectTrusted(project)) {
                provisionDuneRunConfigurations(project, specs)
            }
        }
    }

    override fun dispose() {
        synchronized(schedulingLock) {
            scheduledRefresh?.cancel(false)
            scheduledRefresh = null
        }
    }

    companion object {
        fun getInstance(project: Project): DuneRunConfigurationProvisioningService = project.service()
    }
}

internal fun isDuneModelPath(projectBasePath: String?, eventPath: String): Boolean {
    val fileName = runCatching { Path.of(eventPath).fileName?.toString() }.getOrNull() ?: return false
    if (fileName !in DUNE_MODEL_FILE_NAMES) return false
    val projectRoot = projectBasePath?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
        ?: return false
    val changedPath = runCatching { Path.of(eventPath).toAbsolutePath().normalize() }.getOrNull() ?: return false
    if (!changedPath.startsWith(projectRoot)) return false
    return projectRoot.relativize(changedPath).none { it.toString() in IGNORED_MODEL_DIRECTORIES }
}

private const val MODEL_REFRESH_DELAY_MS = 600L
private val DUNE_MODEL_FILE_NAMES = setOf("dune", "dune-project", "dune-workspace")
private val IGNORED_MODEL_DIRECTORIES = setOf("_build", "_opam", ".git", ".idea")
