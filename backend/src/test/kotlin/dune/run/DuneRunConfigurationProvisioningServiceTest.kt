package dev.munormae.dune.run

import java.nio.file.Path
import dev.munormae.dune.model.DuneProjectModel
import dev.munormae.dune.model.DuneProjectModelState
import org.junit.Assert.assertEquals
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

    @Test
    fun `only the latest requested refresh generation may apply`() {
        val generations = LatestRefreshGeneration()
        val applied = mutableListOf<String>()
        val first = generations.next()
        val second = generations.next()

        if (generations.isCurrent(first)) applied += "old"
        if (generations.isCurrent(second)) applied += "new"

        assertEquals(listOf("new"), applied)
    }

    @Test
    fun `invalidating refresh generations rejects outstanding work`() {
        val generations = LatestRefreshGeneration()
        val outstanding = generations.next()

        generations.invalidate()

        assertFalse(generations.isCurrent(outstanding))
    }

    @Test
    fun `ready model is the only state that exposes run configurations`() {
        val model = DuneProjectModel(Path.of("C:/projects/camel"))

        assertTrue(DuneProjectModelState.Ready(model).runConfigurations.isNotEmpty())
        assertTrue(DuneProjectModelState.Loading.runConfigurations.isEmpty())
        assertTrue(DuneProjectModelState.Failed("broken").runConfigurations.isEmpty())
    }
}
