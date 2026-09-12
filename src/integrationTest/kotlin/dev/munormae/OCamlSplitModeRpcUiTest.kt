package dev.munormae

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.config.ConfigurationStorage
import com.intellij.ide.starter.config.splitMode
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.junit5.hyphenateWithClass
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.CurrentTestMethod
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class OCamlSplitModeRpcUiTest {
    @Test
    fun `settings refresh crosses split mode RPC and updates the frontend UI`(@TempDir projectPath: Path) {
        ConfigurationStorage.splitMode(true)
        val pluginPath = Path.of(requireNotNull(System.getProperty("path.to.build.plugin")))
        val licenseKey = requireNotNull(System.getenv("LICENSE_KEY")) {
            "LICENSE_KEY must contain the Base64-encoded IntelliJ IDEA Ultimate offline activation file"
        }
        Files.writeString(projectPath.resolve("dune-project"), "(lang dune 3.0)\n")
        Files.writeString(projectPath.resolve("main.ml"), "let () = print_endline \"Split Mode RPC smoke test\"\n")

        Starter.newContext(
            CurrentTestMethod.hyphenateWithClass(),
            TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(projectPath)),
        ).apply {
            setLicense(licenseKey)
            addProjectToTrustedLocations()
            applyVMOptionsPatch {
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("ide.show.tips.on.startup.default.value", false)
            }
            PluginConfigurator(this).installPluginFromPath(pluginPath)
        }.runIdeWithDriver().useDriverAndCloseIde {
            waitForIndicators(5.minutes)

            ideFrame {
                driver.invokeAction("ShowSettings", now = false)
                dialog(title = "Settings") {
                    x { byVisibleText("OCaml") }.click()
                    button("Refresh").click()

                    waitFor("OCaml environment state to cross the frontend/backend RPC boundary", 1.minutes) {
                        val texts = getAllTexts().map { it.text }
                        texts.none { it == BACKEND_CONNECTION_FAILURE } &&
                            texts.any { it.startsWith("OCaml ") } &&
                            texts.count { it.contains("Missing") || it.startsWith("✓") } >= TOOL_COUNT
                    }

                    waitFor("RPC-delivered Dune model state to be rendered in Settings", 30.seconds) {
                        getAllTexts().any { text ->
                            text.text == "Up to date" ||
                                text.text == "Not a Dune project" ||
                                text.text.startsWith("Sync failed")
                        }
                    }
                    assertTrue(
                        getAllTexts().none { it.text == BACKEND_CONNECTION_FAILURE },
                        "The frontend did not receive a toolchain snapshot from the backend",
                    )
                    button("Cancel").click()
                }
            }
        }
    }

    private companion object {
        const val TOOL_COUNT = 4
        const val BACKEND_CONNECTION_FAILURE = "Unavailable: backend connection failed"
    }
}
