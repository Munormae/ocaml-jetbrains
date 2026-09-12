@file:Suppress("UnstableApiUsage")

package dev.munormae.toolchain

import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import com.intellij.platform.rpc.backend.RemoteApiProvider
import dev.munormae.dune.model.DuneProjectModelService
import dev.munormae.dune.model.DuneProjectModelState
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow

internal class BackendOCamlToolchainRpcApi : OCamlToolchainRpcApi {
    override suspend fun getStatusFlow(projectId: ProjectId): Flow<OCamlToolchainStatusSnapshot> {
        val project = projectId.findProjectOrNull() ?: return emptyFlow()
        return combine(
            OCamlToolchainDetectionService.getInstance(project).statusFlow,
            DuneProjectModelService.getInstance(project).stateFlow,
        ) { toolchain, dune ->
            toolchain.copy(duneProject = dune.toSummary())
        }
    }

    override suspend fun refreshStatus(projectId: ProjectId, settings: OCamlToolchainSettingsDto) {
        val project = projectId.findProjectOrNull() ?: return
        OCamlToolchainDetectionService.getInstance(project).refresh(EnvironmentDiscoverySettings.from(settings))
        DuneProjectModelService.getInstance(project).requestRefresh(delayMs = 0)
    }

    override suspend fun selectEnvironment(
        projectId: ProjectId,
        environmentId: String,
        environmentPrefix: String,
    ) {
        val project = projectId.findProjectOrNull() ?: return
        OCamlToolchainDetectionService.getInstance(project).selectEnvironment(environmentId, environmentPrefix)
    }

    override suspend fun installRequiredTools(projectId: ProjectId, environmentId: String) {
        val project = projectId.findProjectOrNull() ?: return
        OCamlToolchainDetectionService.getInstance(project).installRequiredTools(environmentId)
    }
}

private fun DuneProjectModelState.toSummary(): DuneProjectSummary = when (this) {
    DuneProjectModelState.NotLoaded -> DuneProjectSummary(DuneProjectSyncState.NOT_LOADED)
    DuneProjectModelState.Loading -> DuneProjectSummary(DuneProjectSyncState.LOADING)
    is DuneProjectModelState.Failed -> DuneProjectSummary(
        state = DuneProjectSyncState.FAILED,
        problem = problem,
    )
    is DuneProjectModelState.Ready -> DuneProjectSummary(
        state = DuneProjectSyncState.READY,
        root = model.root.toString(),
        executableCount = model.executables.size,
        libraryCount = model.libraries.size,
        testCount = model.tests.size,
    )
}

internal class OCamlToolchainRpcApiProvider : RemoteApiProvider {
    override fun RemoteApiProvider.Sink.remoteApis() {
        remoteApi(remoteApiDescriptor<OCamlToolchainRpcApi>()) { BackendOCamlToolchainRpcApi() }
    }
}
