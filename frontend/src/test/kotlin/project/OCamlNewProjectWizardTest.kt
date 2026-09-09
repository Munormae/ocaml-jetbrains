package dev.munormae.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OCamlNewProjectWizardTest {
    @Test
    fun `project names are normalized for Dune`() {
        assertEquals("my_project", sanitizeProjectName("My Project"))
        assertEquals("project_42_tools", sanitizeProjectName("42 Tools"))
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
