package dev.munormae.lang

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import dev.munormae.lang.highlighting.DuneTokenTypes

class DuneBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = arrayOf(
        BracePair(DuneTokenTypes.LPAREN, DuneTokenTypes.RPAREN, true),
    )

    override fun isPairedBracesAllowedBeforeType(leftBraceType: IElementType, contextType: IElementType?): Boolean = true
    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int = openingBraceOffset
}
