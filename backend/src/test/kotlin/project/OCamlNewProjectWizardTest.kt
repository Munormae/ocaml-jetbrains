package dev.munormae.project

import dev.munormae.dune.run.DuneCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.munormae.toolchain.OCamlEnvironmentDescriptor
import dev.munormae.toolchain.OCamlEnvironmentKind
import dev.munormae.toolchain.OCamlToolAvailability
import dev.munormae.toolchain.OCamlToolStatus

class OCamlNewProjectWizardTest {
    @Test
    fun `wizard exposes only user facing project types`() {
        assertEquals(
            listOf(
                OCamlProjectTemplate.APPLICATION,
                OCamlProjectTemplate.LIBRARY,
                OCamlProjectTemplate.APPLICATION_WITH_LIBRARY,
            ),
            WIZARD_PROJECT_TEMPLATES,
        )
    }

    @Test
    fun `project names are normalized for Dune`() {
        assertEquals("my_project", sanitizeProjectName("My Project"))
        assertEquals("project_42_tools", sanitizeProjectName("42 Tools"))
    }

    @Test
    fun `wizard makes the derived Dune package name visible`() {
        assertEquals("Dune package: my_project", dunePackageNamePresentation("My Project"))
    }

    @Test
    fun `project creation requires a complete environment or an explicit repair plan`() {
        val available = OCamlToolStatus(OCamlToolAvailability.AVAILABLE, "1", "/bin/tool")
        val missing = OCamlToolStatus(OCamlToolAvailability.MISSING)
        val environment = OCamlEnvironmentDescriptor(
            id = "opam:5.3.0",
            name = "OCaml 5.3.0",
            kind = OCamlEnvironmentKind.OPAM_SWITCH,
            compiler = available,
            dune = available,
            languageServer = missing,
            formatter = missing,
            canInstallTools = true,
        )

        assertFalse(canCreateOCamlProject(environment, createLocalEnvironment = false, installMissingTools = false))
        assertTrue(canCreateOCamlProject(environment, createLocalEnvironment = false, installMissingTools = true))
        assertTrue(canCreateOCamlProject(null, createLocalEnvironment = true, installMissingTools = true))
    }

    @Test
    fun `wizard accepts an OPAM environment whose missing project tools will be installed`() {
        val available = OCamlToolStatus(OCamlToolAvailability.AVAILABLE, "5.3.0", "/bin/ocamlc")
        val missing = OCamlToolStatus(OCamlToolAvailability.MISSING)
        val environment = OCamlEnvironmentDescriptor(
            id = "opam:5.3.0",
            name = "OCaml 5.3.0",
            kind = OCamlEnvironmentKind.OPAM_SWITCH,
            compiler = available,
            dune = missing,
            languageServer = missing,
            formatter = missing,
            canInstallTools = true,
        )

        assertTrue(canCreateOCamlProject(environment, createLocalEnvironment = false, installMissingTools = true))
    }

    @Test
    fun `minimal template creates only a buildable root executable`() {
        val files = createProjectFiles(
            projectName = "hello_ocaml",
            template = OCamlProjectTemplate.MINIMAL,
            addTests = true,
        )

        assertEquals(
            setOf("dune-project", "dune", "main.ml", ".ocamlformat", ".gitignore"),
            files.keys,
        )
        assertFalse(files.keys.any { it.startsWith("test/") })
        assertTrue(files.getValue("dune").contains("(name main)"))
    }

    @Test
    fun `wizard uses the selected Dune language version with a safe fallback`() {
        val files = createProjectFiles(
            projectName = "hello_ocaml",
            template = OCamlProjectTemplate.MINIMAL,
            addTests = false,
            duneLanguageVersion = "3.24",
        )

        assertTrue(files.getValue("dune-project").startsWith("(lang dune 3.24)"))
        assertEquals("3.24", normalizeDuneLanguageVersion(" 3.24 "))
        assertEquals("3.0", normalizeDuneLanguageVersion("latest"))
    }

    @Test
    fun `minimal template receives ready to use build and run configurations`() {
        val specs = generatedProjectRunConfigurations(
            template = OCamlProjectTemplate.MINIMAL,
            projectName = "hello_ocaml",
            addTests = false,
        )

        assertEquals(listOf(DuneCommand.BUILD, DuneCommand.EXEC), specs.map { it.command })
        assertEquals("./main.exe", specs.single { it.command == DuneCommand.EXEC }.target)
    }

    @Test
    fun `library template receives build and optional test configurations`() {
        val specs = generatedProjectRunConfigurations(
            template = OCamlProjectTemplate.LIBRARY,
            projectName = "camel_core",
            addTests = true,
        )

        assertEquals(listOf(DuneCommand.BUILD, DuneCommand.TEST), specs.map { it.command })
    }

    @Test
    fun `executable templates run the local Dune target instead of the public name`() {
        val application = generatedProjectRunConfigurations(
            template = OCamlProjectTemplate.APPLICATION,
            projectName = "camel_app",
            addTests = false,
        ).single { it.command == DuneCommand.EXEC }
        val applicationWithLibrary = generatedProjectRunConfigurations(
            template = OCamlProjectTemplate.APPLICATION_WITH_LIBRARY,
            projectName = "camel_app",
            addTests = false,
        ).single { it.command == DuneCommand.EXEC }

        assertEquals("./bin/main.exe", application.target)
        assertEquals("camel_app", application.legacyTarget)
        assertEquals("./bin/main.exe", applicationWithLibrary.target)
        assertEquals("camel_app-cli", applicationWithLibrary.legacyTarget)
    }

    @Test
    fun `Dune library wrapper preserves underscores in its OCaml module name`() {
        assertEquals("My_project", moduleName("my_project"))

        val files = createProjectFiles(
            projectName = "my_project",
            template = OCamlProjectTemplate.APPLICATION_WITH_LIBRARY,
            addTests = true,
        )

        assertTrue(files.getValue("bin/main.ml").contains("My_project.greeting"))
        assertTrue(files.getValue("test/test_main.ml").contains("My_project.greeting"))
    }
}
