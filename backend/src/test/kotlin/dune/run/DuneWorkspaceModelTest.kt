package dev.munormae.dune.run

import dev.munormae.dune.model.discoverDuneSourceMetadata
import dev.munormae.dune.model.discoverDuneSourceModel
import dev.munormae.dune.model.discoverDuneWorkspaceSourceModel
import dev.munormae.dune.model.DuneWorkspaceSourceIndex
import dev.munormae.dune.model.duneExecutableModelName
import dev.munormae.dune.model.rootsNeedingDuneDescribe
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DuneWorkspaceModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `executable model names do not depend on localized run configuration text`() {
        assertEquals("published-name", duneExecutableModelName("./bin/main.exe", "published-name"))
        assertEquals("main", duneExecutableModelName("./bin/main.exe", ""))
    }

    @Test
    fun `source model exposes libraries tests packages and source roots`() {
        val root = temporaryFolder.newFolder("camel").toPath()
        Files.writeString(root.resolve("dune-project"), "(lang dune 3.0)\n(package (name camel))\n")
        Files.createDirectories(root.resolve("lib"))
        Files.writeString(root.resolve("lib/dune"), "(library (name camel_core) (public_name camel.core))\n")
        Files.createDirectories(root.resolve("test"))
        Files.writeString(root.resolve("test/dune"), "(tests (names parser lexer))\n")

        val metadata = discoverDuneSourceMetadata(root)

        assertEquals(listOf("camel_core"), metadata.libraries.map { it.name })
        assertEquals(listOf("parser", "lexer"), metadata.tests.map { it.name })
        assertEquals(listOf("camel"), metadata.packages)
        assertEquals(listOf(root.resolve("lib"), root.resolve("test")), metadata.sourceRoots)
    }

    @Test
    fun `one source model load exposes executable and test run configurations`() {
        val root = temporaryFolder.newFolder("single-source-model").toPath()
        Files.writeString(root.resolve("dune-project"), "(lang dune 3.0)\n")
        Files.createDirectories(root.resolve("bin"))
        Files.writeString(
            root.resolve("bin/dune"),
            "(executables (names server worker) (public_names camel-server -))\n(test (name smoke))\n",
        )

        val model = discoverDuneSourceModel(root)

        assertEquals(listOf("server", "worker"), model.executables.map { it.name })
        assertEquals(listOf("./bin/server.exe", "./bin/worker.exe"), model.executables.map { it.target })
        assertEquals(listOf("camel-server", ""), model.executables.map { it.publicName })
        assertEquals(listOf("smoke"), model.tests.map { it.name })
        assertEquals(
            listOf(DuneCommand.BUILD, DuneCommand.EXEC, DuneCommand.EXEC, DuneCommand.TEST),
            model.runConfigurations.map { it.command },
        )
    }

    @Test
    fun `workspace source model assigns targets to their nearest Dune root`() {
        val workspace = temporaryFolder.newFolder("multi-root").toPath()
        Files.writeString(workspace.resolve("dune-project"), "(lang dune 3.0)\n(package (name parent))\n")
        Files.writeString(workspace.resolve("dune"), "(executable (name parent_app))\n")
        val child = Files.createDirectories(workspace.resolve("products/child"))
        Files.writeString(child.resolve("dune-project"), "(lang dune 3.0)\n(package (name child))\n")
        Files.writeString(child.resolve("dune"), "(executable (name child_app))\n")
        val ignored = Files.createDirectories(workspace.resolve("_build/generated"))
        Files.writeString(ignored.resolve("dune-project"), "(lang dune 3.0)\n")

        val model = discoverDuneWorkspaceSourceModel(workspace)

        assertEquals(listOf(workspace.toAbsolutePath().normalize(), child.toAbsolutePath().normalize()), model.roots)
        assertEquals(listOf("parent_app"), model.projects.getValue(workspace.toAbsolutePath().normalize()).executables.map { it.name })
        assertEquals(listOf("child_app"), model.projects.getValue(child.toAbsolutePath().normalize()).executables.map { it.name })
        assertEquals(
            listOf("child_app"),
            findDuneRunConfigurationModel(model, child.toString())?.executables?.map { it.name },
        )
    }

    @Test
    fun `nested Dune root does not orphan targets in an implicit parent root`() {
        val workspace = temporaryFolder.newFolder("implicit-parent").toPath()
        Files.writeString(workspace.resolve("dune"), "(executable (name parent_app))\n")
        val child = Files.createDirectories(workspace.resolve("child"))
        Files.writeString(child.resolve("dune-project"), "(lang dune 3.0)\n")
        Files.writeString(child.resolve("dune"), "(executable (name child_app))\n")

        val model = discoverDuneWorkspaceSourceModel(workspace)

        assertEquals(listOf(workspace, child), model.roots)
        assertEquals(listOf("parent_app"), model.projects.getValue(workspace).executables.map { it.name })
        assertEquals(listOf("child_app"), model.projects.getValue(child).executables.map { it.name })
    }

    @Test
    fun `one Dune edit refreshes only its nearest root describe`() {
        val parent = temporaryFolder.newFolder("describe-parent").toPath()
        val child = Files.createDirectories(parent.resolve("child"))
        val roots = listOf(parent, child)

        assertEquals(setOf(child), rootsNeedingDuneDescribe(roots, setOf(child.resolve("lib/dune"))))
        assertEquals(setOf(parent), rootsNeedingDuneDescribe(roots, setOf(parent.resolve("bin/dune"))))
        assertEquals(roots.toSet(), rootsNeedingDuneDescribe(roots, setOf(child.resolve("dune-project"))))
    }

    @Test
    fun `incremental source index rereads only changed Dune files`() {
        val root = temporaryFolder.newFolder("incremental-index").toPath()
        Files.writeString(root.resolve("dune-project"), "(lang dune 3.0)\n")
        val firstDirectory = Files.createDirectories(root.resolve("first"))
        val secondDirectory = Files.createDirectories(root.resolve("second"))
        val first = firstDirectory.resolve("dune")
        val second = secondDirectory.resolve("dune")
        Files.writeString(first, "(executable (name first_old))\n")
        Files.writeString(second, "(executable (name second_old))\n")
        val index = DuneWorkspaceSourceIndex(root)
        index.refresh()

        Files.writeString(first, "(executable (name first_new))\n")
        Files.writeString(second, "(executable (name second_new))\n")
        val afterSecond = index.refresh(setOf(second))

        assertEquals(
            listOf("first_old", "second_new"),
            afterSecond.projects.getValue(root.toAbsolutePath().normalize()).executables.map { it.name },
        )
        val afterFirst = index.refresh(setOf(first))
        assertEquals(
            listOf("first_new", "second_new"),
            afterFirst.projects.getValue(root.toAbsolutePath().normalize()).executables.map { it.name },
        )
    }

    @Test
    fun `incremental source index drops stale metadata when a Dune file is deleted`() {
        val root = temporaryFolder.newFolder("deleted-incremental-index").toPath()
        Files.writeString(root.resolve("dune-project"), "(lang dune 3.0)\n")
        val duneFile = root.resolve("dune")
        Files.writeString(duneFile, "(executable (name old_target))\n")
        val index = DuneWorkspaceSourceIndex(root)
        index.refresh()

        Files.delete(duneFile)
        val model = index.refresh(setOf(duneFile))

        assertEquals(emptyList<String>(), model.projects.getValue(root.toAbsolutePath().normalize()).executables.map { it.name })
    }

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
