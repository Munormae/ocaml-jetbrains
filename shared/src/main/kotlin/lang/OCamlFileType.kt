package dev.munormae.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import dev.munormae.icons.OCamlIcons
import javax.swing.Icon

object OCamlFileType : LanguageFileType(OCamlLanguage) {
    override fun getName(): String = "OCaml"

    override fun getDescription(): String = "OCaml source file"

    override fun getDefaultExtension(): String = "ml"

    override fun getIcon(): Icon = OCamlIcons.File
}
