package dev.munormae.dune.run

import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuneRunConfigurationProvisioningServiceTest {
    @Test
    fun `refreshes only for Dune model files inside the project`() {
        val root = Path.of("C:/projects/camel")

        assertTrue(isDuneModelPath(root.toString(), root.resolve("dune-project").toString()))
        assertTrue(isDuneModelPath(root.toString(), root.resolve("apps/server/dune").toString()))
        assertFalse(isDuneModelPath(root.toString(), root.resolve("apps/server/main.ml").toString()))
        assertFalse(isDuneModelPath(root.toString(), root.resolve("_build/default/dune").toString()))
        assertFalse(isDuneModelPath(root.toString(), Path.of("C:/projects/other/dune").toString()))
    }
}
