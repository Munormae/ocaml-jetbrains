package dev.munormae.dune.run

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.munormae.dune.model.DuneProjectModelService
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

class DuneRunConfigurationProvisioningActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        DuneProjectModelService.getInstance(project).start()
    }
}

class DuneRunConfigurationProvisioningService(private val project: Project) : Disposable {
    fun start() {
        DuneProjectModelService.getInstance(project).start()
    }

    fun requestRefresh(delayMs: Long = MODEL_REFRESH_DELAY_MS) {
        DuneProjectModelService.getInstance(project).requestRefresh(delayMs)
    }

    override fun dispose() {
        Unit
    }

    companion object {
        fun getInstance(project: Project): DuneRunConfigurationProvisioningService = project.service()
    }
}

internal class LatestRefreshGeneration {
    private val value = AtomicLong()

    fun next(): Long = value.incrementAndGet()

    fun isCurrent(generation: Long): Boolean = value.get() == generation

    fun invalidate() {
        value.incrementAndGet()
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
