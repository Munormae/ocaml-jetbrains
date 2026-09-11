package dev.munormae.toolchain

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.ui.EditorNotifications
import java.nio.file.Path

class OCamlEnvironmentSdkService(private val project: Project) {
    fun synchronize(environment: OCamlEnvironmentDescriptor) {
        if (environment.prefix.isBlank() || !environment.compiler.isAvailable) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            ApplicationManager.getApplication().runWriteAction {
                val sdk = findOrCreateSdk(environment)
                if (ProjectRootManager.getInstance(project).projectSdk !== sdk) {
                    ProjectRootManager.getInstance(project).projectSdk = sdk
                }
            }
            EditorNotifications.getInstance(project).updateAllNotifications()
        }
    }

    private fun findOrCreateSdk(environment: OCamlEnvironmentDescriptor): Sdk {
        val sdkType = OCamlSdkType.getInstance()
        val table = ProjectJdkTable.getInstance()
        val normalizedHome = normalizePath(environment.prefix)
        val existing = table.getSdksOfType(sdkType).firstOrNull { sdk ->
            sdk.homePath?.let(::normalizePath) == normalizedHome
        }
        if (existing != null) {
            val modificator = existing.sdkModificator
            modificator.versionString = environment.compiler.version.takeIf(String::isNotBlank)?.let { "OCaml $it" }
            modificator.commitChanges()
            return existing
        }

        val baseName = environment.name
        val usedNames = table.getSdksOfType(sdkType).mapTo(mutableSetOf()) { it.name }
        val name = generateSequence(1) { it + 1 }
            .map { index -> if (index == 1) baseName else "$baseName ($index)" }
            .first { it !in usedNames }
        val sdk = table.createSdk(name, sdkType)
        sdk.sdkModificator.apply {
            homePath = environment.prefix
            versionString = environment.compiler.version.takeIf(String::isNotBlank)?.let { "OCaml $it" }
            commitChanges()
        }
        table.addJdk(sdk)
        return sdk
    }

    companion object {
        fun getInstance(project: Project): OCamlEnvironmentSdkService = project.service()
    }
}

private fun normalizePath(path: String): String = runCatching {
    Path.of(path).toAbsolutePath().normalize().toString()
}.getOrDefault(path)
