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

    @Test
    fun `ignores commented stanzas and supports plural and cram forms`() {
        val root = Path.of("project").toAbsolutePath().normalize()
        val targets = findDuneRunnableTargets(
            """
                ; (executable (name disabled))
                #| (test (name also_disabled)) |#
                (executables
                 (names server worker))
                (tests
                 (names parser lexer))
                (cram)
            """.trimIndent(),
            root.resolve("tools/dune"),
            root,
        )

        assertEquals(
            listOf("server", "worker", "parser", "lexer", "tools"),
            targets.map { it.name },
        )
        assertEquals(
            listOf(DuneCommand.EXEC, DuneCommand.EXEC, DuneCommand.TEST, DuneCommand.TEST, DuneCommand.TEST),
            targets.map { it.command },
        )
        assertEquals(
            listOf("./tools/server.exe", "./tools/worker.exe", "tools", "tools", "tools"),
            targets.map { it.target },
        )
    }
}
