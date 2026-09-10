package dev.munormae.dune

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.io.BaseOutputReader
import dev.munormae.settings.OCamlProjectSettings
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class DuneWatchService(private val project: Project) : Disposable {
    private var runningWatch: RunningWatch? = null
    private val pauseController = ReferenceCountedPauseController(
        onFirstAcquire = ::pauseWatch,
        onLastRelease = ::resumeWatch,
    )

    @Synchronized
    fun refresh() {
        val state = OCamlProjectSettings.getInstance(project).state
        val root = findDuneRoot(project.basePath)
        if (!state.lspEnabled || !state.duneWatchEnabled || root == null || !TrustedProjects.isProjectTrusted(project)) {
            if (!pauseController.isPaused) stop()
            return
        }
        if (pauseController.isPaused) return

        val commandLine = createDuneWatchCommandLine(
            root = root,
            useOpam = state.useOpam,
            opamExecutable = state.opamExecutable,
            opamSwitch = state.opamSwitch,
            duneExecutable = state.duneExecutable,
        )
        val current = runningWatch
        if (
            current != null &&
            current.root == root &&
            current.command == commandLine.commandLineString &&
            !current.handler.isProcessTerminated
        ) {
            return
        }

        stop()
        start(root, commandLine)
    }

    fun acquirePause(): AutoCloseable = pauseController.acquire()

    private fun pauseWatch() {
        val watch = synchronized(this) {
            val current = runningWatch ?: return
            runningWatch = null
            current.stopRequested = true
            current
        }
        val handler = watch.handler
        if (!handler.isProcessTerminated && !handler.isProcessTerminating) {
            handler.destroyProcess()
        }
        if (!handler.waitFor(WATCH_STOP_TIMEOUT_MS)) {
            LOG.warn("Dune watch did not stop gracefully in ${watch.root}; killing it")
            handler.forceKill()
            if (!handler.waitFor(WATCH_FORCE_KILL_TIMEOUT_MS)) {
                throw ExecutionException("Unable to stop Dune watch in ${watch.root}")
            }
        }
    }

    private fun resumeWatch() {
        if (!project.isDisposed) refresh()
    }

    @Synchronized
    private fun start(root: Path, commandLine: GeneralCommandLine) {
        try {
            val handler = DuneWatchProcessHandler(commandLine)
            val watch = RunningWatch(root, commandLine.commandLineString, handler)
            runningWatch = watch
            handler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    synchronized(this@DuneWatchService) {
                        if (runningWatch === watch) runningWatch = null
                    }
                    if (!watch.stopRequested && event.exitCode != 0 && !project.isDisposed) {
                        LOG.warn("Dune watch stopped with exit code ${event.exitCode} in $root")
                    }
                }
            })
            handler.startNotify()
            LOG.info("Started Dune watch in $root: ${commandLine.commandLineString}")
        } catch (exception: ExecutionException) {
            LOG.warn("Unable to start Dune watch in $root", exception)
        }
    }

    @Synchronized
    private fun stop() {
        val watch = runningWatch ?: return
        runningWatch = null
        watch.stopRequested = true
        if (!watch.handler.isProcessTerminated && !watch.handler.isProcessTerminating) {
            watch.handler.destroyProcess()
        }
    }

    override fun dispose() {
        stop()
    }

    private data class RunningWatch(
        val root: Path,
        val command: String,
        val handler: DuneWatchProcessHandler,
        var stopRequested: Boolean = false,
    )

    companion object {
        private const val WATCH_STOP_TIMEOUT_MS = 5_000L
        private const val WATCH_FORCE_KILL_TIMEOUT_MS = 2_000L
        private val LOG = Logger.getInstance(DuneWatchService::class.java)

        fun getInstance(project: Project): DuneWatchService = project.service()
    }
}

internal class ReferenceCountedPauseController(
    private val onFirstAcquire: () -> Unit,
    private val onLastRelease: () -> Unit,
) {
    private var leaseCount = 0

    @Volatile
    var isPaused: Boolean = false
        private set

    @Synchronized
    fun acquire(): AutoCloseable {
        leaseCount++
        if (leaseCount == 1) {
            isPaused = true
            try {
                onFirstAcquire()
            } catch (exception: Throwable) {
                leaseCount = 0
                isPaused = false
                throw exception
            }
        }
        return PauseLease(this)
    }

    @Synchronized
    private fun release() {
        check(leaseCount > 0) { "Dune watch pause lease released without a matching acquire" }
        leaseCount--
        if (leaseCount == 0) {
            isPaused = false
            onLastRelease()
        }
    }

    private class PauseLease(private val controller: ReferenceCountedPauseController) : AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true)) controller.release()
        }
    }
}

internal fun createDuneWatchCommandLine(
    root: Path,
    useOpam: Boolean,
    opamExecutable: String?,
    opamSwitch: String?,
    duneExecutable: String?,
): GeneralCommandLine {
    return createDuneCommandLine(
        workingDirectory = root,
        useOpam = useOpam,
        opamExecutable = opamExecutable,
        opamSwitch = opamSwitch,
        duneExecutable = duneExecutable,
        arguments = listOf("build", "--watch"),
    )
}

private class DuneWatchProcessHandler(commandLine: GeneralCommandLine) : OSProcessHandler(commandLine) {
    override fun readerOptions(): BaseOutputReader.Options = BaseOutputReader.Options.forMostlySilentProcess()

    fun forceKill() {
        killProcessTree(process)
    }
}
