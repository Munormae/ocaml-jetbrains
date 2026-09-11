@file:Suppress("UnstableApiUsage")

package dev.munormae.toolchain

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import com.intellij.util.messages.Topic
import dev.munormae.OCamlBundle
import fleet.rpc.client.durable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class OCamlToolchainStatusService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    private val mutableStatus = MutableStateFlow(OCamlToolchainStatusSnapshot.NOT_CHECKED)
    val statusFlow: StateFlow<OCamlToolchainStatusSnapshot> = mutableStatus.asStateFlow()

    val snapshot: OCamlToolchainStatusSnapshot
        get() = statusFlow.value

    init {
        coroutineScope.launch {
            flow {
                durable {
                    OCamlToolchainRpcApi.getInstance().getStatusFlow(project.projectId()).collect(::emit)
                }
            }.collect(::update)
        }
    }

    fun refresh(settings: OCamlToolchainSettingsDto) {
        coroutineScope.launch {
            try {
                OCamlToolchainRpcApi.getInstance().refreshStatus(project.projectId(), settings)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                val unavailable = OCamlBundle.message("status.backend.failed")
                update(
                    OCamlToolchainStatusSnapshot(
                        unavailable,
                        unavailable,
                        unavailable,
                        unavailable,
                        detectionState = OCamlEnvironmentDetectionState.FAILED,
                        problem = unavailable,
                    ),
                )
            }
        }
    }

    fun selectEnvironment(environmentId: String, environmentPrefix: String = "") {
        coroutineScope.launch {
            OCamlToolchainRpcApi.getInstance().selectEnvironment(
                project.projectId(),
                environmentId,
                environmentPrefix,
            )
        }
    }

    fun installRequiredTools(environmentId: String) {
        coroutineScope.launch {
            OCamlToolchainRpcApi.getInstance().installRequiredTools(project.projectId(), environmentId)
        }
    }

    private fun update(newSnapshot: OCamlToolchainStatusSnapshot) {
        mutableStatus.value = newSnapshot
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                project.messageBus.syncPublisher(CHANGED_TOPIC).statusChanged(newSnapshot)
            }
        }
    }

    companion object {
        @JvmField
        val CHANGED_TOPIC: Topic<OCamlToolchainStatusChangedListener> = Topic.create(
            "OCaml toolchain status changed",
            OCamlToolchainStatusChangedListener::class.java,
        )

        fun getInstance(project: Project): OCamlToolchainStatusService = project.service()
    }
}

fun interface OCamlToolchainStatusChangedListener {
    fun statusChanged(snapshot: OCamlToolchainStatusSnapshot)
}
