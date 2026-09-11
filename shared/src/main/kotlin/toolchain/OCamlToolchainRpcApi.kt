@file:Suppress("UnstableApiUsage")

package dev.munormae.toolchain

import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class OCamlToolchainStatusSnapshot(
    val opam: String,
    val ocamllsp: String,
    val dune: String,
    val ocamlformat: String,
    val detectionState: OCamlEnvironmentDetectionState = OCamlEnvironmentDetectionState.NOT_CHECKED,
    val environments: List<OCamlEnvironmentDescriptor> = emptyList(),
    val selectedEnvironmentId: String = "",
    val duneProject: DuneProjectSummary = DuneProjectSummary(),
    val problem: String = "",
) {
    val selectedEnvironment: OCamlEnvironmentDescriptor?
        get() = environments.firstOrNull { it.id == selectedEnvironmentId }

    companion object {
        val NOT_CHECKED = OCamlToolchainStatusSnapshot(
            opam = "Not checked",
            ocamllsp = "Not checked",
            dune = "Not checked",
            ocamlformat = "Not checked",
        )
    }
}

@Serializable
data class OCamlToolchainSettingsDto(
    val environmentId: String = "",
    val environmentPrefixOverride: String = "",
    val opamExecutableOverride: String = "",
    val lspExecutableOverride: String = "",
    val duneExecutableOverride: String = "",
    val ocamlformatExecutableOverride: String = "",
)

@Rpc
interface OCamlToolchainRpcApi : RemoteApi<Unit> {
    suspend fun getStatusFlow(projectId: ProjectId): Flow<OCamlToolchainStatusSnapshot>

    suspend fun refreshStatus(projectId: ProjectId, settings: OCamlToolchainSettingsDto)

    suspend fun selectEnvironment(projectId: ProjectId, environmentId: String, environmentPrefix: String)

    suspend fun installRequiredTools(projectId: ProjectId, environmentId: String)

    companion object {
        suspend fun getInstance(): OCamlToolchainRpcApi =
            RemoteApiProviderService.resolve(remoteApiDescriptor<OCamlToolchainRpcApi>())
    }
}
