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
    private var tokenState = DEFAULT_STATE
    private var nextState = DEFAULT_STATE
    private var quotedStringMarker: String? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        tokenStart = startOffset
        tokenEnd = startOffset
        nextState = initialState
        quotedStringMarker = if (isQuotedStringState(initialState)) {
            recoverQuotedStringMarker(startOffset, initialState)
        } else {
            null
        }
        locateToken()
    }

    override fun getState(): Int = tokenState
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
        tokenState = nextState
        if (tokenStart >= bufferEnd) {
            tokenType = null
            tokenEnd = bufferEnd
            return
        }

        when {
            tokenState == STRING_STATE || tokenState == ESCAPED_STRING_STATE -> {
                scanString(openingQuote = false)
                return
            }

            isQuotedStringState(tokenState) -> {
                scanQuotedString(contentStart = tokenStart)
                return
            }

            isCommentState(tokenState) -> {
                scanNestedComment(openingDelimiter = false)
                return
            }
        }

        val first = buffer[tokenStart]
        when {
            first.isWhitespace() -> scanWhitespace()
            startsWith("(*") -> scanNestedComment(openingDelimiter = true)
            first == '"' -> scanString(openingQuote = true)
            first == '{' && scanQuotedStringExtension() -> Unit
            first == '\'' -> scanCharacterOrTypeVariable()
            first in '0'..'9' -> scanNumber()
            first == '#' && scanLineDirective() -> Unit
            first == '\\' && peek(1) == '#' && peek(2)?.isOcamlLowercaseIdentifierStart() == true ->
                scanRawIdentifier()
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

    private fun scanNestedComment(openingDelimiter: Boolean) {
        var depth = if (openingDelimiter) 1 else commentDepth(tokenState)
        var mode = if (openingDelimiter) COMMENT_CODE_MODE else commentMode(tokenState)
        tokenEnd = tokenStart + if (openingDelimiter) 2 else 0

        while (tokenEnd < bufferEnd) {
            if (mode == COMMENT_STRING_MODE || mode == COMMENT_ESCAPED_STRING_MODE) {
                var escaped = mode == COMMENT_ESCAPED_STRING_MODE
                val current = buffer[tokenEnd]
                if (current == '\n' || current == '\r') {
                    consumeLineBreak()
                    nextState = commentState(
                        depth,
                        if (escaped) COMMENT_ESCAPED_STRING_MODE else COMMENT_STRING_MODE,
                    )
                    tokenType = OCamlTokenTypes.COMMENT
                    return
                }

                tokenEnd++
                if (current == '"' && !escaped) {
                    mode = COMMENT_CODE_MODE
                    continue
                }
                escaped = current == '\\' && !escaped
                if (current != '\\') escaped = false
                mode = if (escaped) COMMENT_ESCAPED_STRING_MODE else COMMENT_STRING_MODE
                continue
            }

            when {
                startsWith("(*", tokenEnd) -> {
                    depth++
                    tokenEnd += 2
                }

                startsWith("*)", tokenEnd) -> {
                    depth--
                    tokenEnd += 2
                    if (depth == 0) {
                        nextState = DEFAULT_STATE
                        tokenType = OCamlTokenTypes.COMMENT
                        return
                    }
                }

                buffer[tokenEnd] == '"' -> {
                    mode = COMMENT_STRING_MODE
                    tokenEnd++
                }

                buffer[tokenEnd] == '\'' -> {
                    val characterEnd = findCharacterLiteralEnd(tokenEnd)
                    tokenEnd = if (characterEnd >= 0) characterEnd + 1 else tokenEnd + 1
                }

                buffer[tokenEnd] == '{' -> {
                    val opening = quotedStringOpeningAt(tokenEnd)
                    if (opening == null) {
                        tokenEnd++
                    } else {
                        val terminator = "|${opening.marker}}"
                        val terminatorStart = indexOf(terminator, opening.contentStart)
                        if (terminatorStart < 0) {
                            tokenEnd = bufferEnd
                            nextState = commentState(depth, COMMENT_CODE_MODE)
                            tokenType = OCamlTokenTypes.COMMENT
                            return
                        }
                        tokenEnd = terminatorStart + terminator.length
                    }
                }

                buffer[tokenEnd] == '\n' || buffer[tokenEnd] == '\r' -> {
                    consumeLineBreak()
                    nextState = commentState(depth, COMMENT_CODE_MODE)
                    tokenType = OCamlTokenTypes.COMMENT
                    return
                }

                else -> tokenEnd++
            }
        }

        nextState = commentState(depth, mode)
        tokenType = OCamlTokenTypes.COMMENT
    }

    private fun scanString(openingQuote: Boolean) {
        tokenEnd = tokenStart + if (openingQuote) 1 else 0
        var escaped = tokenState == ESCAPED_STRING_STATE
        while (tokenEnd < bufferEnd) {
            val current = buffer[tokenEnd]
            if (current == '\n' || current == '\r') {
                consumeLineBreak()
                nextState = if (escaped) ESCAPED_STRING_STATE else STRING_STATE
                tokenType = OCamlTokenTypes.STRING
                return
            }

            tokenEnd++
            if (current == '"' && !escaped) {
                nextState = DEFAULT_STATE
                tokenType = OCamlTokenTypes.STRING
                return
            }
            escaped = current == '\\' && !escaped
            if (current != '\\') escaped = false
        }

        nextState = if (escaped) ESCAPED_STRING_STATE else STRING_STATE
        tokenType = OCamlTokenTypes.STRING
    }

    private fun scanQuotedStringExtension(): Boolean {
        val opening = quotedStringOpeningAt(tokenStart) ?: return false
        quotedStringMarker = opening.marker
        scanQuotedString(contentStart = opening.contentStart)
        return true
    }

    private fun scanQuotedString(contentStart: Int) {
        tokenEnd = contentStart
        val marker = quotedStringMarker
        val continuationState = marker?.let(::quotedStringState) ?: tokenState
        val terminator = marker?.let { "|$it}" }

        while (tokenEnd < bufferEnd) {
            if (terminator != null && startsWith(terminator, tokenEnd)) {
                tokenEnd += terminator.length
                quotedStringMarker = null
                nextState = DEFAULT_STATE
                tokenType = OCamlTokenTypes.STRING
                return
            }

            val current = buffer[tokenEnd]
            if (current == '\n' || current == '\r') {
                consumeLineBreak()
                nextState = continuationState
                tokenType = OCamlTokenTypes.STRING
                return
            }
            tokenEnd++
        }

        nextState = continuationState
        tokenType = OCamlTokenTypes.STRING
    }

    private fun recoverQuotedStringMarker(startOffset: Int, state: Int): String? {
        val closedMarkers = mutableSetOf<String>()
        for (index in startOffset - 1 downTo 0) {
            quotedStringClosingAt(index)?.let(closedMarkers::add)
            val opening = quotedStringOpeningAt(index) ?: continue
            if (quotedStringState(opening.marker) != state) continue
            if (opening.marker !in closedMarkers) return opening.marker
        }
        return null
    }

    private fun quotedStringClosingAt(offset: Int): String? {
        if (buffer.getOrNull(offset) != '|' || offset >= bufferEnd) return null
        var markerEnd = offset + 1
        while (markerEnd < bufferEnd && buffer[markerEnd].isQuotedStringMarkerChar()) markerEnd++
        if (markerEnd >= bufferEnd || buffer[markerEnd] != '}') return null
        return buffer.subSequence(offset + 1, markerEnd).toString()
    }

    private fun quotedStringOpeningAt(offset: Int): QuotedStringOpening? {
        if (buffer.getOrNull(offset) != '{' || offset >= bufferEnd) return null
        var markerStart = offset + 1
        if (buffer.getOrNull(markerStart) == '%') {
            markerStart++
            if (buffer.getOrNull(markerStart) == '%') markerStart++
            markerStart = attributeIdEnd(markerStart) ?: return null
            if (buffer.getOrNull(markerStart) == '|') {
                return QuotedStringOpening(marker = "", contentStart = markerStart + 1)
            }
            if (buffer.getOrNull(markerStart) != ' ' && buffer.getOrNull(markerStart) != '\t') return null
            while (buffer.getOrNull(markerStart) == ' ' || buffer.getOrNull(markerStart) == '\t') markerStart++
        }

        var markerEnd = markerStart
        while (markerEnd < bufferEnd && buffer[markerEnd].isQuotedStringMarkerChar()) markerEnd++
        if (markerEnd >= bufferEnd || buffer[markerEnd] != '|') return null
        return QuotedStringOpening(
            marker = buffer.subSequence(markerStart, markerEnd).toString(),
            contentStart = markerEnd + 1,
        )
    }

    private fun attributeIdEnd(startOffset: Int): Int? {
        var index = startOffset
        if (buffer.getOrNull(index)?.isIdentifierStart() != true) return null
        index++
        while (buffer.getOrNull(index)?.isIdentifierPart() == true) index++
        while (buffer.getOrNull(index) == '.') {
            index++
            if (buffer.getOrNull(index)?.isIdentifierStart() != true) return null
            index++
            while (buffer.getOrNull(index)?.isIdentifierPart() == true) index++
        }
        return index
    }

    private fun indexOf(value: String, startOffset: Int): Int {
        val lastStart = bufferEnd - value.length
        if (startOffset > lastStart) return -1
        for (index in startOffset..lastStart) {
            if (startsWith(value, index)) return index
        }
        return -1
    }

    private fun scanCharacterOrTypeVariable() {
        val close = findCharacterLiteralEnd(tokenStart)
        if (close >= 0) {
            tokenEnd = close + 1
            tokenType = OCamlTokenTypes.CHARACTER
            return
        }

        tokenEnd = tokenStart + 1
        while (tokenEnd < bufferEnd && buffer[tokenEnd].isIdentifierPart()) tokenEnd++
        tokenType = OCamlTokenTypes.TYPE_VARIABLE
    }

    private fun findCharacterLiteralEnd(startOffset: Int): Int {
        var index = startOffset + 1
        if (index >= bufferEnd || buffer[index] == '\n' || buffer[index] == '\r') return -1
        if (buffer[index] != '\\') {
            index++
        } else {
            index++
            if (index >= bufferEnd) return -1
            index = when {
                buffer[index] in '0'..'9' -> consumeDigits(index, 3) { it in '0'..'9' }
                buffer[index] == 'x' -> consumeDigits(index + 1, 2) { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
                buffer[index] == 'o' -> consumeOctalCharacterEscape(index + 1)
                else -> index + 1
            }
            if (index < 0) return -1
        }
        return if (index < bufferEnd && buffer[index] == '\'') index else -1
    }

    private fun consumeDigits(startOffset: Int, count: Int, accepts: (Char) -> Boolean): Int {
        val endOffset = startOffset + count
        if (endOffset > bufferEnd) return -1
        return if ((startOffset until endOffset).all { accepts(buffer[it]) }) endOffset else -1
    }

    private fun consumeOctalCharacterEscape(startOffset: Int): Int {
        val endOffset = startOffset + 3
        if (endOffset > bufferEnd) return -1
        return if (
            buffer[startOffset] in '0'..'3' &&
            buffer[startOffset + 1] in '0'..'7' &&
            buffer[startOffset + 2] in '0'..'7'
        ) {
            endOffset
        } else {
            -1
        }
    }

    private fun scanNumber() {
        val firstDigit = tokenStart
        tokenEnd = when {
            startsWithAt("0x", firstDigit) || startsWithAt("0X", firstDigit) ->
                scanHexNumber(firstDigit)

            startsWithAt("0o", firstDigit) || startsWithAt("0O", firstDigit) ->
                scanRadixInteger(firstDigit, 2) { it in '0'..'7' }

            startsWithAt("0b", firstDigit) || startsWithAt("0B", firstDigit) ->
                scanRadixInteger(firstDigit, 2) { it == '0' || it == '1' }

            else -> scanDecimalNumber(firstDigit)
        }
        tokenType = OCamlTokenTypes.NUMBER
    }

    private fun scanDecimalNumber(firstDigit: Int): Int {
        var index = consumeNumberDigits(firstDigit) { it in '0'..'9' }
        var isFloat = false
        if (buffer.getOrNull(index) == '.') {
            isFloat = true
            index = consumeNumberDigits(index + 1, requireFirstDigit = false) { it in '0'..'9' }
        }
        val exponentEnd = scanExponent(index, "eE")
        if (exponentEnd != index) {
            isFloat = true
            index = exponentEnd
        }
        return if (!isFloat && buffer.getOrNull(index)?.let { it in INTEGER_SUFFIXES } == true) index + 1 else index
    }

    private fun scanHexNumber(firstDigit: Int): Int {
        val digitsStart = firstDigit + 2
        if (buffer.getOrNull(digitsStart)?.isHexDigit() != true) return firstDigit + 1

        var index = consumeNumberDigits(digitsStart, accepts = { it.isHexDigit() })
        var isFloat = false
        if (buffer.getOrNull(index) == '.') {
            isFloat = true
            index = consumeNumberDigits(index + 1, requireFirstDigit = false) { it.isHexDigit() }
        }
        val exponentEnd = scanExponent(index, "pP")
        if (exponentEnd != index) {
            isFloat = true
            index = exponentEnd
        }
        return if (!isFloat && buffer.getOrNull(index)?.let { it in INTEGER_SUFFIXES } == true) index + 1 else index
    }

    private fun scanRadixInteger(firstDigit: Int, prefixLength: Int, accepts: (Char) -> Boolean): Int {
        val digitsStart = firstDigit + prefixLength
        if (buffer.getOrNull(digitsStart)?.let(accepts) != true) return firstDigit + 1
        val end = consumeNumberDigits(digitsStart, accepts = accepts)
        return if (buffer.getOrNull(end)?.let { it in INTEGER_SUFFIXES } == true) end + 1 else end
    }

    private fun scanExponent(startOffset: Int, markers: String): Int {
        if (buffer.getOrNull(startOffset)?.let { it in markers } != true) return startOffset
        var digitsStart = startOffset + 1
        if (buffer.getOrNull(digitsStart) == '+' || buffer.getOrNull(digitsStart) == '-') digitsStart++
        if (buffer.getOrNull(digitsStart)?.let { it in '0'..'9' } != true) return startOffset
        return consumeNumberDigits(digitsStart) { it in '0'..'9' }
    }

    private fun consumeNumberDigits(
        startOffset: Int,
        requireFirstDigit: Boolean = true,
        accepts: (Char) -> Boolean,
    ): Int {
        if (requireFirstDigit && buffer.getOrNull(startOffset)?.let(accepts) != true) return startOffset
        var index = startOffset
        while (index < bufferEnd && (accepts(buffer[index]) || buffer[index] == '_')) index++
        return index
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
            text.firstOrNull()?.isOcamlUppercaseLetter() == true -> OCamlTokenTypes.CONSTRUCTOR
            else -> OCamlTokenTypes.IDENTIFIER
        }
    }

    private fun scanRawIdentifier() {
        tokenEnd = tokenStart + 3
        while (tokenEnd < bufferEnd && buffer[tokenEnd].isIdentifierPart()) tokenEnd++
        tokenType = OCamlTokenTypes.IDENTIFIER
    }

    private fun scanLineDirective(): Boolean {
        if (!isAtBeginningOfLine()) return false

        var index = tokenStart + 1
        while (buffer.getOrNull(index) == ' ' || buffer.getOrNull(index) == '\t') index++
        val lineNumberStart = index
        while (buffer.getOrNull(index)?.let { it in '0'..'9' } == true) index++
        if (index == lineNumberStart) return false

        while (buffer.getOrNull(index) == ' ' || buffer.getOrNull(index) == '\t') index++
        if (buffer.getOrNull(index) != '"') return false
        index++
        var escaped = false
        while (index < bufferEnd) {
            val current = buffer[index++]
            if (current == '\n' || current == '\r') return false
            if (current == '"' && !escaped) {
                tokenEnd = index
                tokenType = TokenType.WHITE_SPACE
                return true
            }
            escaped = current == '\\' && !escaped
            if (current != '\\') escaped = false
        }
        return false
    }

    private fun isAtBeginningOfLine(): Boolean =
        tokenStart == 0 || buffer[tokenStart - 1] == '\n' || buffer[tokenStart - 1] == '\r'

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

    private fun consumeLineBreak() {
        val first = buffer[tokenEnd++]
        if (first == '\r' && tokenEnd < bufferEnd && buffer[tokenEnd] == '\n') tokenEnd++
    }

    private fun startsWith(value: String, offset: Int = tokenStart): Boolean {
        if (offset < 0 || offset + value.length > bufferEnd) return false
        return value.indices.all { buffer[offset + it] == value[it] }
    }

    private fun startsWithAt(value: String, offset: Int): Boolean = startsWith(value, offset)

    private fun peek(delta: Int): Char? =
        buffer.getOrNull(tokenStart + delta)?.takeIf { tokenStart + delta < bufferEnd }

    private fun Char.isIdentifierStart(): Boolean = this == '_' || isOcamlLetter()
    private fun Char.isIdentifierPart(): Boolean =
        this == '_' || this == '\'' || this in '0'..'9' || isOcamlLetter()
    private fun Char.isOcamlLowercaseIdentifierStart(): Boolean = this == '_' || isOcamlLowercaseLetter()
    private fun Char.isQuotedStringMarkerChar(): Boolean = this == '_' || isOcamlLowercaseLetter()
    private fun Char.isOcamlLetter(): Boolean = isOcamlLowercaseLetter() || isOcamlUppercaseLetter()
    private fun Char.isOcamlLowercaseLetter(): Boolean =
        this in 'a'..'z' ||
            this in '\u00df'..'\u00f6' ||
            this in '\u00f8'..'\u00ff' ||
            this == '\u0153' ||
            this == '\u0161' ||
            this == '\u017e'
    private fun Char.isOcamlUppercaseLetter(): Boolean =
        this in 'A'..'Z' ||
            this in '\u00c0'..'\u00d6' ||
            this in '\u00d8'..'\u00de' ||
            this == '\u0152' ||
            this == '\u0160' ||
            this == '\u017d' ||
            this == '\u0178' ||
            this == '\u1e9e'

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || lowercaseChar() in 'a'..'f'

    private data class QuotedStringOpening(
        val marker: String,
        val contentStart: Int,
    )

    companion object {
        const val DEFAULT_STATE = 0
        const val STRING_STATE = 1
        const val ESCAPED_STRING_STATE = 2
        const val QUOTED_STRING_STATE = 0x10000000
        const val COMMENT_STATE = 0x20000004

        private const val STATE_TAG_MASK = 0x70000000
        private const val STATE_PAYLOAD_MASK = 0x0fffffff
        private const val QUOTED_STRING_TAG = QUOTED_STRING_STATE
        private const val COMMENT_TAG = 0x20000000
        private const val COMMENT_CODE_MODE = 0
        private const val COMMENT_STRING_MODE = 1
        private const val COMMENT_ESCAPED_STRING_MODE = 2
        private const val COMMENT_MODE_MASK = 0x3
        private const val COMMENT_DEPTH_SHIFT = 2
        private const val MAX_COMMENT_DEPTH = STATE_PAYLOAD_MASK ushr COMMENT_DEPTH_SHIFT

        private fun quotedStringState(marker: String): Int =
            QUOTED_STRING_TAG or (marker.hashCode() and STATE_PAYLOAD_MASK)

        private fun isQuotedStringState(state: Int): Boolean =
            state and STATE_TAG_MASK == QUOTED_STRING_TAG

        private fun commentState(depth: Int, mode: Int): Int =
            COMMENT_TAG or
                (depth.coerceIn(1, MAX_COMMENT_DEPTH) shl COMMENT_DEPTH_SHIFT) or
                (mode and COMMENT_MODE_MASK)

        private fun isCommentState(state: Int): Boolean =
            state and STATE_TAG_MASK == COMMENT_TAG

        private fun commentDepth(state: Int): Int =
            ((state and STATE_PAYLOAD_MASK) ushr COMMENT_DEPTH_SHIFT).coerceAtLeast(1)

        private fun commentMode(state: Int): Int = state and COMMENT_MODE_MASK

        private val KEYWORDS = setOf(
            "and", "as", "assert", "asr", "begin", "class", "constraint", "do", "done", "downto",
            "effect", "else", "end", "exception", "external", "false", "for", "fun", "function", "functor", "if",
            "in", "include", "inherit", "initializer", "land", "lazy", "let", "lor", "lsl", "lsr", "lxor",
            "match", "method", "mod", "module", "mutable", "new", "nonrec", "object", "of", "open", "or",
            "private", "rec", "sig", "struct", "then", "to", "true", "try", "type", "val", "virtual",
            "when", "while", "with",
        )
        private const val INTEGER_SUFFIXES = "lLn"
        private const val OPERATOR_CHARS = "!\$%&*+-./:<=>?@^|~#;,:`"
    }
}
