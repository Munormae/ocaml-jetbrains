package dev.munormae

import com.intellij.psi.TokenType
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
}
