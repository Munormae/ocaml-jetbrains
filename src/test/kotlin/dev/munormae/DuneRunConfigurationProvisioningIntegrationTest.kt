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
        assertEquals(
            DuneCommand.EXEC,
            (runManager.selectedConfiguration?.configuration as DuneRunConfiguration).command,
        )
    }
}
