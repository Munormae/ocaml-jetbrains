package dev.munormae.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import dev.munormae.icons.OCamlIcons
import javax.swing.Icon

object OpamFileType : LanguageFileType(OpamLanguage) {
    override fun getName(): String = "OPAM"

    override fun getDescription(): String = "OPAM package manifest"

    override fun getDefaultExtension(): String = "opam"

    override fun getIcon(): Icon = OCamlIcons.Opam
}
