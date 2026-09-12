package dev.munormae

import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.storage.entities
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.munormae.project.ensureOCamlModule

class OCamlProjectModelIntegrationTest : BasePlatformTestCase() {
    fun testGeneratedProjectReceivesModuleContentSourcesAndExclusions() {
        val root = myFixture.tempDirFixture.findOrCreateDir("camel")
        myFixture.tempDirFixture.findOrCreateDir("camel/bin")
        myFixture.tempDirFixture.findOrCreateDir("camel/test")
        myFixture.tempDirFixture.findOrCreateDir("camel/_build")
        myFixture.tempDirFixture.findOrCreateDir("camel/_opam")

        ensureOCamlModule(project, root, "camel")

        val module = WorkspaceModel.getInstance(project)
            .currentSnapshot
            .entities<ModuleEntity>()
            .single { it.name == "camel" }
        val contentRoot = module.contentRoots.single()
        assertEquals(root.url, contentRoot.url.url)
        assertContainsElements(
            contentRoot.sourceRoots.map { it.url.url },
            "${root.url}/bin",
            "${root.url}/test",
        )
        assertContainsElements(
            contentRoot.excludedUrls.map { it.url.url },
            "${root.url}/_build",
            "${root.url}/_opam",
        )
    }
}
