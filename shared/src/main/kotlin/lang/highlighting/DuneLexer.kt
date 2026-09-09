package dev.munormae.lang.highlighting

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

class DuneLexer : LexerBase() {
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
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = bufferEnd

    override fun advance() {
        tokenStart = tokenEnd
        locateToken()
    }

    private fun locateToken() {
        if (tokenStart >= bufferEnd) {
            tokenType = null
            tokenEnd = bufferEnd
            return
        }

        when (val first = buffer[tokenStart]) {
            '(' -> singleCharacter(DuneTokenTypes.LPAREN)
            ')' -> singleCharacter(DuneTokenTypes.RPAREN)
            ';' -> scanComment()
            '"' -> scanString()
            else -> when {
                first.isWhitespace() -> scanWhitespace()
                first == '%' && peek(1) == '{' -> scanVariable()
                else -> scanAtom()
            }
        }
    }

    private fun singleCharacter(type: IElementType) {
        tokenEnd = tokenStart + 1
        tokenType = type
    }

    private fun scanWhitespace() {
        tokenEnd = tokenStart + 1
        while (tokenEnd < bufferEnd && buffer[tokenEnd].isWhitespace()) tokenEnd++
        tokenType = TokenType.WHITE_SPACE
    }

    private fun scanComment() {
        tokenEnd = tokenStart + 1
        while (tokenEnd < bufferEnd && buffer[tokenEnd] != '\n' && buffer[tokenEnd] != '\r') tokenEnd++
        tokenType = DuneTokenTypes.COMMENT
    }

    private fun scanString() {
        tokenEnd = tokenStart + 1
        var escaped = false
        while (tokenEnd < bufferEnd) {
            val current = buffer[tokenEnd++]
            if (current == '"' && !escaped) break
            escaped = current == '\\' && !escaped
            if (current != '\\') escaped = false
        }
        tokenType = DuneTokenTypes.STRING
    }

    private fun scanVariable() {
        tokenEnd = tokenStart + 2
        while (tokenEnd < bufferEnd && buffer[tokenEnd] != '}') tokenEnd++
        if (tokenEnd < bufferEnd) tokenEnd++
        tokenType = DuneTokenTypes.VARIABLE
    }

    private fun scanAtom() {
        tokenEnd = tokenStart + 1
        while (tokenEnd < bufferEnd && buffer[tokenEnd] !in ATOM_DELIMITERS) tokenEnd++
        val text = buffer.subSequence(tokenStart, tokenEnd).toString()
        tokenType = when {
            text in KEYWORDS || text.startsWith(':') -> DuneTokenTypes.KEYWORD
            text.startsWith('@') || text.endsWith(".exe") -> DuneTokenTypes.TARGET
            text.toDoubleOrNull() != null -> DuneTokenTypes.NUMBER
            else -> DuneTokenTypes.ATOM
        }
    }

    private fun peek(delta: Int): Char? =
        buffer.getOrNull(tokenStart + delta)?.takeIf { tokenStart + delta < bufferEnd }

    companion object {
        private val ATOM_DELIMITERS = setOf(' ', '\t', '\n', '\r', '(', ')', ';', '"')
        private val KEYWORDS = setOf(
            "alias", "aliases", "allow_approximate_merlin", "and_absent", "as", "authors",
            "binary_kind", "build", "cat", "chdir", "cinaps", "copy", "copy_files",
            "copy_files#", "cram", "data_only_dirs", "deps", "documentation", "enabled_if",
            "env", "executable", "executables", "fallback", "foreign_archives", "foreign_stubs",
            "generate_sites_module", "glob_files", "glob_files_rec", "include", "include_subdirs",
            "install", "instrumentation", "js_of_ocaml", "lang", "libraries", "library", "license",
            "link_deps", "link_flags", "link_mode", "link_modes", "lint", "map_workspace_root",
            "mdx", "menhir", "modes", "module", "modules", "modules_without_implementation",
            "name", "names", "ocamlc_flags", "ocamllex", "ocamlyacc", "ocamlopt_flags",
            "package", "packages", "per_module", "pps", "preprocess", "preprocessor_deps",
            "private_modules", "promote", "public_name", "public_names", "rule", "run", "select",
            "staged_pps", "subdir", "synopsis", "system", "test", "tests", "universe", "using",
            "vendored_dirs", "version", "virtual_deps", "virtual_modules", "wrapped",
        )
    }
}
