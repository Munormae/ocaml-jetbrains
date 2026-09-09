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
            '"' -> parseQuotedAtom()
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
                current == '\\' && offset < text.length -> {
                    val escaped = text[offset++]
                    value.append(
                        when (escaped) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            else -> escaped
                        },
                    )
                }
                else -> value.append(current)
            }
        }
        return SAtom(value.toString())
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
}
