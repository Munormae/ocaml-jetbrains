package dev.munormae

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import dev.munormae.lang.highlighting.DuneLexer
import dev.munormae.lang.highlighting.DuneTokenTypes
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class DuneSyntaxHighlightingTest {
    @Test
    fun `lexer recognizes Dune stanzas variables targets strings numbers and comments`() {
        val source = """
            ; Build the executable.
            (executable
             (name main)
             (deps %{project_root}/data @all)
             (enabled_if (= 42 "42")))
        """.trimIndent()
        val lexer = DuneLexer()
        lexer.start(source)
        val tokens = buildList {
            while (lexer.tokenType != null) {
                if (lexer.tokenType != TokenType.WHITE_SPACE) {
                    add(source.substring(lexer.tokenStart, lexer.tokenEnd) to lexer.tokenType)
                }
                lexer.advance()
            }
        }

        assertTrue("; Build the executable." to DuneTokenTypes.COMMENT in tokens)
        assertTrue("executable" to DuneTokenTypes.KEYWORD in tokens)
        assertTrue("%{project_root}" to DuneTokenTypes.VARIABLE in tokens)
        assertTrue("@all" to DuneTokenTypes.TARGET in tokens)
        assertTrue("42" to DuneTokenTypes.NUMBER in tokens)
        assertTrue("\"42\"" to DuneTokenTypes.STRING in tokens)
        assertTrue(tokens.count { it.second == DuneTokenTypes.LPAREN } == 5)
        assertTrue(tokens.count { it.second == DuneTokenTypes.RPAREN } == 5)
    }

    @Test
    fun `lexer highlights interpolation inside an atom`() {
        val source = "foo-%{profile}-bar"
        val lexer = DuneLexer()
        lexer.start(source)
        val tokens = buildList {
            while (lexer.tokenType != null) {
                add(source.substring(lexer.tokenStart, lexer.tokenEnd) to lexer.tokenType)
                lexer.advance()
            }
        }

        assertEquals(
            listOf(
                "foo-" to DuneTokenTypes.ATOM,
                "%{profile}" to DuneTokenTypes.VARIABLE,
                "-bar" to DuneTokenTypes.ATOM,
            ),
            tokens,
        )
    }

    @Test
    fun `lexer preserves multiline string state for incremental restart`() {
        val source = "\"first line\\\nsecond line\nthird line\" tail"
        val lexer = DuneLexer()
        lexer.start(source)

        assertEquals(DuneLexer.DEFAULT_STATE, lexer.state)
        assertEquals(DuneTokenTypes.STRING, lexer.tokenType)
        lexer.advance()
        assertEquals(DuneLexer.ESCAPED_STRING_STATE, lexer.state)
        assertEquals(DuneTokenTypes.STRING, lexer.tokenType)
        lexer.advance()
        assertEquals(DuneLexer.STRING_STATE, lexer.state)
        assertEquals(DuneTokenTypes.STRING, lexer.tokenType)

        val thirdLineOffset = source.indexOf("third line")
        lexer.start(source, thirdLineOffset, source.length, DuneLexer.STRING_STATE)
        assertEquals(DuneLexer.STRING_STATE, lexer.state)
        assertEquals(DuneTokenTypes.STRING, lexer.tokenType)
        assertEquals("third line\"", source.substring(lexer.tokenStart, lexer.tokenEnd))
    }

    @Test
    fun `lexer keeps Dune end-of-line strings separate from following structure`() {
        val source = """
            (rule
             (action
              (echo
               "\| text (with parens)
               "\> raw \n text (with parens)
              )))

            (executable
             (name main))
        """.trimIndent()
        val lexer = DuneLexer()
        lexer.start(source)
        val tokens = buildList {
            while (lexer.tokenType != null) {
                if (lexer.tokenType != TokenType.WHITE_SPACE) {
                    add(source.substring(lexer.tokenStart, lexer.tokenEnd) to requireNotNull(lexer.tokenType))
                }
                lexer.advance()
            }
        }

        assertTrue("\"\\| text (with parens)\n" to DuneTokenTypes.STRING in tokens)
        assertTrue("\"\\> raw \\n text (with parens)\n" to DuneTokenTypes.STRING in tokens)
        assertTrue("executable" to DuneTokenTypes.KEYWORD in tokens)
        assertTrue("main" to DuneTokenTypes.ATOM in tokens)
        assertEquals(5, tokens.count { it.second == DuneTokenTypes.LPAREN })
        assertEquals(5, tokens.count { it.second == DuneTokenTypes.RPAREN })
    }

    @Test
    fun `incremental restart at every line matches full Dune lexing`() {
        val source = """(rule
(action
(echo "first line\
continued line
last line")))
"\| escaped \n line (with parens)
"\> raw \n line (with parens)
; comment
(executable (name main))"""
        val fullTokens = lexWithState(source)

        val lineStarts = source.indices.filter { it == 0 || source[it - 1] == '\n' }
        for (lineStart in lineStarts) {
            val containingIndex = fullTokens.indexOfFirst { lineStart in it.start until it.end }
            assertTrue("Full lexing must cover offset $lineStart", containingIndex >= 0)
            val containing = fullTokens[containingIndex]
            val expected = buildList {
                add(
                    containing.copy(
                        start = lineStart,
                        text = source.substring(lineStart, containing.end),
                    ),
                )
                addAll(fullTokens.drop(containingIndex + 1))
            }

            assertEquals(
                "Incremental Dune lexing differs at offset $lineStart",
                expected,
                lexWithState(source, lineStart, containing.state),
            )
        }
    }

    private fun lexWithState(
        source: String,
        startOffset: Int = 0,
        initialState: Int = DuneLexer.DEFAULT_STATE,
    ): List<TokenSnapshot> {
        val lexer = DuneLexer()
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
}
