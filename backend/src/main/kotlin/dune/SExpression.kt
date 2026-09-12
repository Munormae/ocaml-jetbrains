package dev.munormae.dune

internal sealed interface SExpression

internal data class SAtom(val value: String) : SExpression

internal data class SList(val values: List<SExpression>) : SExpression {
    val head: String?
        get() = (values.firstOrNull() as? SAtom)?.value

    fun field(name: String): SList? = values
        .asSequence()
        .filterIsInstance<SList>()
        .firstOrNull { it.head == name }

    fun atomValuesAfterHead(): List<String> = values.drop(1).flatMap { expression ->
        when (expression) {
            is SAtom -> listOf(expression.value)
            is SList -> expression.values.filterIsInstance<SAtom>().map(SAtom::value)
        }
    }
}

internal fun parseSExpressions(text: String): List<SExpression> = SExpressionParser(text).parseAll()

private class SExpressionParser(private val text: String) {
    private var offset = 0

    fun parseAll(): List<SExpression> = buildList {
        while (true) {
            skipTrivia()
            if (offset >= text.length) break
            parseExpression()?.let(::add) ?: offset++
        }
    }

    private fun parseExpression(): SExpression? {
        skipTrivia()
        if (offset >= text.length) return null
        return when (text[offset]) {
            '(' -> parseList()
            ')' -> null
            '"' -> if (isEndOfLineStringOpening(offset)) parseEndOfLineString() else parseQuotedAtom()
            else -> parseAtom()
        }
    }

    private fun parseList(): SList {
        offset++
        val values = buildList {
            while (offset < text.length) {
                skipTrivia()
                if (offset >= text.length) break
                if (text[offset] == ')') {
                    offset++
                    break
                }
                parseExpression()?.let(::add) ?: offset++
            }
        }
        return SList(values)
    }

    private fun parseQuotedAtom(): SAtom {
        offset++
        val value = StringBuilder()
        while (offset < text.length) {
            val current = text[offset++]
            when {
                current == '"' -> break
                current == '\\' && offset < text.length -> appendEscapedCharacter(value)
                else -> value.append(current)
            }
        }
        return SAtom(value.toString())
    }

    private fun parseEndOfLineString(): SAtom {
        val value = StringBuilder()
        var continuation = false
        while (isEndOfLineStringOpening(offset)) {
            if (continuation) value.append('\n')
            val interpretEscapes = text[offset + 2] == '|'
            offset += END_OF_LINE_STRING_PREFIX_LENGTH
            if (offset < text.length && text[offset] == ' ') offset++

            while (offset < text.length && text[offset] != '\n' && text[offset] != '\r') {
                val current = text[offset++]
                if (interpretEscapes && current == '\\' && offset < text.length) {
                    appendEscapedCharacter(value)
                } else {
                    value.append(current)
                }
            }

            consumeLineBreak()
            while (offset < text.length && (text[offset] == ' ' || text[offset] == '\t')) offset++
            continuation = true
        }
        return SAtom(value.toString())
    }

    private fun appendEscapedCharacter(value: StringBuilder) {
        val escaped = text[offset++]
        when (escaped) {
            'n' -> value.append('\n')
            'r' -> value.append('\r')
            'b' -> value.append('\b')
            't' -> value.append('\t')
            '\n' -> skipContinuationIndent()
            '\r' -> {
                if (offset < text.length && text[offset] == '\n') offset++
                skipContinuationIndent()
            }
            'x' -> value.append(readHexEscape() ?: 'x')
            in '0'..'9' -> value.append(readDecimalEscape(escaped))
            else -> value.append(escaped)
        }
    }

    private fun consumeLineBreak() {
        if (offset >= text.length) return
        val first = text[offset]
        if (first != '\n' && first != '\r') return
        offset++
        if (first == '\r' && offset < text.length && text[offset] == '\n') offset++
    }

    private fun isEndOfLineStringOpening(startOffset: Int): Boolean =
        text.getOrNull(startOffset) == '"' &&
            text.getOrNull(startOffset + 1) == '\\' &&
            (text.getOrNull(startOffset + 2) == '|' || text.getOrNull(startOffset + 2) == '>')

    private fun readDecimalEscape(first: Char): Char {
        var digits = first.toString()
        repeat(2) {
            if (offset < text.length && text[offset].isDigit()) digits += text[offset++]
        }
        return digits.toIntOrNull()?.toChar() ?: first
    }

    private fun readHexEscape(): Char? {
        if (offset + 2 > text.length) return null
        val digits = text.substring(offset, offset + 2)
        val code = digits.toIntOrNull(16) ?: return null
        offset += 2
        return code.toChar()
    }

    private fun skipContinuationIndent() {
        while (offset < text.length && text[offset] in " \t") offset++
    }

    private fun parseAtom(): SAtom {
        val start = offset
        while (offset < text.length && !isAtomDelimiter(text[offset])) offset++
        return SAtom(text.substring(start, offset))
    }

    private fun skipTrivia() {
        while (offset < text.length) {
            when {
                text[offset].isWhitespace() -> offset++
                text[offset] == ';' -> skipLineComment()
                text.startsWith("#|", offset) -> skipBlockComment()
                text.startsWith("#;", offset) -> {
                    offset += 2
                    skipTrivia()
                    parseExpression()
                }
                else -> return
            }
        }
    }

    private fun skipLineComment() {
        while (offset < text.length && text[offset] != '\n' && text[offset] != '\r') offset++
    }

    private fun skipBlockComment() {
        offset += 2
        var depth = 1
        while (offset < text.length && depth > 0) {
            when {
                text.startsWith("#|", offset) -> {
                    depth++
                    offset += 2
                }
                text.startsWith("|#", offset) -> {
                    depth--
                    offset += 2
                }
                else -> offset++
            }
        }
    }

    private fun isAtomDelimiter(character: Char): Boolean =
        character.isWhitespace() || character == '(' || character == ')' || character == ';' || character == '"'

    companion object {
        private const val END_OF_LINE_STRING_PREFIX_LENGTH = 3
    }
}
