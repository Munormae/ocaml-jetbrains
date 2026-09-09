package dev.munormae.project

import dev.munormae.dune.run.DuneCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OCamlNewProjectWizardTest {
    @Test
    fun `project names are normalized for Dune`() {
        assertEquals("my_project", sanitizeProjectName("My Project"))
        assertEquals("project_42_tools", sanitizeProjectName("42 Tools"))
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
