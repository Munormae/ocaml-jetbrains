package dev.munormae.lang

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import dev.munormae.lang.highlighting.OCamlTokenTypes

class OCamlBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = arrayOf(
        BracePair(OCamlTokenTypes.LPAREN, OCamlTokenTypes.RPAREN, false),
        BracePair(OCamlTokenTypes.LBRACKET, OCamlTokenTypes.RBRACKET, false),
        BracePair(OCamlTokenTypes.LBRACE, OCamlTokenTypes.RBRACE, false),
    )

    override fun isPairedBracesAllowedBeforeType(leftBraceType: IElementType, contextType: IElementType?): Boolean = true

    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int = openingBraceOffset
}
