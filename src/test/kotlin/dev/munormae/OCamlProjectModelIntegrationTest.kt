package dev.munormae

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.openapi.roots.ProjectFileIndex
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
        assertEquals(
            setOf("ocaml-source", "ocaml-test"),
            contentRoot.sourceRoots.map { it.rootTypeId.name }.toSet(),
        )
        assertTrue(ProjectFileIndex.getInstance(project).isInSource(requireNotNull(root.findChild("bin"))))
        assertTrue(ProjectFileIndex.getInstance(project).isInTestSourceContent(requireNotNull(root.findChild("test"))))
    }

    fun testRefreshReplacesPluginOwnedRootsInsteadOfAccumulatingThem() {
        val rootPath = createTempDir("converging-roots").toPath()
        Files.createDirectories(rootPath.resolve("bin"))
        val generated = Files.createDirectories(rootPath.resolve("generated"))
        val root = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rootPath))

        ensureOCamlModule(project, root, "converging-roots", listOf(generated))
        ensureOCamlModule(project, root, "converging-roots", emptyList())

        val contentRoot = WorkspaceModel.getInstance(project)
            .currentSnapshot
            .entities<ModuleEntity>()
            .single { module -> module.contentRoots.any { it.url.url == root.url } }
            .contentRoots
            .single()
        assertEquals(listOf("${root.url}/bin"), contentRoot.sourceRoots.map { it.url.url })
    }

    fun testDuneWorkspaceRootCoversArbitrarySourceDirectories() {
        val rootPath = createTempDir("arbitrary-dune-layout").toPath()
        Files.writeString(rootPath.resolve("dune-project"), "(lang dune 3.0)\n")
        Files.createDirectories(rootPath.resolve("lib"))
        val compiler = Files.createDirectories(rootPath.resolve("compiler"))
        Files.writeString(compiler.resolve("foo.ml"), "let answer = 42\n")
        val root = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rootPath))

        ensureOCamlModule(project, root, "arbitrary-dune-layout")

        val source = requireNotNull(root.findFileByRelativePath("compiler/foo.ml"))
        assertTrue(ProjectFileIndex.getInstance(project).isInSource(source))
    }
}
