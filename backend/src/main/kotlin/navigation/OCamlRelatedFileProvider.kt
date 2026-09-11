package dev.munormae.navigation

import com.intellij.navigation.GotoRelatedItem
import com.intellij.navigation.GotoRelatedProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import dev.munormae.OCamlBundle

class OCamlRelatedFileProvider : GotoRelatedProvider() {
    override fun getItems(psiElement: PsiElement): List<GotoRelatedItem> {
        val file = psiElement.containingFile?.virtualFile ?: return emptyList()
        val counterpartName = ocamlCounterpartFileName(file.name) ?: return emptyList()
        val counterpart = file.parent?.findChild(counterpartName) ?: return emptyList()
        val counterpartPsi = PsiManager.getInstance(psiElement.project).findFile(counterpart) ?: return emptyList()
        return listOf(GotoRelatedItem(counterpartPsi, OCamlBundle.message("navigation.related.group")))
    }
}

internal fun ocamlCounterpartFileName(fileName: String): String? = when {
    fileName.endsWith(".mli", ignoreCase = true) -> fileName.dropLast(4) + ".ml"
    fileName.endsWith(".ml", ignoreCase = true) -> fileName.dropLast(3) + ".mli"
    else -> null
}
