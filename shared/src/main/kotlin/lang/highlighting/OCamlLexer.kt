package dev.munormae.lang.highlighting

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

class OCamlLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        tokenStart = startOffset
        tokenEnd = startOffset
        locateToken()
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd

    override fun advance() {
        tokenStart = tokenEnd
        locateToken()
    }

    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = bufferEnd

    private fun locateToken() {
        if (tokenStart >= bufferEnd) {
            tokenType = null
            tokenEnd = bufferEnd
            return
        }

        val first = buffer[tokenStart]
        when {
            first.isWhitespace() -> scanWhitespace()
            startsWith("(*") -> scanNestedComment()
            first == '"' -> scanQuoted('"', OCamlTokenTypes.STRING)
            first == '{' && scanQuotedStringExtension() -> Unit
            first == '\'' -> scanCharacterOrTypeVariable()
            first.isDigit() -> scanNumber()
            first == '~' || first == '?' -> scanLabelOrOperator()
            first == '[' && (peek(1) == '@' || peek(1) == '%') -> scanAttribute()
            first.isIdentifierStart() -> scanIdentifier()
            else -> scanPunctuationOrOperator(first)
        }
    }

    private fun scanWhitespace() {
        tokenEnd = tokenStart + 1
        while (tokenEnd < bufferEnd && buffer[tokenEnd].isWhitespace()) tokenEnd++
        tokenType = TokenType.WHITE_SPACE
    }

    private fun scanNestedComment() {
        var depth = 1
        tokenEnd = tokenStart + 2
        while (tokenEnd < bufferEnd && depth > 0) {
            when {
                startsWith("(*", tokenEnd) -> {
                    depth++
                    tokenEnd += 2
                }
                startsWith("*)", tokenEnd) -> {
                    depth--
                    tokenEnd += 2
                }
                else -> tokenEnd++
            }
        }
        tokenType = OCamlTokenTypes.COMMENT
    }

    private fun scanQuoted(quote: Char, type: IElementType) {
        tokenEnd = tokenStart + 1
        var escaped = false
        while (tokenEnd < bufferEnd) {
            val current = buffer[tokenEnd++]
            if (current == quote && !escaped) break
            escaped = current == '\\' && !escaped
            if (current != '\\') escaped = false
        }
        tokenType = type
    }

    private fun scanQuotedStringExtension(): Boolean {
        var markerEnd = tokenStart + 1
        while (markerEnd < bufferEnd && buffer[markerEnd].isIdentifierPart()) markerEnd++
        if (markerEnd >= bufferEnd || buffer[markerEnd] != '|') return false

        val marker = buffer.subSequence(tokenStart + 1, markerEnd).toString()
        val terminator = "|$marker}"
        val contentStart = markerEnd + 1
        val terminatorStart = indexOf(terminator, contentStart)
        tokenEnd = if (terminatorStart >= 0) terminatorStart + terminator.length else bufferEnd
        tokenType = OCamlTokenTypes.STRING
        return true
    }

    private fun indexOf(value: String, startOffset: Int): Int {
        val lastStart = bufferEnd - value.length
        for (index in startOffset..lastStart) {
            if (startsWith(value, index)) return index
        }
        return -1
    }

    private fun scanCharacterOrTypeVariable() {
        val close = findCharacterLiteralEnd()
        if (close >= 0) {
            tokenEnd = close + 1
            tokenType = OCamlTokenTypes.CHARACTER
            return
        }

        tokenEnd = tokenStart + 1
        while (tokenEnd < bufferEnd && buffer[tokenEnd].isIdentifierPart()) tokenEnd++
        tokenType = OCamlTokenTypes.TYPE_VARIABLE
    }

    private fun findCharacterLiteralEnd(): Int {
        var index = tokenStart + 1
        if (index >= bufferEnd || buffer[index] == '\n' || buffer[index] == '\r') return -1
        if (buffer[index] == '\\') index += 2 else index++
        return if (index < bufferEnd && buffer[index] == '\'') index else -1
    }

    private fun scanNumber() {
        tokenEnd = tokenStart + 1
        while (tokenEnd < bufferEnd) {
            val current = buffer[tokenEnd]
            if (current.isLetterOrDigit() || current == '_' || current == '.' ||
                current == '\'' || current in "+-" && isExponentContext(tokenEnd)
            ) tokenEnd++ else break
        }
        tokenType = OCamlTokenTypes.NUMBER
    }

    private fun isExponentContext(index: Int): Boolean {
        val current = buffer[index]
        if (current !in "+-") return true
        return index > tokenStart && buffer[index - 1] in "eEpP"
    }

    private fun scanLabelOrOperator() {
        tokenEnd = tokenStart + 1
        if (tokenEnd < bufferEnd && buffer[tokenEnd].isIdentifierStart()) {
            tokenEnd++
            while (tokenEnd < bufferEnd && buffer[tokenEnd].isIdentifierPart()) tokenEnd++
            if (tokenEnd < bufferEnd && buffer[tokenEnd] == ':') tokenEnd++
            tokenType = OCamlTokenTypes.LABEL
        } else {
            scanOperator()
        }
    }

    private fun scanAttribute() {
        tokenEnd = tokenStart + 2
        while (tokenEnd < bufferEnd && buffer[tokenEnd] == buffer[tokenStart + 1]) tokenEnd++
        while (tokenEnd < bufferEnd && (buffer[tokenEnd].isIdentifierPart() || buffer[tokenEnd] == '.')) tokenEnd++
        tokenType = OCamlTokenTypes.ATTRIBUTE
    }

    private fun scanIdentifier() {
        tokenEnd = tokenStart + 1
        while (tokenEnd < bufferEnd && buffer[tokenEnd].isIdentifierPart()) tokenEnd++
        val text = buffer.subSequence(tokenStart, tokenEnd).toString()
        tokenType = when {
            text in KEYWORDS -> OCamlTokenTypes.KEYWORD
            text.firstOrNull()?.isUpperCase() == true -> OCamlTokenTypes.CONSTRUCTOR
            else -> OCamlTokenTypes.IDENTIFIER
        }
    }

    private fun scanPunctuationOrOperator(first: Char) {
        val single = when (first) {
            '(' -> OCamlTokenTypes.LPAREN
            ')' -> OCamlTokenTypes.RPAREN
            '[' -> OCamlTokenTypes.LBRACKET
            ']' -> OCamlTokenTypes.RBRACKET
            '{' -> OCamlTokenTypes.LBRACE
            '}' -> OCamlTokenTypes.RBRACE
            else -> null
        }
        if (single != null) {
            tokenEnd = tokenStart + 1
            tokenType = single
        } else if (first in OPERATOR_CHARS) {
            scanOperator()
        } else {
            tokenEnd = tokenStart + 1
            tokenType = TokenType.BAD_CHARACTER
        }
    }

    private fun scanOperator() {
        tokenEnd = tokenStart + 1
        while (tokenEnd < bufferEnd && buffer[tokenEnd] in OPERATOR_CHARS) tokenEnd++
        tokenType = OCamlTokenTypes.OPERATOR
    }

    private fun startsWith(value: String, offset: Int = tokenStart): Boolean {
        if (offset + value.length > bufferEnd) return false
        return value.indices.all { buffer[offset + it] == value[it] }
    }

    private fun peek(delta: Int): Char? = buffer.getOrNull(tokenStart + delta)?.takeIf { tokenStart + delta < bufferEnd }
    private fun Char.isIdentifierStart(): Boolean = this == '_' || isLetter()
    private fun Char.isIdentifierPart(): Boolean = this == '_' || this == '\'' || isLetterOrDigit()

    companion object {
        private val KEYWORDS = setOf(
            "and", "as", "assert", "asr", "begin", "class", "constraint", "do", "done", "downto",
            "else", "end", "exception", "external", "false", "for", "fun", "function", "functor", "if",
            "in", "include", "inherit", "initializer", "land", "lazy", "let", "lor", "lsl", "lsr", "lxor",
            "match", "method", "mod", "module", "mutable", "new", "nonrec", "object", "of", "open", "or",
            "private", "rec", "sig", "struct", "then", "to", "true", "try", "type", "val", "virtual",
            "when", "while", "with",
        )
        private const val OPERATOR_CHARS = "!\$%&*+-./:<=>?@^|~#;,:`"
    }
}
