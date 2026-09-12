@file:Suppress("UnstableApiUsage")

package dev.munormae.dune.external

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.SimpleJavaParameters
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.ExternalSystemAutoImportAware
import com.intellij.openapi.externalSystem.ExternalSystemManager
import com.intellij.openapi.externalSystem.ExternalSystemUiAware
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ExternalSystemException
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.model.settings.ExternalSystemExecutionSettings
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.externalSystem.service.project.ExternalSystemProjectResolver
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemLocalSettings
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemSettings
import com.intellij.openapi.externalSystem.settings.DelegatingExternalSystemSettingsListener
import com.intellij.openapi.externalSystem.settings.ExternalProjectSettings
import com.intellij.openapi.externalSystem.settings.ExternalSystemSettingsListener
import com.intellij.openapi.externalSystem.task.ExternalSystemTaskManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Pair
import com.intellij.util.Function
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.XCollection
import dev.munormae.OCamlBundle
import dev.munormae.dune.DuneWatchService
import dev.munormae.dune.run.DuneCommand
import dev.munormae.dune.run.discoverDuneRunConfigurations
import dev.munormae.icons.OCamlIcons
import dev.munormae.settings.OCamlWorkspaceSettings
import dev.munormae.toolchain.OCamlEnvironmentKind
import dev.munormae.toolchain.OCamlToolchainDetectionService
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

val DUNE_SYSTEM_ID: ProjectSystemId = ProjectSystemId("DUNE", "Dune")

class DuneExternalProjectSettings : ExternalProjectSettings() {
    override fun clone(): DuneExternalProjectSettings {
        val copy = DuneExternalProjectSettings()
        copyTo(copy)
        return copy
    }
}

interface DuneExternalSettingsListener : ExternalSystemSettingsListener<DuneExternalProjectSettings> {
    companion object {
        @JvmField
        val TOPIC: Topic<DuneExternalSettingsListener> = Topic.create(
            "Dune external settings",
            DuneExternalSettingsListener::class.java,
            Topic.BroadcastDirection.NONE,
        )
    }
}

@Service(Service.Level.PROJECT)
@State(name = "DuneExternalSystemSettings", storages = [Storage("dune.xml")])
class DuneExternalSystemSettings(project: Project) : AbstractExternalSystemSettings<
    DuneExternalSystemSettings,
    DuneExternalProjectSettings,
    DuneExternalSettingsListener,
>(DuneExternalSettingsListener.TOPIC, project), PersistentStateComponent<DuneExternalSystemSettings.SettingsState> {

    class SettingsState : AbstractExternalSystemSettings.State<DuneExternalProjectSettings> {
        private val linkedProjects = TreeSet<DuneExternalProjectSettings>()

        @XCollection(elementTypes = [DuneExternalProjectSettings::class])
        override fun getLinkedExternalProjectsSettings(): Set<DuneExternalProjectSettings> = linkedProjects

        override fun setLinkedExternalProjectsSettings(settings: Set<DuneExternalProjectSettings>?) {
            linkedProjects.clear()
            if (settings != null) linkedProjects.addAll(settings)
        }
    }

    override fun getState(): SettingsState = SettingsState().also(::fillState)

    override fun loadState(state: SettingsState) = super.loadState(state)

    override fun copyExtraSettingsFrom(settings: DuneExternalSystemSettings) = Unit

    override fun checkSettings(old: DuneExternalProjectSettings, current: DuneExternalProjectSettings) = Unit

    override fun subscribe(
        listener: ExternalSystemSettingsListener<DuneExternalProjectSettings>,
        parentDisposable: Disposable,
    ) {
        doSubscribe(DuneDelegatingSettingsListener(listener), parentDisposable)
    }

    fun ensureLinked(root: Path): Boolean {
        val normalized = root.toAbsolutePath().normalize().toString()
        if (getLinkedProjectSettings(normalized) != null) return false
        linkProject(
            DuneExternalProjectSettings().apply {
                setupNewProjectDefault()
                externalProjectPath = normalized
            },
        )
        return true
    }

    companion object {
        fun getInstance(project: Project): DuneExternalSystemSettings = project.service()
    }
}

private class DuneDelegatingSettingsListener(
    delegate: ExternalSystemSettingsListener<DuneExternalProjectSettings>,
) : DelegatingExternalSystemSettingsListener<DuneExternalProjectSettings>(delegate), DuneExternalSettingsListener

@Service(Service.Level.PROJECT)
@State(name = "DuneExternalSystemLocalSettings", storages = [Storage(StoragePathMacros.CACHE_FILE)])
class DuneExternalSystemLocalSettings(project: Project) :
    AbstractExternalSystemLocalSettings<AbstractExternalSystemLocalSettings.State>(
        DUNE_SYSTEM_ID,
        project,
        AbstractExternalSystemLocalSettings.State(),
    ),
    PersistentStateComponent<AbstractExternalSystemLocalSettings.State> {
    companion object {
        fun getInstance(project: Project): DuneExternalSystemLocalSettings = project.service()
    }
}

class DuneExternalExecutionSettings : ExternalSystemExecutionSettings() {
    var trusted: Boolean = false
    var duneAvailable: Boolean = true
    var environmentKind: String = OCamlEnvironmentKind.PATH.name
    var environmentPrefix: String = ""
    var switchName: String = ""
    var opamExecutable: String = "opam"
    var duneExecutable: String = "dune"
}

class DuneExternalSystemManager : ExternalSystemManager<
    DuneExternalProjectSettings,
    DuneExternalSettingsListener,
    DuneExternalSystemSettings,
    DuneExternalSystemLocalSettings,
    DuneExternalExecutionSettings,
>, ExternalSystemUiAware, ExternalSystemAutoImportAware {
    override fun enhanceRemoteProcessing(parameters: SimpleJavaParameters) = Unit

    override fun getSystemId(): ProjectSystemId = DUNE_SYSTEM_ID

    override fun getSettingsProvider(): Function<Project, DuneExternalSystemSettings> =
        Function { project -> DuneExternalSystemSettings.getInstance(project) }

    override fun getLocalSettingsProvider(): Function<Project, DuneExternalSystemLocalSettings> =
        Function { project -> DuneExternalSystemLocalSettings.getInstance(project) }

    override fun getExecutionSettingsProvider(): Function<Pair<Project, String>, DuneExternalExecutionSettings> =
        Function { pair ->
            val project = pair.first
            val environment = OCamlToolchainDetectionService.getInstance(project).status.selectedEnvironment
            val workspace = OCamlWorkspaceSettings.getInstance(project).state
            DuneExternalExecutionSettings().apply {
                trusted = TrustedProjects.isProjectTrusted(project)
                duneAvailable = environment?.dune?.isAvailable == true
                environmentKind = environment?.kind?.name ?: OCamlEnvironmentKind.PATH.name
                environmentPrefix = environment?.prefix.orEmpty()
                switchName = environment?.switchName.orEmpty()
                opamExecutable = workspace.opamExecutableOverride.orEmpty().ifBlank { "opam" }
                duneExecutable = workspace.duneExecutableOverride.orEmpty()
                    .ifBlank { environment?.dune?.executable.orEmpty() }
                    .ifBlank { "dune" }
            }
        }

    override fun getProjectResolverClass(): Class<out ExternalSystemProjectResolver<DuneExternalExecutionSettings>> =
        DuneExternalProjectResolver::class.java

    override fun getTaskManagerClass(): Class<out ExternalSystemTaskManager<DuneExternalExecutionSettings>> =
        DuneExternalTaskManager::class.java

    override fun getExternalProjectDescriptor(): FileChooserDescriptor =
        FileChooserDescriptorFactory.createSingleFolderDescriptor()

    override fun getExternalProjectConfigDescriptor(): FileChooserDescriptor = externalProjectDescriptor

    override fun getProjectRepresentationName(targetProjectPath: String, rootProjectPath: String?): String =
        Path.of(targetProjectPath).fileName?.toString() ?: OCamlBundle.message("dune.external.name")

    override fun getProjectIcon(): Icon = OCamlIcons.Dune

    override fun getTaskIcon(): Icon = OCamlIcons.Dune

    override fun getAffectedExternalProjectPath(changedFileOrDirPath: String, project: Project): String? {
        val changed = runCatching { Path.of(changedFileOrDirPath).toAbsolutePath().normalize() }.getOrNull() ?: return null
        if (changed.fileName?.toString() !in DUNE_MODEL_FILES) return null
        return DuneExternalSystemSettings.getInstance(project).linkedProjectsSettings
            .mapNotNull { runCatching { Path.of(it.externalProjectPath).toAbsolutePath().normalize() }.getOrNull() }
            .firstOrNull(changed::startsWith)
            ?.toString()
    }
}

class DuneExternalProjectResolver : ExternalSystemProjectResolver<DuneExternalExecutionSettings> {
    override fun resolveProjectInfo(
        id: ExternalSystemTaskId,
        projectPath: String,
        isPreviewMode: Boolean,
        settings: DuneExternalExecutionSettings?,
        listener: ExternalSystemTaskNotificationListener,
    ): DataNode<ProjectData> {
        val root = Path.of(projectPath).toAbsolutePath().normalize()
        require(Files.isDirectory(root)) { OCamlBundle.message("dune.external.error.root", root) }
        val projectData = ProjectData(
            DUNE_SYSTEM_ID,
            root.fileName?.toString() ?: "Dune",
            root.toString(),
            root.toString(),
        )
        val node = DataNode(ProjectKeys.PROJECT, projectData, null)
        val configurations = discoverDuneRunConfigurations(root)
        val taskNames = buildList {
            add("build")
            add("test")
            add("clean")
            configurations.filter { it.command == DuneCommand.EXEC }.forEach { add("exec ${it.target}") }
        }
        taskNames.distinct().forEach { taskName ->
            node.createChild(
                ProjectKeys.TASK,
                TaskData(DUNE_SYSTEM_ID, taskName, root.toString(), duneTaskDescription(taskName)),
            )
        }
        return node
    }

    override fun cancelTask(
        taskId: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
    ): Boolean = false
}

class DuneExternalTaskManager : ExternalSystemTaskManager<DuneExternalExecutionSettings> {
    private val activeProcesses = ConcurrentHashMap<ExternalSystemTaskId, CapturingProcessHandler>()

    override fun executeTasks(
        projectPath: String,
        id: ExternalSystemTaskId,
        settings: DuneExternalExecutionSettings,
        listener: ExternalSystemTaskNotificationListener,
    ) {
        listener.onStart(projectPath, id)
        var pauseLease: AutoCloseable? = null
        try {
            pauseLease = findOpenProject(projectPath)?.let { DuneWatchService.getInstance(it).acquirePause() }
            if (!settings.trusted) throw ExternalSystemException(OCamlBundle.message("run.error.untrusted"))
            if (!settings.duneAvailable) {
                throw ExternalSystemException(OCamlBundle.message("run.error.dune.missing"))
            }
            for (task in settings.tasks.ifEmpty { listOf("build") }) {
                val commandLine = createDuneExternalTaskCommandLine(projectPath, task, settings)
                val handler = CapturingProcessHandler(commandLine)
                activeProcesses[id] = handler
                val output = handler.runProcess()
                if (output.stdout.isNotEmpty()) listener.onTaskOutput(id, output.stdout, ProcessOutputType.STDOUT)
                if (output.stderr.isNotEmpty()) listener.onTaskOutput(id, output.stderr, ProcessOutputType.STDERR)
                if (output.exitCode != 0) {
                    throw ExternalSystemException(
                        OCamlBundle.message("dune.external.error.exit", task, output.exitCode),
                    )
                }
            }
            listener.onSuccess(projectPath, id)
        } catch (exception: Exception) {
            listener.onFailure(projectPath, id, exception)
            throw exception
        } finally {
            activeProcesses.remove(id)
            pauseLease?.close()
            listener.onEnd(projectPath, id)
        }
    }

    override fun cancelTask(
        id: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
    ): Boolean {
        val handler = activeProcesses[id] ?: return false
        handler.destroyProcess()
        return true
    }
}

private fun findOpenProject(projectPath: String): Project? {
    val normalized = runCatching { Path.of(projectPath).toAbsolutePath().normalize() }.getOrNull() ?: return null
    return ProjectManager.getInstance().openProjects.firstOrNull { project ->
        project.basePath?.let { basePath ->
            runCatching { Path.of(basePath).toAbsolutePath().normalize() == normalized }.getOrDefault(false)
        } == true
    }
}

internal fun createDuneExternalTaskCommandLine(
    projectPath: String,
    taskName: String,
    settings: DuneExternalExecutionSettings,
): GeneralCommandLine {
    val normalizedTask = taskName.trim()
    val arguments = if (normalizedTask.startsWith("exec ")) {
        listOf("exec", normalizedTask.removePrefix("exec ").trim())
    } else {
        ParametersListUtil.parse(normalizedTask)
    }
    val commandLine = if (
        settings.environmentKind == OCamlEnvironmentKind.PATH.name ||
        settings.environmentKind == OCamlEnvironmentKind.CUSTOM.name
    ) {
        GeneralCommandLine(settings.duneExecutable)
    } else {
        GeneralCommandLine(settings.opamExecutable).apply {
            addParameter("exec")
            if (settings.switchName.isNotBlank()) addParameters("--switch", settings.switchName)
            addParameters("--", settings.duneExecutable)
        }
    }
    commandLine.withParameters(arguments).withWorkDirectory(projectPath)
    if (settings.environmentKind == OCamlEnvironmentKind.CUSTOM.name && settings.environmentPrefix.isNotBlank()) {
        val environmentBin = Path.of(settings.environmentPrefix, "bin").toString()
        commandLine.withEnvironment(
            "PATH",
            listOf(environmentBin, System.getenv("PATH").orEmpty())
                .filter(String::isNotBlank)
                .joinToString(File.pathSeparator),
        )
    }
    return commandLine
}

private fun duneTaskDescription(task: String): String = when {
    task == "build" -> OCamlBundle.message("dune.external.task.build")
    task == "test" -> OCamlBundle.message("dune.external.task.test")
    task == "clean" -> OCamlBundle.message("dune.external.task.clean")
    else -> OCamlBundle.message("dune.external.task.exec")
}

private val DUNE_MODEL_FILES = setOf("dune", "dune-project", "dune-workspace")
