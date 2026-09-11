package dev.munormae.dune.run

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Test

class DuneRunLineMarkerProviderTest {
    @Test
    fun `discovers executable and test stanzas with model targets`() {
        val root = Path.of("project").toAbsolutePath().normalize()
        val targets = findDuneRunnableTargets(
            """
                (executable
                 (name server))
                (test
                 (name test_parser))
            """.trimIndent(),
            root.resolve("bin/dune"),
            root,
        )
        assertEquals(listOf(DuneCommand.EXEC, DuneCommand.TEST), targets.map { it.command })
        assertEquals(listOf("./bin/server.exe", "bin"), targets.map { it.target })
    }
}
