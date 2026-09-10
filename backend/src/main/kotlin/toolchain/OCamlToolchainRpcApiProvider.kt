@file:Suppress("UnstableApiUsage")

package dev.munormae.toolchain

import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import com.intellij.platform.rpc.backend.RemoteApiProvider
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

internal class BackendOCamlToolchainRpcApi : OCamlToolchainRpcApi {
    override suspend fun getStatusFlow(projectId: ProjectId): Flow<OCamlToolchainStatusSnapshot> {
        val project = projectId.findProjectOrNull() ?: return emptyFlow()
        return OCamlToolchainDetectionService.getInstance(project).statusFlow
    }

    override suspend fun refreshStatus(projectId: ProjectId, settings: OCamlToolchainSettingsDto) {
        val project = projectId.findProjectOrNull() ?: return
        OCamlToolchainDetectionService.getInstance(project).refresh(ToolchainSettingsSnapshot.from(settings))
    }
}

internal class OCamlToolchainRpcApiProvider : RemoteApiProvider {
    override fun RemoteApiProvider.Sink.remoteApis() {
        remoteApi(remoteApiDescriptor<OCamlToolchainRpcApi>()) { BackendOCamlToolchainRpcApi() }
    }
}
