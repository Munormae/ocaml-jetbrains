package dev.munormae.toolchain

import org.junit.Assert.assertEquals
import org.junit.Test

class OCamlEditorNotificationProviderTest {
    @Test
    fun `reports setup problems only for project OCaml files after detection`() {
        assertEquals(
            OCamlSetupProblem.ENVIRONMENT_MISSING,
            ocamlSetupProblem("ml", true, OCamlEnvironmentDetectionState.READY, null),
        )
        assertEquals(
            OCamlSetupProblem.NONE,
            ocamlSetupProblem("ml", true, OCamlEnvironmentDetectionState.DETECTING, null),
        )
        assertEquals(
            OCamlSetupProblem.DETECTION_FAILED,
            ocamlSetupProblem("ml", true, OCamlEnvironmentDetectionState.FAILED, null),
        )
        assertEquals(
            OCamlSetupProblem.NONE,
            ocamlSetupProblem("txt", true, OCamlEnvironmentDetectionState.READY, null),
        )
    }

    @Test
    fun `reports missing language server for an otherwise usable environment`() {
        val environment = OCamlEnvironmentDescriptor(
            id = "opam:default",
            name = "OCaml",
            kind = OCamlEnvironmentKind.OPAM_SWITCH,
            compiler = available("ocamlc"),
            dune = available("dune"),
            languageServer = OCamlToolStatus(OCamlToolAvailability.MISSING),
        )
        assertEquals(
            OCamlSetupProblem.LANGUAGE_SERVER_MISSING,
            ocamlSetupProblem("mli", true, OCamlEnvironmentDetectionState.READY, environment),
        )
    }

    @Test
    fun `reports missing Dune for a Dune project`() {
        val environment = OCamlEnvironmentDescriptor(
            id = "opam:default",
            name = "OCaml",
            kind = OCamlEnvironmentKind.OPAM_SWITCH,
            compiler = available("ocamlc"),
            dune = OCamlToolStatus(OCamlToolAvailability.MISSING),
            languageServer = available("ocamllsp"),
            formatter = available("ocamlformat"),
        )

        assertEquals(
            OCamlSetupProblem.DUNE_MISSING,
            ocamlSetupProblem("ml", true, OCamlEnvironmentDetectionState.READY, environment),
        )
    }

    @Test
    fun `reports missing formatter only when project formatting is enabled`() {
        val environment = OCamlEnvironmentDescriptor(
            id = "opam:default",
            name = "OCaml",
            kind = OCamlEnvironmentKind.OPAM_SWITCH,
            compiler = available("ocamlc"),
            dune = available("dune"),
            languageServer = available("ocamllsp"),
            formatter = OCamlToolStatus(OCamlToolAvailability.MISSING),
        )

        assertEquals(
            OCamlSetupProblem.FORMATTER_MISSING,
            ocamlSetupProblem("ml", true, OCamlEnvironmentDetectionState.READY, environment),
        )
        assertEquals(
            OCamlSetupProblem.NONE,
            ocamlSetupProblem(
                "ml",
                true,
                OCamlEnvironmentDetectionState.READY,
                environment,
                formatterRequired = false,
            ),
        )
    }

    private fun available(executable: String) = OCamlToolStatus(
        availability = OCamlToolAvailability.AVAILABLE,
        executable = executable,
    )
}
