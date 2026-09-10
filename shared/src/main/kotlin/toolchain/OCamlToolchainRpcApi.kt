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
) {
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
    val useOpam: Boolean,
    val opamExecutable: String,
    val opamSwitch: String,
    val lspExecutable: String,
    val duneExecutable: String,
    val ocamlformatExecutable: String,
)

@Rpc
interface OCamlToolchainRpcApi : RemoteApi<Unit> {
    suspend fun getStatusFlow(projectId: ProjectId): Flow<OCamlToolchainStatusSnapshot>

    suspend fun refreshStatus(projectId: ProjectId, settings: OCamlToolchainSettingsDto)

    companion object {
        suspend fun getInstance(): OCamlToolchainRpcApi =
            RemoteApiProviderService.resolve(remoteApiDescriptor<OCamlToolchainRpcApi>())
    }
}
