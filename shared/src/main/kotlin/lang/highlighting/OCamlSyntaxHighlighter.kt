package dev.munormae.lang.highlighting

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

class OCamlSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = OCamlLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
        OCamlTokenTypes.KEYWORD -> pack(KEYWORD)
        OCamlTokenTypes.CONSTRUCTOR -> pack(CONSTRUCTOR)
        OCamlTokenTypes.TYPE_VARIABLE -> pack(TYPE_VARIABLE)
        OCamlTokenTypes.LABEL -> pack(LABEL)
        OCamlTokenTypes.NUMBER -> pack(NUMBER)
        OCamlTokenTypes.STRING -> pack(STRING)
        OCamlTokenTypes.CHARACTER -> pack(CHARACTER)
        OCamlTokenTypes.COMMENT -> pack(COMMENT)
        OCamlTokenTypes.ATTRIBUTE -> pack(ATTRIBUTE)
        OCamlTokenTypes.OPERATOR -> pack(OPERATOR)
        TokenType.BAD_CHARACTER -> pack(BAD_CHARACTER)
        else -> EMPTY
    }

    companion object {
        @JvmField val KEYWORD = TextAttributesKey.createTextAttributesKey("OCAML_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        @JvmField val CONSTRUCTOR = TextAttributesKey.createTextAttributesKey("OCAML_CONSTRUCTOR", DefaultLanguageHighlighterColors.CLASS_NAME)
        @JvmField val TYPE_VARIABLE = TextAttributesKey.createTextAttributesKey("OCAML_TYPE_VARIABLE", DefaultLanguageHighlighterColors.PARAMETER)
        @JvmField val LABEL = TextAttributesKey.createTextAttributesKey("OCAML_LABEL", DefaultLanguageHighlighterColors.PARAMETER)
        @JvmField val NUMBER = TextAttributesKey.createTextAttributesKey("OCAML_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
        @JvmField val STRING = TextAttributesKey.createTextAttributesKey("OCAML_STRING", DefaultLanguageHighlighterColors.STRING)
        @JvmField val CHARACTER = TextAttributesKey.createTextAttributesKey("OCAML_CHARACTER", DefaultLanguageHighlighterColors.STRING)
        @JvmField val COMMENT = TextAttributesKey.createTextAttributesKey("OCAML_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)
        @JvmField val ATTRIBUTE = TextAttributesKey.createTextAttributesKey("OCAML_ATTRIBUTE", DefaultLanguageHighlighterColors.METADATA)
        @JvmField val OPERATOR = TextAttributesKey.createTextAttributesKey("OCAML_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
        @JvmField val BAD_CHARACTER = TextAttributesKey.createTextAttributesKey("OCAML_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)
        private val EMPTY = emptyArray<TextAttributesKey>()
    }
}
