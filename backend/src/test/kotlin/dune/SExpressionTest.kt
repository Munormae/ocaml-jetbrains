package dev.munormae.dune

import org.junit.Assert.assertEquals
import org.junit.Test

class SExpressionTest {
    @Test
    fun `parser handles strings and supported S-expression comment forms`() {
        val expressions = parseSExpressions(
            """
                ; line comment
                #| outer #| nested |# comment |#
                #; (ignored stanza)
                (executable (name "main") (public_name camel-app))
            """.trimIndent(),
        )

        val executable = expressions.single() as SList
        assertEquals("executable", executable.head)
        assertEquals(listOf("main"), executable.field("name")?.atomValuesAfterHead())
        assertEquals(listOf("camel-app"), executable.field("public_name")?.atomValuesAfterHead())
    }

    @Test
    fun `parser unescapes quoted Windows paths from Dune describe`() {
        val root = parseSExpressions("((root \"C:\\\\Projects\\\\camel\"))").single() as SList

        assertEquals("C:\\Projects\\camel", root.field("root")?.atomValuesAfterHead()?.single())
    }

    @Test
    fun `parser handles Dune quoted-string escapes and line continuation`() {
        val atom = parseSExpressions("\"line\\n\\098\\x41\\\n   continued\"").single() as SAtom

        assertEquals("line\nbAcontinued", atom.value)
    }
}
