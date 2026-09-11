package dev.munormae.dune.run

import dev.munormae.dune.model.discoverDuneSourceMetadata
import dev.munormae.dune.model.duneExecutableModelName
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DuneWorkspaceModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `executable model names do not depend on localized run configuration text`() {
        assertEquals("published-name", duneExecutableModelName("./bin/main.exe", "published-name"))
        assertEquals("main", duneExecutableModelName("./bin/main.exe", ""))
    }

    @Test
    fun `source model exposes libraries tests packages and source roots`() {
        val root = temporaryFolder.newFolder("camel").toPath()
        Files.writeString(root.resolve("dune-project"), "(lang dune 3.0)\n(package (name camel))\n")
        Files.createDirectories(root.resolve("lib"))
        Files.writeString(root.resolve("lib/dune"), "(library (name camel_core) (public_name camel.core))\n")
        Files.createDirectories(root.resolve("test"))
        Files.writeString(root.resolve("test/dune"), "(tests (names parser lexer))\n")

        val metadata = discoverDuneSourceMetadata(root)

        assertEquals(listOf("camel_core"), metadata.libraries.map { it.name })
        assertEquals(listOf("parser", "lexer"), metadata.tests.map { it.name })
        assertEquals(listOf("camel"), metadata.packages)
        assertEquals(listOf(root.resolve("lib"), root.resolve("test")), metadata.sourceRoots)
    }

    @Test
    fun `current Dune describe fixture yields a local executable target`() {
        val output = requireNotNull(
            javaClass.classLoader.getResource("dune/describe/dune-3.24-workspace.sexp"),
        ).readText()

        val specs = parseDuneDescribeRunConfigurations(output, Path.of("C:/Projects/camel"))

        assertEquals(listOf("./bin/main.exe"), specs.map { it.target })
        assertEquals(listOf("Dune Run main"), specs.map { it.name })
    }

    @Test
    fun `published OCaml documentation fixture remains supported`() {
        val output = requireNotNull(
            javaClass.classLoader.getResource("dune/describe/dune-language-3.7-ocaml-docs.sexp"),
        ).readText()

        val specs = parseDuneDescribeRunConfigurations(output, Path.of("/workspace/mixtli-dune"))

        assertEquals(listOf("./cloud.exe"), specs.map { it.target })
        assertEquals(listOf("Dune Run cloud"), specs.map { it.name })
    }


    @Test
    fun `Dune describe model yields local executable targets`() {
        val output = """
            ((root "C:\\Projects\\camel")
             (build_context _build/default)
             (executables
              ((names (server worker))
               (requires ())
               (modules
                (((name Server) (impl (_build/default/apps/server.ml)))
                 ((name Worker) (impl (_build/default/apps/worker.ml))))))))
        """.trimIndent()

        val specs = parseDuneDescribeRunConfigurations(output, Path.of("C:/Projects/camel"))

        assertEquals(listOf("./apps/server.exe", "./apps/worker.exe"), specs.map { it.target })
        assertEquals(listOf("Dune Run server", "Dune Run worker"), specs.map { it.name })
    }
}
