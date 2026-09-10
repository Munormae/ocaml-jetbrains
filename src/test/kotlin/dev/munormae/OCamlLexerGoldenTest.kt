package dev.munormae

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import dev.munormae.lang.highlighting.OCamlLexer
import dev.munormae.lang.highlighting.OCamlTokenTypes
import org.junit.Assert.assertEquals
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
                "{foo|multiline\nstring|foo}" to OCamlTokenTypes.STRING,
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
}
