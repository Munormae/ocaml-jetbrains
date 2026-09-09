package dev.munormae.lang.highlighting

import com.intellij.psi.tree.IElementType
import dev.munormae.lang.OCamlLanguage

object OCamlTokenTypes {
    @JvmField val KEYWORD = IElementType("OCAML_KEYWORD", OCamlLanguage)
    @JvmField val IDENTIFIER = IElementType("OCAML_IDENTIFIER", OCamlLanguage)
    @JvmField val CONSTRUCTOR = IElementType("OCAML_CONSTRUCTOR", OCamlLanguage)
    @JvmField val TYPE_VARIABLE = IElementType("OCAML_TYPE_VARIABLE", OCamlLanguage)
    @JvmField val LABEL = IElementType("OCAML_LABEL", OCamlLanguage)
    @JvmField val NUMBER = IElementType("OCAML_NUMBER", OCamlLanguage)
    @JvmField val STRING = IElementType("OCAML_STRING", OCamlLanguage)
    @JvmField val CHARACTER = IElementType("OCAML_CHARACTER", OCamlLanguage)
    @JvmField val COMMENT = IElementType("OCAML_COMMENT", OCamlLanguage)
    @JvmField val ATTRIBUTE = IElementType("OCAML_ATTRIBUTE", OCamlLanguage)
    @JvmField val OPERATOR = IElementType("OCAML_OPERATOR", OCamlLanguage)
    @JvmField val LPAREN = IElementType("OCAML_LPAREN", OCamlLanguage)
    @JvmField val RPAREN = IElementType("OCAML_RPAREN", OCamlLanguage)
    @JvmField val LBRACKET = IElementType("OCAML_LBRACKET", OCamlLanguage)
    @JvmField val RBRACKET = IElementType("OCAML_RBRACKET", OCamlLanguage)
    @JvmField val LBRACE = IElementType("OCAML_LBRACE", OCamlLanguage)
    @JvmField val RBRACE = IElementType("OCAML_RBRACE", OCamlLanguage)
}
