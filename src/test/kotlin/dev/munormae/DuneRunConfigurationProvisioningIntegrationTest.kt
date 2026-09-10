package dev.munormae

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.munormae.dune.run.DuneCommand
import dev.munormae.dune.run.DuneRunConfiguration
import dev.munormae.dune.run.DuneRunConfigurationSpec
import dev.munormae.dune.run.DuneRunConfigurationType
import dev.munormae.dune.run.provisionDuneRunConfigurations

class DuneRunConfigurationProvisioningIntegrationTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            val runManager = RunManager.getInstance(project)
            runManager.allSettings
                .filter { it.configuration is DuneRunConfiguration }
                .forEach(runManager::removeConfiguration)
        } finally {
            super.tearDown()
        }
    }

    fun testProvisioningCreatesSelectsAndDoesNotDuplicateConfigurations() {
        val specs = listOf(
            DuneRunConfigurationSpec(DuneCommand.BUILD, "Dune Build"),
            DuneRunConfigurationSpec(
                DuneCommand.EXEC,
                "Dune Run camel-app",
                "./bin/main.exe",
                legacyTarget = "camel-app",
            ),
        )

        provisionDuneRunConfigurations(
            project,
            specs.map { spec ->
                if (spec.command == DuneCommand.EXEC) spec.copy(target = "camel-app", legacyTarget = "") else spec
            },
        )
        provisionDuneRunConfigurations(
            project,
            specs.map { it.copy(workingDirectory = project.basePath.orEmpty()) },
        )

        val configurationType = ConfigurationTypeUtil.findConfigurationType(
            DuneRunConfigurationType::class.java,
        )
        val runManager = RunManager.getInstance(project)
        val settings = runManager.allSettings.filter { it.type == configurationType }
        val configurations = settings.map { it.configuration as DuneRunConfiguration }

        assertEquals(2, configurations.size)
        assertEquals(setOf(DuneCommand.BUILD, DuneCommand.EXEC), configurations.map { it.command }.toSet())
        assertEquals("./bin/main.exe", configurations.single { it.command == DuneCommand.EXEC }.target)
        assertTrue(configurations.all { it.managedByPlugin })
        assertTrue(configurations.all { it.modelId.startsWith("dune-model-v1|") })
        assertEquals(
            DuneCommand.EXEC,
            (runManager.selectedConfiguration?.configuration as DuneRunConfiguration).command,
        )
    }

    fun testProvisioningRemovesDisappearedManagedConfigurations() {
        provisionDuneRunConfigurations(
            project,
            listOf(DuneRunConfigurationSpec(DuneCommand.EXEC, "Dune Run server", "./server.exe")),
        )
        provisionDuneRunConfigurations(
            project,
            listOf(DuneRunConfigurationSpec(DuneCommand.EXEC, "Dune Run api", "./api.exe")),
        )

        val configurations = RunManager.getInstance(project).allSettings
            .mapNotNull { it.configuration as? DuneRunConfiguration }
            .filter(DuneRunConfiguration::managedByPlugin)

        assertEquals(listOf("./api.exe"), configurations.map { it.target })
        assertTrue(configurations.single().managedByPlugin)
    }

    fun testProvisioningLeavesUserConfigurationsAlone() {
        val configurationType = ConfigurationTypeUtil.findConfigurationType(DuneRunConfigurationType::class.java)
        val factory = configurationType.configurationFactories.single { it.name == "Dune Build" }
        val runManager = RunManager.getInstance(project)
        val userSettings = runManager.createConfiguration("My Dune Build", factory)
        val userConfiguration = userSettings.configuration as DuneRunConfiguration
        userConfiguration.workingDirectory = "custom-root"
        runManager.addConfiguration(userSettings)

        provisionDuneRunConfigurations(
            project,
            listOf(DuneRunConfigurationSpec(DuneCommand.BUILD, "Dune Build")),
        )
        provisionDuneRunConfigurations(project, emptyList())

        val remaining = runManager.allSettings.mapNotNull { it.configuration as? DuneRunConfiguration }
        assertEquals(listOf(userConfiguration), remaining)
        assertFalse(userConfiguration.managedByPlugin)
    }

    fun testProvisioningAdoptsPreOwnershipGeneratedConfiguration() {
        val runManager = RunManager.getInstance(project)
        val configurationType = ConfigurationTypeUtil.findConfigurationType(DuneRunConfigurationType::class.java)
        val factory = configurationType.configurationFactories.single { it.name == "Dune Exec" }
        val legacySettings = runManager.createConfiguration("Dune Run camel-app", factory)
        val legacyConfiguration = legacySettings.configuration as DuneRunConfiguration
        legacyConfiguration.target = "camel-app"
        legacyConfiguration.workingDirectory = project.basePath.orEmpty()
        runManager.addConfiguration(legacySettings)

        provisionDuneRunConfigurations(
            project,
            listOf(
                DuneRunConfigurationSpec(
                    DuneCommand.EXEC,
                    "Dune Run camel-app",
                    "./bin/main.exe",
                    project.basePath.orEmpty(),
                    legacyTarget = "camel-app",
                ),
            ),
        )

        val configurations = runManager.allSettings
            .mapNotNull { it.configuration as? DuneRunConfiguration }
        assertEquals(1, configurations.size)
        assertEquals(legacyConfiguration, configurations.single())
        assertEquals("./bin/main.exe", legacyConfiguration.target)
        assertTrue(legacyConfiguration.managedByPlugin)
        assertTrue(legacyConfiguration.modelId.startsWith("dune-model-v1|"))
        assertEquals("Dune Run camel-app", legacyConfiguration.lastGeneratedName)
        assertEquals("./bin/main.exe", legacyConfiguration.lastGeneratedTarget)
        assertEquals(project.basePath.orEmpty(), legacyConfiguration.lastGeneratedWorkingDirectory)
    }

    fun testProvisioningAdoptsPreOwnershipExecutableWithoutPublicName() {
        val runManager = RunManager.getInstance(project)
        val configurationType = ConfigurationTypeUtil.findConfigurationType(DuneRunConfigurationType::class.java)
        val factory = configurationType.configurationFactories.single { it.name == "Dune Exec" }
        val legacySettings = runManager.createConfiguration("Dune Run server", factory)
        val legacyConfiguration = legacySettings.configuration as DuneRunConfiguration
        legacyConfiguration.target = "./server.exe"
        legacyConfiguration.workingDirectory = project.basePath.orEmpty()
        runManager.addConfiguration(legacySettings)

        provisionDuneRunConfigurations(
            project,
            listOf(
                DuneRunConfigurationSpec(
                    DuneCommand.EXEC,
                    "Dune Run server",
                    "./server.exe",
                    project.basePath.orEmpty(),
                ),
            ),
        )

        val configurations = runManager.allSettings
            .mapNotNull { it.configuration as? DuneRunConfiguration }
        assertEquals(listOf(legacyConfiguration), configurations)
        assertTrue(legacyConfiguration.managedByPlugin)
        assertTrue(legacyConfiguration.modelId.startsWith("dune-model-v1|"))
    }

    fun testProgramArgumentsDetachManagedConfigurationBeforeStaleRemoval() {
        val spec = DuneRunConfigurationSpec(
            DuneCommand.EXEC,
            "Dune Run server",
            "./server.exe",
            project.basePath.orEmpty(),
        )
        provisionDuneRunConfigurations(project, listOf(spec))
        val configuration = RunManager.getInstance(project).allSettings
            .mapNotNull { it.configuration as? DuneRunConfiguration }
            .single()
        configuration.programArguments = "--port 8080"

        provisionDuneRunConfigurations(project, emptyList())

        val remaining = RunManager.getInstance(project).allSettings
            .mapNotNull { it.configuration as? DuneRunConfiguration }
        assertEquals(listOf(configuration), remaining)
        assertFalse(configuration.managedByPlugin)
        assertEquals("", configuration.modelId)
        assertEquals("--port 8080", configuration.programArguments)
    }

    fun testManualRenameDetachesManagedConfigurationWithoutCreatingDuplicate() {
        val spec = DuneRunConfigurationSpec(
            DuneCommand.EXEC,
            "Dune Run server",
            "./server.exe",
            project.basePath.orEmpty(),
        )
        provisionDuneRunConfigurations(project, listOf(spec))
        val runManager = RunManager.getInstance(project)
        val settings = runManager.allSettings.single()
        val configuration = settings.configuration as DuneRunConfiguration
        settings.name = "Local server"

        provisionDuneRunConfigurations(project, listOf(spec))

        val remaining = runManager.allSettings
            .mapNotNull { it.configuration as? DuneRunConfiguration }
        assertEquals(listOf(configuration), remaining)
        assertEquals("Local server", settings.name)
        assertFalse(configuration.managedByPlugin)
    }

    fun testUntouchedManagedConfigurationFollowsGeneratedDisplayName() {
        val workingDirectory = project.basePath.orEmpty()
        provisionDuneRunConfigurations(
            project,
            listOf(DuneRunConfigurationSpec(DuneCommand.EXEC, "Dune Run old-name", "./main.exe", workingDirectory)),
        )
        val runManager = RunManager.getInstance(project)
        val settings = runManager.allSettings.single()

        provisionDuneRunConfigurations(
            project,
            listOf(DuneRunConfigurationSpec(DuneCommand.EXEC, "Dune Run new-name", "./main.exe", workingDirectory)),
        )

        val configuration = settings.configuration as DuneRunConfiguration
        assertEquals(1, runManager.allSettings.size)
        assertEquals("Dune Run new-name", settings.name)
        assertEquals("Dune Run new-name", configuration.lastGeneratedName)
        assertTrue(configuration.managedByPlugin)
    }

    fun testUntouchedManagedConfigurationMovesWithoutUniqueNameSuffix() {
        val workingDirectory = project.basePath.orEmpty()
        provisionDuneRunConfigurations(
            project,
            listOf(DuneRunConfigurationSpec(DuneCommand.EXEC, "Dune Run main", "./bin/main.exe", workingDirectory)),
        )
        val runManager = RunManager.getInstance(project)
        val settings = runManager.allSettings.single()

        provisionDuneRunConfigurations(
            project,
            listOf(DuneRunConfigurationSpec(DuneCommand.EXEC, "Dune Run main", "./app/main.exe", workingDirectory)),
        )

        val configuration = settings.configuration as DuneRunConfiguration
        assertEquals(listOf(settings), runManager.allSettings)
        assertEquals("Dune Run main", settings.name)
        assertEquals("./app/main.exe", configuration.target)
        assertTrue(configuration.managedByPlugin)
    }

    fun testCustomizedManagedConfigurationWithoutBaselineIsDetachedDuringUpgrade() {
        val spec = DuneRunConfigurationSpec(
            DuneCommand.EXEC,
            "Dune Run server",
            "./server.exe",
            project.basePath.orEmpty(),
        )
        provisionDuneRunConfigurations(project, listOf(spec))
        val runManager = RunManager.getInstance(project)
        val configuration = runManager.allSettings.single().configuration as DuneRunConfiguration
        configuration.lastGeneratedName = ""
        configuration.lastGeneratedTarget = ""
        configuration.lastGeneratedWorkingDirectory = ""
        configuration.programArguments = "--verbose"

        provisionDuneRunConfigurations(project, listOf(spec))

        assertEquals(1, runManager.allSettings.size)
        assertFalse(configuration.managedByPlugin)
        assertEquals("--verbose", configuration.programArguments)
    }
}
