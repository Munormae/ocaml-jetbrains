package dev.munormae.dune.run

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Test

class DuneWorkspaceModelTest {
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
