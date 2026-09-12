package dev.munormae

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.storage.entities
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.munormae.project.ensureOCamlModule
import java.nio.file.Files

class OCamlProjectModelIntegrationTest : HeavyPlatformTestCase() {
    fun testGeneratedProjectReceivesModuleContentSourcesAndExclusions() {
        val rootPath = createTempDir("camel").toPath()
        Files.createDirectories(rootPath.resolve("bin"))
        Files.createDirectories(rootPath.resolve("test"))
        Files.createDirectories(rootPath.resolve("_build"))
        Files.createDirectories(rootPath.resolve("_opam"))
        val root = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rootPath))

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
