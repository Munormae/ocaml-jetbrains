package dev.munormae

import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.munormae.dune.run.DuneRunConfiguration
import dev.munormae.dune.run.DuneRunConfigurationType
import dev.munormae.lang.DuneLanguage
import dev.munormae.lang.highlighting.DuneSyntaxHighlighter
import org.jdom.Element

class DuneRunConfigurationRegistrationTest : BasePlatformTestCase() {
    fun testDuneRunConfigurationFactoriesAndEditorsAreRegistered() {
        val type = ConfigurationTypeUtil.findConfigurationType(DuneRunConfigurationType::class.java)

        assertEquals(
            listOf("Dune Build", "Dune Exec", "Dune Test"),
            type.configurationFactories.map { it.name },
        )
        type.configurationFactories.forEach { factory ->
            val configuration = factory.createTemplateConfiguration(project)
            assertInstanceOf(configuration, DuneRunConfiguration::class.java)

            val editor = configuration.configurationEditor
            try {
                assertNotNull(editor.component)
            } finally {
                Disposer.dispose(editor)
            }
        }

        val execFactory = type.configurationFactories.single { it.name == "Dune Exec" }
        val original = execFactory.createTemplateConfiguration(project) as DuneRunConfiguration
        original.target = "./bin/main.exe"
        original.duneArguments = "--profile release"
        original.programArguments = "--verbose"
        original.workingDirectory = "app"

        val xml = Element("configuration")
        original.writeExternal(xml)
        val restored = execFactory.createTemplateConfiguration(project) as DuneRunConfiguration
        restored.readExternal(xml)

        assertEquals(original.target, restored.target)
        assertEquals(original.duneArguments, restored.duneArguments)
        assertEquals(original.programArguments, restored.programArguments)
        assertEquals(original.workingDirectory, restored.workingDirectory)
    }

    fun testDuneSyntaxHighlighterIsRegistered() {
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(DuneLanguage, project, null)
        assertInstanceOf(highlighter, DuneSyntaxHighlighter::class.java)
    }
}
