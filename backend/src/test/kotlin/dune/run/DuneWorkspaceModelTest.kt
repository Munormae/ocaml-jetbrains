package dev.munormae.dune.run

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Test

class DuneWorkspaceModelTest {
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
