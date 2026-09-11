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
    fun `lexer recognizes only valid numeric character escapes`() {
        assertEquals(
            listOf(
                "'\\169'" to OCamlTokenTypes.CHARACTER,
                "'\\xA9'" to OCamlTokenTypes.CHARACTER,
                "'\\o251'" to OCamlTokenTypes.CHARACTER,
            ),
            lex("""'\169' '\xA9' '\o251'"""),
        )

        assertTrue(lex("""'\o777'""").none { it.second == OCamlTokenTypes.CHARACTER })
    }

    @Test
    fun `raw identifiers require a backslash while sharp stays separate`() {
        assertEquals(
            listOf(
                "let" to OCamlTokenTypes.KEYWORD,
                "\\#let" to OCamlTokenTypes.IDENTIFIER,
                "=" to OCamlTokenTypes.OPERATOR,
                "object_value" to OCamlTokenTypes.IDENTIFIER,
                "#" to OCamlTokenTypes.OPERATOR,
                "method_name" to OCamlTokenTypes.IDENTIFIER,
                "in" to OCamlTokenTypes.KEYWORD,
                "#" to OCamlTokenTypes.OPERATOR,
                "let" to OCamlTokenTypes.KEYWORD,
            ),
            lex("let \\#let = object_value#method_name in #let"),
        )
    }

    @Test
    fun `lexer recognizes PPX quoted string shorthand`() {
        val source = """
            {%sql|SELECT * FROM users|}
            {%%foo|structure item|}
            {%Vendor.syntax|qualified extension|}
            {%foo tag|first line
            second line|tag}
        """.trimIndent()

        assertEquals(
            listOf(
                "{%sql|SELECT * FROM users|}" to OCamlTokenTypes.STRING,
                "{%%foo|structure item|}" to OCamlTokenTypes.STRING,
                "{%Vendor.syntax|qualified extension|}" to OCamlTokenTypes.STRING,
                "{%foo tag|first line\n" to OCamlTokenTypes.STRING,
                "second line|tag}" to OCamlTokenTypes.STRING,
            ),
            lex(source),
        )
    }

    @Test
    fun `lexer preserves PPX quoted string marker for incremental restart`() {
        val source = "{%foo tag|first line\nsecond line|tag} tail"
        val lexer = OCamlLexer()
        lexer.start(source)
        lexer.advance()
        val continuationState = lexer.state

        val secondLineOffset = source.indexOf("second line")
        lexer.start(source, secondLineOffset, source.length, continuationState)

        assertEquals(continuationState, lexer.state)
        assertEquals(OCamlTokenTypes.STRING, lexer.tokenType)
        assertEquals("second line|tag}", source.substring(lexer.tokenStart, lexer.tokenEnd))
    }

    @Test
    fun `identifiers use the exact OCaml letter set`() {
        assertEquals(
            listOf(
                "lower_ßœšž" to OCamlTokenTypes.IDENTIFIER,
                "Upper_ÀŒŠŽŸẞ" to OCamlTokenTypes.CONSTRUCTOR,
            ),
            lex("lower_ßœšž Upper_ÀŒŠŽŸẞ"),
        )

        for (invalid in listOf('λ', 'Ж', '名', '١')) {
            assertEquals(
                "$invalid must not be accepted as an OCaml identifier character",
                TokenType.BAD_CHARACTER,
                lex(invalid.toString()).single().second,
            )
        }
        assertEquals(
            listOf(
                "ascii" to OCamlTokenTypes.IDENTIFIER,
                "١" to TokenType.BAD_CHARACTER,
            ),
            lex("ascii١"),
        )
    }

    @Test
    fun `line directives are whitespace only at the beginning of a line`() {
        assertEquals(
            listOf(
                "let" to OCamlTokenTypes.KEYWORD,
                "value" to OCamlTokenTypes.IDENTIFIER,
                "=" to OCamlTokenTypes.OPERATOR,
                "1" to OCamlTokenTypes.NUMBER,
                "value" to OCamlTokenTypes.IDENTIFIER,
                "#" to OCamlTokenTypes.OPERATOR,
                "7" to OCamlTokenTypes.NUMBER,
                "\"not-a-directive.ml\"" to OCamlTokenTypes.STRING,
                "#" to OCamlTokenTypes.OPERATOR,
                "8" to OCamlTokenTypes.NUMBER,
                "\"indented.ml\"" to OCamlTokenTypes.STRING,
            ),
            lex("# 123 \"generated.ml\"\nlet value = 1\nvalue # 7 \"not-a-directive.ml\"\n  # 8 \"indented.ml\""),
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

    @Test
    fun `incremental restart at every line matches full lexing`() {
        val source = """let normal = "first
second"
let quoted = {tag|alpha
beta|tag}
let extension = {%sql marker|select (*)
from users|marker}
(* outer
nested (* comment *) *)
let raw = \#let"""
        val fullTokens = lexWithState(source)

        val lineStarts = source.indices.filter { it == 0 || source[it - 1] == '\n' }
        for (lineStart in lineStarts) {
            val expectedIndex = fullTokens.indexOfFirst { it.start == lineStart }
            assertTrue("Full lexing must expose a token boundary at offset $lineStart", expectedIndex >= 0)
            val expected = fullTokens.drop(expectedIndex)
            val actual = lexWithState(source, lineStart, expected.first().state)

            assertEquals("Incremental lexing differs at offset $lineStart", expected, actual)
        }
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

    private fun lexWithState(
        source: String,
        startOffset: Int = 0,
        initialState: Int = OCamlLexer.DEFAULT_STATE,
    ): List<TokenSnapshot> {
        val lexer = OCamlLexer()
        lexer.start(source, startOffset, source.length, initialState)
        return buildList {
            while (lexer.tokenType != null) {
                add(
                    TokenSnapshot(
                        start = lexer.tokenStart,
                        end = lexer.tokenEnd,
                        text = source.substring(lexer.tokenStart, lexer.tokenEnd),
                        type = requireNotNull(lexer.tokenType),
                        state = lexer.state,
                    ),
                )
                lexer.advance()
            }
        }
    }

    private data class TokenSnapshot(
        val start: Int,
        val end: Int,
        val text: String,
        val type: IElementType,
        val state: Int,
    )

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
