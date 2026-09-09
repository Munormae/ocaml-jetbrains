package dev.munormae.lang.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import dev.munormae.icons.OCamlIcons
import javax.swing.Icon

class DuneColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "Dune"
    override fun getIcon(): Icon = OCamlIcons.Dune
    override fun getHighlighter(): SyntaxHighlighter = DuneSyntaxHighlighter()
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getDemoText(): String = """
        ; Build the application and link the project library.
        (executable
         (name main)
         (public_name camel-app)
         (libraries camel_core)
         (enabled_if (= %{profile} release)))

        (rule
         (alias @generate)
         (action (run generator.exe "generated.ml")))
    """.trimIndent()

    companion object {
        private val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Keyword", DuneSyntaxHighlighter.KEYWORD),
            AttributesDescriptor("Target", DuneSyntaxHighlighter.TARGET),
            AttributesDescriptor("Variable", DuneSyntaxHighlighter.VARIABLE),
            AttributesDescriptor("Number", DuneSyntaxHighlighter.NUMBER),
            AttributesDescriptor("String", DuneSyntaxHighlighter.STRING),
            AttributesDescriptor("Comment", DuneSyntaxHighlighter.COMMENT),
            AttributesDescriptor("Parentheses", DuneSyntaxHighlighter.PARENTHESES),
            AttributesDescriptor("Bad character", DuneSyntaxHighlighter.BAD_CHARACTER),
        )
    }
}
