package dev.munormae.lang.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import dev.munormae.icons.OCamlIcons
import javax.swing.Icon

class OCamlColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "OCaml"
    override fun getIcon(): Icon = OCamlIcons.File
    override fun getHighlighter(): SyntaxHighlighter = OCamlSyntaxHighlighter()
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getDemoText(): String = """
        (* Nested comments are supported: (* like this *) *)
        type 'a tree =
          | Leaf of 'a
          | Node of 'a tree * 'a tree

        let rec map ~f = function
          | Leaf value -> Leaf (f value)
          | Node (left, right) -> Node (map ~f left, map ~f right)

        let greeting = {ocaml|Hello from OCaml!|ocaml}
        let answer = 42
    """.trimIndent()

    companion object {
        private val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Keyword", OCamlSyntaxHighlighter.KEYWORD),
            AttributesDescriptor("Constructor or module", OCamlSyntaxHighlighter.CONSTRUCTOR),
            AttributesDescriptor("Type variable", OCamlSyntaxHighlighter.TYPE_VARIABLE),
            AttributesDescriptor("Label", OCamlSyntaxHighlighter.LABEL),
            AttributesDescriptor("Number", OCamlSyntaxHighlighter.NUMBER),
            AttributesDescriptor("String", OCamlSyntaxHighlighter.STRING),
            AttributesDescriptor("Character", OCamlSyntaxHighlighter.CHARACTER),
            AttributesDescriptor("Comment", OCamlSyntaxHighlighter.COMMENT),
            AttributesDescriptor("Attribute or extension", OCamlSyntaxHighlighter.ATTRIBUTE),
            AttributesDescriptor("Operator", OCamlSyntaxHighlighter.OPERATOR),
            AttributesDescriptor("Bad character", OCamlSyntaxHighlighter.BAD_CHARACTER),
        )
    }
}
