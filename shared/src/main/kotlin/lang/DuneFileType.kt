package dev.munormae.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import dev.munormae.icons.OCamlIcons
import javax.swing.Icon

object DuneFileType : LanguageFileType(DuneLanguage) {
    override fun getName(): String = "Dune"

    override fun getDescription(): String = "Dune build configuration"

    override fun getDefaultExtension(): String = ""

    override fun getIcon(): Icon = OCamlIcons.Dune
}
