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
import com.intellij.util.concurrency.AppExecutorUtil
import dev.munormae.settings.OCamlProjectSettings
import dev.munormae.settings.OCamlWorkspaceSettings
import dev.munormae.toolchain.OCamlToolchainDetectionService
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

class DuneWatchService(private val project: Project) : Disposable {
    private val coordinator = DuneWatchCoordinator(
        AppExecutorUtil.createBoundedApplicationPoolExecutor("OCaml Dune watch supervisor", 1),
    )
    private val disposed = AtomicBoolean()
    private val runningWatches = ConcurrentHashMap<Path, RunningWatch>()
    private val pauseControllers = RootScopedPauseController(
        onFirstAcquire = ::pauseWatch,
        onLastRelease = ::resumeWatch,
    )

    fun refresh() {
        if (disposed.get()) return
        coordinator.dispatch(::refreshOnWorker)
    }

    private fun refreshOnWorker() {
        if (disposed.get() || project.isDisposed) {
            stopAll()
            return
        }
        val state = OCamlProjectSettings.getInstance(project).state
        val workspace = OCamlWorkspaceSettings.getInstance(project).state
        val roots = (dev.munormae.dune.model.DuneProjectModelService.getInstance(project).state
            as? dev.munormae.dune.model.DuneProjectModelState.Ready)
            ?.workspace
            ?.roots
            .orEmpty()
            .ifEmpty { listOfNotNull(findDuneRoot(project.basePath)) }
        if (!state.lspEnabled || !workspace.manageDuneWatch || roots.isEmpty() || !TrustedProjects.isProjectTrusted(project)) {
            stopAll()
            return
        }
        if (OCamlToolchainDetectionService.getInstance(project).status.selectedEnvironment?.dune?.isAvailable != true) {
            stopAll()
            return
        }

        val desired = roots.associateWith { root ->
            try {
                createDuneWatchCommandLine(project, root)
            } catch (exception: ExecutionException) {
                LOG.debug("Dune watch is waiting for a configured OCaml environment", exception)
                null
            }
        }
        runningWatches.keys.filterNot(desired::containsKey).forEach(::stop)
        for ((root, commandLine) in desired) {
            if (commandLine == null || pauseControllers.isPaused(root)) {
                stop(root)
                continue
            }
            val current = runningWatches[root]
            if (current != null && current.command == commandLine.commandLineString && !current.handler.isProcessTerminated) {
                continue
            }
            if (stop(root)) start(root, commandLine)
        }
    }

    fun acquirePause(root: Path): AutoCloseable = pauseControllers.acquire(root)

    fun isWatching(root: Path): Boolean = runningWatches[root.toAbsolutePath().normalize()]
        ?.handler
        ?.isProcessTerminated == false

    private fun pauseWatch(root: Path) {
        if (!disposed.get()) coordinator.dispatchAndWait {
            if (!stop(root)) throw ExecutionException("Unable to pause Dune watch in $root")
        }
    }

    private fun resumeWatch(@Suppress("UNUSED_PARAMETER") root: Path) {
        if (!project.isDisposed) refresh()
    }

    private fun start(root: Path, commandLine: GeneralCommandLine) {
        try {
            val handler = DuneWatchProcessHandler(commandLine)
            val watch = RunningWatch(root, commandLine.commandLineString, handler)
            runningWatches[root] = watch
            handler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    runningWatches.remove(root, watch)
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

    private fun stop(root: Path): Boolean {
        val watch = runningWatches[root] ?: return true
        return runCatching {
            terminateWatch(watch)
            runningWatches.remove(root, watch)
            true
        }.getOrElse { exception ->
            LOG.warn("Unable to stop Dune watch in $root", exception)
            false
        }
    }

    private fun stopAll(): Boolean = runningWatches.keys.toList().map(::stop).all { it }

    private fun terminateWatch(watch: RunningWatch) {
        watch.stopRequested = true
        if (
            !terminateDuneWatchProcess(
                process = watch.handler,
                gracefulTimeoutMillis = WATCH_STOP_TIMEOUT_MS,
                forceKillTimeoutMillis = WATCH_FORCE_KILL_TIMEOUT_MS,
                onGracefulTimeout = {
                    LOG.warn("Dune watch did not stop gracefully in ${watch.root}; killing it")
                },
            )
        ) {
            throw ExecutionException("Unable to stop Dune watch in ${watch.root}")
        }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        runCatching { coordinator.dispatch { stopAll() } }.onFailure { exception ->
            LOG.warn("Unable to terminate Dune watch while disposing the project", exception)
        }
        coordinator.close()
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

internal class DuneWatchCoordinator(private val executor: ExecutorService) : AutoCloseable {
    private val closed = AtomicBoolean()

    fun dispatch(action: () -> Unit): Future<*> {
        if (closed.get()) return java.util.concurrent.CompletableFuture.completedFuture(Unit)
        return try {
            executor.submit(action)
        } catch (exception: java.util.concurrent.RejectedExecutionException) {
            if (closed.get()) java.util.concurrent.CompletableFuture.completedFuture(Unit) else throw exception
        }
    }

    fun dispatchAndWait(action: () -> Unit) {
        try {
            dispatch(action).get()
        } catch (exception: java.util.concurrent.ExecutionException) {
            val cause = exception.cause ?: exception
            if (cause is RuntimeException) throw cause
            if (cause is Error) throw cause
            throw RuntimeException(cause)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) executor.shutdown()
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

internal class RootScopedPauseController(
    private val onFirstAcquire: (Path) -> Unit,
    private val onLastRelease: (Path) -> Unit,
) {
    private val controllers = ConcurrentHashMap<Path, ReferenceCountedPauseController>()

    fun acquire(root: Path): AutoCloseable {
        val normalizedRoot = root.toAbsolutePath().normalize()
        return controllers.computeIfAbsent(normalizedRoot) {
            ReferenceCountedPauseController(
                onFirstAcquire = { onFirstAcquire(normalizedRoot) },
                onLastRelease = { onLastRelease(normalizedRoot) },
            )
        }.acquire()
    }

    fun isPaused(root: Path): Boolean = controllers[root.toAbsolutePath().normalize()]?.isPaused == true
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

internal fun createDuneWatchCommandLine(project: Project, root: Path): GeneralCommandLine =
    createDuneCommandLine(project, root, listOf("build", "--watch"))

internal interface DuneWatchProcessControl {
    fun isTerminated(): Boolean
    fun isTerminating(): Boolean
    fun requestTermination()
    fun waitFor(timeoutInMilliseconds: Long): Boolean
    fun forceKill()
}

internal fun terminateDuneWatchProcess(
    process: DuneWatchProcessControl,
    gracefulTimeoutMillis: Long,
    forceKillTimeoutMillis: Long,
    onGracefulTimeout: () -> Unit = {},
): Boolean {
    if (process.isTerminated()) return true
    if (!process.isTerminating()) process.requestTermination()
    if (process.waitFor(gracefulTimeoutMillis)) return true
    onGracefulTimeout()
    process.forceKill()
    return process.waitFor(forceKillTimeoutMillis)
}

private class DuneWatchProcessHandler(commandLine: GeneralCommandLine) :
    OSProcessHandler(commandLine),
    DuneWatchProcessControl {
    override fun readerOptions(): BaseOutputReader.Options = BaseOutputReader.Options.forMostlySilentProcess()
    override fun isTerminated(): Boolean = isProcessTerminated
    override fun isTerminating(): Boolean = isProcessTerminating
    override fun requestTermination() = destroyProcess()

    override fun forceKill() {
        killProcessTree(process)
    }
}
