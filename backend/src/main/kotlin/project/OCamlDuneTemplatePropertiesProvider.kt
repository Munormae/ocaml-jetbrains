package dev.munormae.project

import com.intellij.ide.fileTemplates.DefaultTemplatePropertiesProvider
import com.intellij.psi.PsiDirectory
import dev.munormae.toolchain.DEFAULT_DUNE_LANGUAGE_VERSION
import java.util.Properties

class OCamlDuneTemplatePropertiesProvider : DefaultTemplatePropertiesProvider {
    override fun fillProperties(directory: PsiDirectory, properties: Properties) {
        properties.putIfAbsent(DUNE_LANGUAGE_VERSION_PROPERTY, DEFAULT_DUNE_LANGUAGE_VERSION)
    }

    companion object {
        const val DUNE_LANGUAGE_VERSION_PROPERTY = "DUNE_LANGUAGE_VERSION"
    }
}
