package dev.munormae.toolchain

import dev.munormae.settings.OCamlWorkspaceSettings
import java.util.Collections
import java.util.concurrent.Executors
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OCamlToolchainDetectionServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `selecting a non-custom environment clears the previous directory override`() {
        val state = OCamlWorkspaceSettings.WorkspaceState().apply {
            environmentId = "custom:C:/ocaml"
            environmentPrefixOverride = "C:/ocaml"
        }

        updateEnvironmentSelection(state, "opam_switch:5.3.0", "")

        assertEquals("opam_switch:5.3.0", state.environmentId)
        assertEquals("", state.environmentPrefixOverride.orEmpty())
    }

    @Test
    fun `directory-only selection resolves to the backend-normalized custom environment id`() {
        val prefix = "C:/ocaml"

        assertEquals(
            environmentCandidate(OCamlEnvironmentKind.CUSTOM, "", prefix).id,
            resolveEnvironmentSelectionId("", prefix),
        )
        assertEquals("opam_switch:5.3.0", resolveEnvironmentSelectionId("opam_switch:5.3.0", prefix))
    }

    @Test
    fun `OPAM switch list is normalized and de-duplicated`() {
        assertEquals(
            listOf("5.3.0", "default", "C:/work/camel"),
            parseOpamSwitchList(" 5.3.0\ndefault\n5.3.0\nC:/work/camel\n"),
        )
    }

    @Test
    fun `environment selection prefers requested then local then current OPAM then PATH`() {
        val path = environmentCandidate(OCamlEnvironmentKind.PATH, "", "C:/ocaml")
        val current = environmentCandidate(OCamlEnvironmentKind.OPAM_SWITCH, "5.3.0", "C:/opam/5.3.0")
        val local = environmentCandidate(OCamlEnvironmentKind.LOCAL_OPAM_SWITCH, "C:/work/camel", "C:/work/camel/_opam")
        val candidates = listOf(path, current, local)

        assertEquals(path.id, selectEnvironmentCandidate(candidates, path.id, "5.3.0")?.id)
        assertEquals(local.id, selectEnvironmentCandidate(candidates, "", "5.3.0")?.id)
        assertEquals(current.id, selectEnvironmentCandidate(listOf(path, current), "", "5.3.0")?.id)
        assertEquals(path.id, selectEnvironmentCandidate(listOf(path), "", "")?.id)
    }

    @Test
    fun `OPAM environment command keeps switch and tool arguments separate`() {
        val candidate = environmentCandidate(
            OCamlEnvironmentKind.OPAM_SWITCH,
            "5.3.0",
            "C:/opam/5.3.0",
        )

        val command = createEnvironmentCommandLine(
            candidate = candidate,
            opamExecutable = "C:/bin/opam.exe",
            executable = "ocamllsp",
            arguments = listOf("--version"),
        )

        assertEquals("C:/bin/opam.exe", command.exePath)
        assertEquals(
            listOf("exec", "--switch", "5.3.0", "--", "ocamllsp", "--version"),
            command.parametersList.list,
        )
    }

    @Test
    fun `selected directory environment runs tools directly`() {
        val candidate = environmentCandidate(
            OCamlEnvironmentKind.CUSTOM,
            "",
            "C:/ocaml",
        )

        val command = createEnvironmentCommandLine(
            candidate = candidate,
            opamExecutable = "opam",
            executable = "C:/ocaml/bin/ocamllsp.exe",
            arguments = listOf("--version"),
        )

        assertEquals("C:/ocaml/bin/ocamllsp.exe", command.exePath)
        assertEquals(listOf("--version"), command.parametersList.list)
    }

    @Test
    fun `Dune package management runs supported developer tools through dune tools`() {
        val candidate = environmentCandidate(
            OCamlEnvironmentKind.DUNE_PACKAGE_MANAGEMENT,
            "",
            "C:/work/camel",
        )

        val command = createEnvironmentCommandLine(
            candidate = candidate,
            opamExecutable = "opam",
            executable = "ocamllsp",
            arguments = listOf("--version"),
            duneExecutable = "custom-dune",
        )

        assertEquals("custom-dune", command.exePath)
        assertEquals(listOf("tools", "exec", "ocamllsp", "--", "--version"), command.parametersList.list)
    }

    @Test
    fun `Dune package management runs a Dune override directly`() {
        val candidate = environmentCandidate(
            OCamlEnvironmentKind.DUNE_PACKAGE_MANAGEMENT,
            "",
            "C:/work/camel",
        )

        val command = createEnvironmentCommandLine(
            candidate = candidate,
            opamExecutable = "opam",
            executable = "C:/tools/dune.exe",
            arguments = listOf("build"),
            duneExecutable = "C:/tools/dune.exe",
            duneManagedToolName = "dune",
            duneManagedExecutableOverride = true,
        )

        assertEquals("C:/tools/dune.exe", command.exePath)
        assertEquals(listOf("build"), command.parametersList.list)
    }

    @Test
    fun `Dune package management keeps an overridden language server inside the project environment`() {
        val candidate = environmentCandidate(
            OCamlEnvironmentKind.DUNE_PACKAGE_MANAGEMENT,
            "",
            "C:/work/camel",
        )

        val command = createEnvironmentCommandLine(
            candidate = candidate,
            opamExecutable = "opam",
            executable = "C:/tools/ocamllsp.exe",
            arguments = listOf("--version"),
            duneExecutable = "dune",
            duneManagedToolName = "ocamllsp",
            duneManagedExecutableOverride = true,
        )

        assertEquals("dune", command.exePath)
        assertEquals(listOf("exec", "--", "C:/tools/ocamllsp.exe", "--version"), command.parametersList.list)
    }

    @Test
    fun `discovery probes only the selected OPAM switch`() {
        val root = temporaryFolder.newFolder("lazy-switches").toPath()
        Files.writeString(root.resolve("dune-project"), "(lang dune 3.0)")
        val commands = mutableListOf<List<String>>()
        val result = discoverOCamlEnvironments(
            settings = EnvironmentDiscoverySettings(environmentId = "opam_switch:5.3.0"),
            projectBasePath = root.toString(),
            runner = EnvironmentCommandRunner { command, _ ->
                val arguments = command.parametersList.list
                commands += listOf(command.exePath) + arguments
                when {
                    arguments == listOf("--version") -> EnvironmentCommandResult(0, "2.4.1")
                    arguments.take(2) == listOf("switch", "show") -> EnvironmentCommandResult(0, "5.3.0")
                    arguments.take(2) == listOf("switch", "list") -> EnvironmentCommandResult(
                        0,
                        (1..20).joinToString("\n") { "old-$it" } + "\n5.3.0\n",
                    )
                    arguments.take(2) == listOf("var", "prefix") -> EnvironmentCommandResult(0, root.toString())
                    else -> EnvironmentCommandResult(0, "5.3.0")
                }
            },
        )

        assertEquals("opam_switch:5.3.0", result.selectedEnvironmentId)
        assertTrue(result.environments.any { it.id == "opam_switch:old-20" })
        assertFalse(commands.any { command -> command.windowed(2).any { it == listOf("--switch", "old-20") } })
        assertEquals(1, commands.count { it.drop(1).take(2) == listOf("var", "prefix") })
    }

    @Test
    fun `Dune package management is hidden when the Dune executable lacks tools support`() {
        val root = temporaryFolder.newFolder("dune-no-tools").toPath()
        Files.writeString(root.resolve("dune-project"), "(lang dune 3.0)\n")
        val duneExecutable = Files.writeString(root.resolve("dune-custom"), "fake dune executable")

        val result = discoverOCamlEnvironments(
            settings = EnvironmentDiscoverySettings(duneExecutableOverride = duneExecutable.toString()),
            projectBasePath = root.toString(),
            runner = EnvironmentCommandRunner { command, _ ->
                if (command.parametersList.list == listOf("tools", "--help")) {
                    EnvironmentCommandResult(exitCode = 1, stderr = "unknown command tools")
                } else {
                    EnvironmentCommandResult(exitCode = 1, stderr = "not installed")
                }
            },
        )

        assertFalse(result.environments.any { it.kind == OCamlEnvironmentKind.DUNE_PACKAGE_MANAGEMENT })
    }

    @Test
    fun `Dune package management is selectable when tools support is available`() {
        val root = temporaryFolder.newFolder("dune-with-tools").toPath()
        Files.writeString(root.resolve("dune-project"), "(lang dune 3.0)\n")
        val duneExecutable = Files.writeString(root.resolve("dune-custom"), "fake dune executable")

        val result = discoverOCamlEnvironments(
            settings = EnvironmentDiscoverySettings(duneExecutableOverride = duneExecutable.toString()),
            projectBasePath = root.toString(),
            runner = EnvironmentCommandRunner { command, _ ->
                if (command.exePath == "opam") EnvironmentCommandResult(exitCode = 1)
                else EnvironmentCommandResult(exitCode = 0, stdout = "5.3.0")
            },
        )

        val selected = result.environments.single { it.id == result.selectedEnvironmentId }
        assertEquals(OCamlEnvironmentKind.DUNE_PACKAGE_MANAGEMENT, selected.kind)
        assertTrue(selected.compiler.isAvailable)
        assertTrue(selected.utop.isAvailable)
    }

    @Test
    fun `UTop participates in complete environment health`() {
        val available = OCamlToolStatus(OCamlToolAvailability.AVAILABLE)
        val environment = OCamlEnvironmentDescriptor(
            id = "path:system",
            name = "OCaml",
            kind = OCamlEnvironmentKind.PATH,
            compiler = available,
            dune = available,
            languageServer = available,
            formatter = available,
            utop = OCamlToolStatus(OCamlToolAvailability.MISSING),
        )

        assertTrue(environment.isReady)
        assertFalse(environment.hasAllTools)
    }

    @Test
    fun `tool repair installs UTop in OPAM and Dune package management environments`() {
        val opamEnvironment = OCamlEnvironmentDescriptor(
            id = "opam_switch:5.3.0",
            name = "OCaml",
            kind = OCamlEnvironmentKind.OPAM_SWITCH,
            switchName = "5.3.0",
            canInstallTools = true,
        )
        val duneEnvironment = OCamlEnvironmentDescriptor(
            id = "dune_package_management:C:/work/camel",
            name = "Dune",
            kind = OCamlEnvironmentKind.DUNE_PACKAGE_MANAGEMENT,
            canInstallTools = true,
        )

        val opamCommands = createRequiredToolInstallCommandLines(
            opamEnvironment,
            opamExecutable = "opam",
            duneExecutable = "dune",
            workingDirectory = Path.of("."),
        )
        val duneCommands = createRequiredToolInstallCommandLines(
            duneEnvironment,
            opamExecutable = "opam",
            duneExecutable = "custom-dune",
            workingDirectory = Path.of("."),
        )

        assertEquals(1, opamCommands.size)
        assertTrue(opamCommands.single().parametersList.list.contains("utop"))
        assertEquals(
            listOf(
                listOf("tools", "install", "ocamllsp"),
                listOf("tools", "install", "ocamlformat"),
                listOf("tools", "install", "utop"),
            ),
            duneCommands.map { it.parametersList.list },
        )
        assertTrue(duneCommands.all { it.exePath == "custom-dune" })
    }

    @Test
    fun `LSP runtime key changes for every process-affecting setting`() {
        val environment = OCamlEnvironmentDescriptor(
            id = "opam_switch:5.3.0",
            name = "OCaml",
            kind = OCamlEnvironmentKind.OPAM_SWITCH,
            languageServer = OCamlToolStatus(
                availability = OCamlToolAvailability.AVAILABLE,
                executable = "ocamllsp",
            ),
        )
        val baseline = createLspRuntimeKey(
            environment,
            EnvironmentDiscoverySettings(additionalLspArguments = "--fallback-read-dot-merlin"),
            inheritedPath = "C:/bin",
        )

        assertFalse(
            baseline == createLspRuntimeKey(
                environment,
                EnvironmentDiscoverySettings(
                    opamExecutableOverride = "C:/custom/opam.exe",
                    additionalLspArguments = "--fallback-read-dot-merlin",
                ),
                inheritedPath = "C:/bin",
            ),
        )

        assertFalse(
            baseline == createLspRuntimeKey(
                environment,
                EnvironmentDiscoverySettings(lspExecutableOverride = "custom-lsp"),
                inheritedPath = "C:/bin",
            ),
        )
        assertFalse(
            baseline == createLspRuntimeKey(
                environment,
                EnvironmentDiscoverySettings(additionalLspArguments = "--stdio"),
                inheritedPath = "C:/bin",
            ),
        )
        assertFalse(
            baseline == createLspRuntimeKey(
                environment,
                EnvironmentDiscoverySettings(additionalLspArguments = "--fallback-read-dot-merlin"),
                inheritedPath = "D:/tools",
            ),
        )
    }

    @Test
    fun `environment probe cache invalidates when executable fingerprints change`() {
        val cache = EnvironmentProbeCache()
        var probes = 0
        val descriptor = OCamlEnvironmentDescriptor(
            id = "path:system",
            name = "OCaml",
            kind = OCamlEnvironmentKind.PATH,
        )
        val firstKey = EnvironmentProbeCacheKey("path:system", "ocamlc:1", EnvironmentDiscoverySettings())

        cache.getOrProbe(firstKey) { probes++; descriptor }
        cache.getOrProbe(firstKey) { probes++; descriptor }
        cache.getOrProbe(firstKey.copy(executableFingerprint = "ocamlc:2")) { probes++; descriptor }

        assertEquals(2, probes)
    }

    @Test
    fun `derives Dune language version from installed version output`() {
        assertEquals("3.24", parseDuneLanguageVersion("3.24.2"))
        assertEquals("3.17", parseDuneLanguageVersion("dune 3.17+dev"))
        assertNull(parseDuneLanguageVersion("version unavailable"))
    }

    @Test
    fun `untrusted status explains why tools are not probed`() {
        val snapshot = ToolchainDetectionSnapshot.blocked()

        assertEquals("Blocked until the project is trusted", snapshot.opam.status)
        assertEquals(snapshot.opam, snapshot.ocamllsp)
        assertEquals(snapshot.opam, snapshot.dune)
        assertEquals(snapshot.opam, snapshot.ocamlformat)
    }

    @Test
    fun `failed opam probe short circuits all opam-based tool probes`() {
        val commands = mutableListOf<String>()
        val snapshot = detectToolchain(
            ToolchainSettingsSnapshot(true, "opam", "5.3.0", "", "", ""),
            projectBasePath = null,
        ) { commandLine, _, _ ->
            commands += commandLine.commandLineString
            ToolProbeResult("Unavailable")
        }

        assertEquals(1, commands.size)
        assertTrue(commands.single().contains("opam --version"))
        assertEquals("Unavailable because opam could not be started", snapshot.dune.status)
    }

    @Test
    fun `wizard Dune probe uses the selected opam switch`() {
        val command = createDuneVersionProbeCommand(useOpam = true, opamSwitch = " 5.1.1 ")

        assertEquals("opam", command.exePath)
        assertEquals(
            listOf("exec", "--switch", "5.1.1", "--", "dune", "--version"),
            command.parametersList.list,
        )
    }

    @Test
    fun `independent probes run on the supplied executor`() {
        val executor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "managed-toolchain-probe-test")
        }
        val threadNames = Collections.synchronizedList(mutableListOf<String>())

        try {
            detectToolchain(
                ToolchainSettingsSnapshot(false, "", "", "", "", ""),
                projectBasePath = null,
                probeExecutor = executor,
            ) { _, _, _ ->
                threadNames += Thread.currentThread().name
                ToolProbeResult("OK", isAvailable = true)
            }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(4, threadNames.size)
        assertTrue(threadNames.all { it == "managed-toolchain-probe-test" })
    }
}
