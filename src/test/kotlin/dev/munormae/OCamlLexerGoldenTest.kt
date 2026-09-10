package dev.munormae

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import dev.munormae.lang.highlighting.OCamlLexer
import dev.munormae.lang.highlighting.OCamlTokenTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OCamlLexerGoldenTest {
    @Test
    fun `lexer covers representative OCaml lexical forms`() {
        val source = """
            (* nested (* comment *) done *)
            {foo|multiline
            string|foo} '\n' 'a ~label: ?optional: [@attr] [%extension]
            0xff 1_000 1.0e-10 Module constructor +. >>= let
        """.trimIndent()

        assertEquals(
            listOf(
                "(* nested (* comment *) done *)" to OCamlTokenTypes.COMMENT,
                "{foo|multiline\n" to OCamlTokenTypes.STRING,
                "string|foo}" to OCamlTokenTypes.STRING,
                "'\\n'" to OCamlTokenTypes.CHARACTER,
                "'a" to OCamlTokenTypes.TYPE_VARIABLE,
                "~label:" to OCamlTokenTypes.LABEL,
                "?optional:" to OCamlTokenTypes.LABEL,
                "[@attr" to OCamlTokenTypes.ATTRIBUTE,
                "]" to OCamlTokenTypes.RBRACKET,
                "[%extension" to OCamlTokenTypes.ATTRIBUTE,
                "]" to OCamlTokenTypes.RBRACKET,
                "0xff" to OCamlTokenTypes.NUMBER,
                "1_000" to OCamlTokenTypes.NUMBER,
                "1.0e-10" to OCamlTokenTypes.NUMBER,
                "Module" to OCamlTokenTypes.CONSTRUCTOR,
                "constructor" to OCamlTokenTypes.IDENTIFIER,
                "+." to OCamlTokenTypes.OPERATOR,
                ">>=" to OCamlTokenTypes.OPERATOR,
                "let" to OCamlTokenTypes.KEYWORD,
            ),
            lex(source),
        )
    }

    @Test
    fun `lexer consumes unterminated comments strings and quoted strings safely`() {
        assertEquals(listOf("(* unfinished" to OCamlTokenTypes.COMMENT), lex("(* unfinished"))
        assertEquals(listOf("\"unfinished" to OCamlTokenTypes.STRING), lex("\"unfinished"))
        assertEquals(listOf("{id|unfinished" to OCamlTokenTypes.STRING), lex("{id|unfinished"))
    }

    @Test
    fun `lexer preserves multiline string state for incremental restart`() {
        val source = "\"first line\nsecond line\" tail"
        val lexer = OCamlLexer()
        lexer.start(source)

        assertEquals(OCamlLexer.DEFAULT_STATE, lexer.state)
        assertEquals("\"first line\n", source.substring(lexer.tokenStart, lexer.tokenEnd))
        lexer.advance()
        assertEquals(OCamlLexer.STRING_STATE, lexer.state)
        assertEquals("second line\"", source.substring(lexer.tokenStart, lexer.tokenEnd))

        val secondLineOffset = source.indexOf("second line")
        lexer.start(source, secondLineOffset, source.length, OCamlLexer.STRING_STATE)
        assertEquals(OCamlLexer.STRING_STATE, lexer.state)
        assertEquals(OCamlTokenTypes.STRING, lexer.tokenType)
        assertEquals("second line\"", source.substring(lexer.tokenStart, lexer.tokenEnd))
    }

    @Test
    fun `lexer preserves quoted string state and delimiter for incremental restart`() {
        val source = "{camel|first line\nsecond line|camel} tail"
        val lexer = OCamlLexer()
        lexer.start(source)

        assertEquals(OCamlLexer.DEFAULT_STATE, lexer.state)
        assertEquals("{camel|first line\n", source.substring(lexer.tokenStart, lexer.tokenEnd))
        lexer.advance()
        val continuationState = lexer.state
        assertTrue(continuationState != OCamlLexer.DEFAULT_STATE)
        assertEquals("second line|camel}", source.substring(lexer.tokenStart, lexer.tokenEnd))

        val secondLineOffset = source.indexOf("second line")
        lexer.start(source, secondLineOffset, source.length, continuationState)
        assertEquals(continuationState, lexer.state)
        assertEquals(OCamlTokenTypes.STRING, lexer.tokenType)
        assertEquals("second line|camel}", source.substring(lexer.tokenStart, lexer.tokenEnd))
    }

    @Test
    fun `lexer preserves nested comment depth for incremental restart`() {
        val source = "(* outer\n(* nested\nstill nested *)\nouter *) let"
        val lexer = OCamlLexer()
        lexer.start(source)
        lexer.advance()
        lexer.advance()
        val nestedLineState = lexer.state

        assertEquals(OCamlTokenTypes.COMMENT, lexer.tokenType)
        assertEquals("still nested *)\n", source.substring(lexer.tokenStart, lexer.tokenEnd))

        val nestedLineOffset = source.indexOf("still nested")
        lexer.start(source, nestedLineOffset, source.length, nestedLineState)
        assertEquals(nestedLineState, lexer.state)
        assertEquals(OCamlTokenTypes.COMMENT, lexer.tokenType)
        assertEquals("still nested *)\n", source.substring(lexer.tokenStart, lexer.tokenEnd))
        lexer.advance()
        assertEquals(OCamlTokenTypes.COMMENT, lexer.tokenType)
        lexer.advance()
        assertEquals(TokenType.WHITE_SPACE, lexer.tokenType)
        lexer.advance()
        assertEquals("let", source.substring(lexer.tokenStart, lexer.tokenEnd))
        assertEquals(OCamlTokenTypes.KEYWORD, lexer.tokenType)
    }

    @Test
    fun `comment delimiters inside strings do not change nesting depth`() {
        val source = """
            (* commented-out OCaml:
               let x = "*)"
               let y = 42
            *)
            let live = true
        """.trimIndent()

        val tokens = lex(source)

        assertEquals(OCamlTokenTypes.COMMENT, tokens.first().second)
        assertEquals("let", tokens[tokens.lastIndex - 3].first)
        assertEquals("live", tokens[tokens.lastIndex - 2].first)
    }

    @Test
    fun `lexer recognizes numeric character escapes`() {
        assertEquals(
            listOf(
                "'\\169'" to OCamlTokenTypes.CHARACTER,
                "'\\xA9'" to OCamlTokenTypes.CHARACTER,
                "'\\o251'" to OCamlTokenTypes.CHARACTER,
            ),
            lex("""'\169' '\xA9' '\o251'"""),
        )
    }

    @Test
    fun `raw identifiers are never highlighted as keywords`() {
        assertEquals(
            listOf(
                "let" to OCamlTokenTypes.KEYWORD,
                "#let" to OCamlTokenTypes.IDENTIFIER,
                "=" to OCamlTokenTypes.OPERATOR,
                "#foo" to OCamlTokenTypes.IDENTIFIER,
                "in" to OCamlTokenTypes.KEYWORD,
                "effect" to OCamlTokenTypes.KEYWORD,
            ),
            lex("let #let = #foo in effect"),
        )
    }

    @Test
    fun `quoted string markers only contain lowercase letters and underscores`() {
        assertEquals(
            listOf("{valid_marker|contents|valid_marker}" to OCamlTokenTypes.STRING),
            lex("{valid_marker|contents|valid_marker}"),
        )

        for (invalid in listOf("{Upper|contents|Upper}", "{id1|contents|id1}", "{id'|contents|id'}")) {
            assertTrue("$invalid must not be a quoted string", lex(invalid).none { it.second == OCamlTokenTypes.STRING })
        }
    }

    @Test
    fun `numeric literals stop at grammar boundaries`() {
        assertEquals(
            listOf(
                "1." to OCamlTokenTypes.NUMBER,
                "." to OCamlTokenTypes.OPERATOR,
                "2" to OCamlTokenTypes.NUMBER,
                "123" to OCamlTokenTypes.NUMBER,
                "foobar" to OCamlTokenTypes.IDENTIFIER,
                "0xFFL" to OCamlTokenTypes.NUMBER,
                "0o17n" to OCamlTokenTypes.NUMBER,
                "0b101l" to OCamlTokenTypes.NUMBER,
                "1e+2" to OCamlTokenTypes.NUMBER,
                "0x1.fp-3" to OCamlTokenTypes.NUMBER,
            ),
            lex("1..2 123foobar 0xFFL 0o17n 0b101l 1e+2 0x1.fp-3"),
        )
    }

    @Test
    fun `comment delimiters inside quoted strings do not close the comment`() {
        val source = """
            (*
              let x = {foo| *) |foo}
              let y = 123
            *)
            let live = true
        """.trimIndent()

        val tokens = lex(source)

        assertTrue(tokens.dropLast(4).all { it.second == OCamlTokenTypes.COMMENT })
        assertEquals(
            listOf(
                "let" to OCamlTokenTypes.KEYWORD,
                "live" to OCamlTokenTypes.IDENTIFIER,
                "=" to OCamlTokenTypes.OPERATOR,
                "true" to OCamlTokenTypes.KEYWORD,
            ),
            tokens.takeLast(4),
        )
    }

    @Test
    fun `quoted string marker recovery reads a large buffer linearly`() {
        val activeMarker = "klwfqlsre"
        val collidingMarker = "wvizyvybq"
        val stateSource = "{$activeMarker|first line\nsecond line|$activeMarker}"
        val stateLexer = OCamlLexer()
        stateLexer.start(stateSource)
        stateLexer.advance()
        val continuationState = stateLexer.state

        val source = buildString {
            append("{$activeMarker|")
            repeat(2_000) { append("{$collidingMarker|") }
            append("|$collidingMarker}\ncontinuation|$activeMarker}")
        }
        val restartOffset = source.indexOf("continuation")
        val countingBuffer = CountingCharSequence(source)
        val lexer = OCamlLexer()

        lexer.start(countingBuffer, restartOffset, source.length, continuationState)

        assertEquals(OCamlTokenTypes.STRING, lexer.tokenType)
        assertEquals(source.length, lexer.tokenEnd)
        assertTrue(
            "Expected linear recovery, read ${countingBuffer.readCount} characters for ${source.length} input characters",
            countingBuffer.readCount < source.length * 50L,
        )
    }

    private fun lex(source: String): List<Pair<String, IElementType>> {
        val lexer = OCamlLexer()
        lexer.start(source)
        return buildList {
            while (lexer.tokenType != null) {
                if (lexer.tokenType != TokenType.WHITE_SPACE) {
                    add(source.substring(lexer.tokenStart, lexer.tokenEnd) to requireNotNull(lexer.tokenType))
                }
                lexer.advance()
            }
        }
    }

    private class CountingCharSequence(private val delegate: String) : CharSequence {
        var readCount: Long = 0
            private set

        override val length: Int
            get() = delegate.length

        override fun get(index: Int): Char {
            readCount++
            return delegate[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            delegate.subSequence(startIndex, endIndex)
    }
}
