package dev.munormae.toolchain

import kotlinx.serialization.Serializable

@Serializable
enum class OCamlEnvironmentKind {
    LOCAL_OPAM_SWITCH,
    OPAM_SWITCH,
    CUSTOM,
    PATH,
}

@Serializable
enum class OCamlToolAvailability {
    AVAILABLE,
    MISSING,
    ERROR,
    NOT_CHECKED,
    BLOCKED,
}

@Serializable
data class OCamlToolStatus(
    val availability: OCamlToolAvailability = OCamlToolAvailability.NOT_CHECKED,
    val version: String = "",
    val executable: String = "",
    val detail: String = "",
) {
    val isAvailable: Boolean
        get() = availability == OCamlToolAvailability.AVAILABLE
}

@Serializable
data class OCamlEnvironmentDescriptor(
    val id: String,
    val name: String,
    val kind: OCamlEnvironmentKind,
    val switchName: String = "",
    val prefix: String = "",
    val compiler: OCamlToolStatus = OCamlToolStatus(),
    val dune: OCamlToolStatus = OCamlToolStatus(),
    val languageServer: OCamlToolStatus = OCamlToolStatus(),
    val formatter: OCamlToolStatus = OCamlToolStatus(),
    val canInstallTools: Boolean = false,
) {
    val isReady: Boolean
        get() = compiler.isAvailable && dune.isAvailable && languageServer.isAvailable

    val hasAllTools: Boolean
        get() = isReady && formatter.isAvailable

    override fun toString(): String = name
}

@Serializable
enum class OCamlEnvironmentDetectionState {
    NOT_CHECKED,
    DETECTING,
    READY,
    BLOCKED,
    FAILED,
}

@Serializable
enum class DuneProjectSyncState {
    NOT_LOADED,
    LOADING,
    READY,
    FAILED,
}

@Serializable
data class DuneProjectSummary(
    val state: DuneProjectSyncState = DuneProjectSyncState.NOT_LOADED,
    val root: String = "",
    val executableCount: Int = 0,
    val libraryCount: Int = 0,
    val testCount: Int = 0,
    val problem: String = "",
)
