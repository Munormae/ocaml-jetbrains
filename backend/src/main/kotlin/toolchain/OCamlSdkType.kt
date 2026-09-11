package dev.munormae.toolchain

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.AdditionalDataConfigurable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.SdkModificator
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.vfs.VirtualFile
import dev.munormae.OCamlBundle
import dev.munormae.icons.OCamlIcons
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon

class OCamlSdkType : SdkType(NAME) {
    @Deprecated("Use suggestHomePath(Path)")
    override fun suggestHomePath(): String? = System.getenv("OPAM_SWITCH_PREFIX")

    override fun suggestHomePath(path: Path): String? {
        val projectSwitch = path.toAbsolutePath().normalize().resolve("_opam")
        return projectSwitch.takeIf(Files::isDirectory)?.toString() ?: suggestHomePath()
    }

    override fun isValidSdkHome(path: String): Boolean = compilerAt(path) != null

    override fun suggestSdkName(currentSdkName: String?, sdkHome: String): String {
        val version = getVersionString(sdkHome)?.removePrefix("OCaml ") ?: Path.of(sdkHome).fileName.toString()
        return "OCaml $version"
    }

    override fun getVersionString(sdkHome: String): String? {
        val compiler = compilerAt(sdkHome) ?: return null
        return runCatching {
            val output = CapturingProcessHandler(
                GeneralCommandLine(compiler.toString()).withParameters("--version"),
            ).runProcess(VERSION_TIMEOUT_MS)
            output.stdout.trim().takeIf { output.exitCode == 0 && it.isNotEmpty() }?.let { "OCaml $it" }
        }.getOrNull()
    }

    override fun createAdditionalDataConfigurable(
        sdkModel: SdkModel,
        sdkModificator: SdkModificator,
    ): AdditionalDataConfigurable? = null

    override fun getPresentableName(): String = OCamlBundle.message("environment.sdk.name")

    override fun getIcon(): Icon = OCamlIcons.File

    override fun isRelevantForFile(project: Project, file: VirtualFile): Boolean =
        file.extension?.lowercase() in setOf("ml", "mli")

    private fun compilerAt(home: String): Path? {
        val bin = runCatching { Path.of(home, "bin") }.getOrNull() ?: return null
        val names = if (File.separatorChar == '\\') listOf("ocamlc.exe", "ocamlc") else listOf("ocamlc")
        return names.asSequence().map(bin::resolve).firstOrNull(Files::isRegularFile)
    }

    companion object {
        const val NAME = "OCAML_ENVIRONMENT"
        private const val VERSION_TIMEOUT_MS = 3_000

        fun getInstance(): OCamlSdkType = findInstance(OCamlSdkType::class.java)
    }
}
