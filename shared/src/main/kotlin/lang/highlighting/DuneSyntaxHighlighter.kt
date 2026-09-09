package dev.munormae.lang.highlighting

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

class DuneSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = DuneLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
        DuneTokenTypes.KEYWORD -> pack(KEYWORD)
        DuneTokenTypes.TARGET -> pack(TARGET)
        DuneTokenTypes.VARIABLE -> pack(VARIABLE)
        DuneTokenTypes.NUMBER -> pack(NUMBER)
        DuneTokenTypes.STRING -> pack(STRING)
        DuneTokenTypes.COMMENT -> pack(COMMENT)
        DuneTokenTypes.LPAREN, DuneTokenTypes.RPAREN -> pack(PARENTHESES)
        TokenType.BAD_CHARACTER -> pack(BAD_CHARACTER)
        else -> EMPTY
    }

    companion object {
        @JvmField val KEYWORD = TextAttributesKey.createTextAttributesKey("DUNE_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        @JvmField val TARGET = TextAttributesKey.createTextAttributesKey("DUNE_TARGET", DefaultLanguageHighlighterColors.STATIC_FIELD)
        @JvmField val VARIABLE = TextAttributesKey.createTextAttributesKey("DUNE_VARIABLE", DefaultLanguageHighlighterColors.METADATA)
        @JvmField val NUMBER = TextAttributesKey.createTextAttributesKey("DUNE_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
        @JvmField val STRING = TextAttributesKey.createTextAttributesKey("DUNE_STRING", DefaultLanguageHighlighterColors.STRING)
        @JvmField val COMMENT = TextAttributesKey.createTextAttributesKey("DUNE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
        @JvmField val PARENTHESES = TextAttributesKey.createTextAttributesKey("DUNE_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES)
        @JvmField val BAD_CHARACTER = TextAttributesKey.createTextAttributesKey("DUNE_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)
        private val EMPTY = emptyArray<TextAttributesKey>()
    }
}
