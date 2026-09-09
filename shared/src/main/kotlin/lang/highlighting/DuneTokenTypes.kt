package dev.munormae.lang.highlighting

import com.intellij.psi.tree.IElementType
import dev.munormae.lang.DuneLanguage

object DuneTokenTypes {
    @JvmField val KEYWORD = IElementType("DUNE_KEYWORD", DuneLanguage)
    @JvmField val ATOM = IElementType("DUNE_ATOM", DuneLanguage)
    @JvmField val TARGET = IElementType("DUNE_TARGET", DuneLanguage)
    @JvmField val VARIABLE = IElementType("DUNE_VARIABLE", DuneLanguage)
    @JvmField val NUMBER = IElementType("DUNE_NUMBER", DuneLanguage)
    @JvmField val STRING = IElementType("DUNE_STRING", DuneLanguage)
    @JvmField val COMMENT = IElementType("DUNE_COMMENT", DuneLanguage)
    @JvmField val LPAREN = IElementType("DUNE_LPAREN", DuneLanguage)
    @JvmField val RPAREN = IElementType("DUNE_RPAREN", DuneLanguage)
}
