package dev.munormae

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
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

        val module = ModuleManager.getInstance(project).modules.single { it.name == "camel" }
        val roots = ModuleRootManager.getInstance(module)
        assertEquals(listOf(root.url), roots.contentRootUrls.toList())
        assertContainsElements(roots.sourceRootUrls.toList(), "${root.url}/bin", "${root.url}/test")
        assertContainsElements(roots.excludeRootUrls.toList(), "${root.url}/_build", "${root.url}/_opam")
    }
}
