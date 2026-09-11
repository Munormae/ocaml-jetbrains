package dev.munormae.project

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OCamlProjectModelServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `application layout marks bin and test sources and build directories excluded`() {
        val root = temporaryFolder.newFolder("camel").toPath()
        Files.createDirectories(root.resolve("bin"))
        Files.createDirectories(root.resolve("test"))
        Files.writeString(root.resolve("dune-project"), "(lang dune 3.0)")

        val layout = deriveOCamlProjectLayout(root)

        assertEquals(listOf(root.resolve("bin")), layout.sourceRoots)
        assertEquals(listOf(root.resolve("test")), layout.testRoots)
        assertEquals(listOf(root.resolve("_build"), root.resolve("_opam")), layout.excludedRoots)
        assertTrue(layout.isOCamlProject)
    }

    @Test
    fun `minimal layout uses project root as source root`() {
        val root = temporaryFolder.newFolder("minimal").toPath()
        Files.writeString(root.resolve("main.ml"), "let () = ()")

        val layout = deriveOCamlProjectLayout(root)

        assertEquals(listOf(root), layout.sourceRoots)
        assertTrue(layout.isOCamlProject)
    }

    @Test
    fun `library layout marks lib as a source root`() {
        val root = temporaryFolder.newFolder("library").toPath()
        Files.createDirectories(root.resolve("lib"))
        Files.writeString(root.resolve("dune-project"), "(lang dune 3.0)")

        val layout = deriveOCamlProjectLayout(root)

        assertEquals(listOf(root.resolve("lib")), layout.sourceRoots)
        assertTrue(layout.isOCamlProject)
    }

    @Test
    fun `existing local switch is recognized as an OCaml project marker`() {
        val root = temporaryFolder.newFolder("existing").toPath()
        Files.createDirectories(root.resolve("_opam"))

        val layout = deriveOCamlProjectLayout(root)

        assertTrue(layout.isOCamlProject)
        assertEquals(listOf(root.resolve("_build"), root.resolve("_opam")), layout.excludedRoots)
    }

    @Test
    fun `unrelated directory is not adopted as an OCaml project`() {
        val root = temporaryFolder.newFolder("unrelated").toPath()
        Files.writeString(root.resolve("README.md"), "plain project")

        assertFalse(deriveOCamlProjectLayout(root).isOCamlProject)
    }
}
