package dev.munormae.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateOCamlModuleActionTest {
    @Test
    fun `accepts conventional and camel case module file names`() {
        assertTrue(isValidModuleFileName("user_profile"))
        assertTrue(isValidModuleFileName("userProfile.ml"))
        assertTrue(isValidModuleFileName("UserProfile.mli"))
    }

    @Test
    fun `rejects paths spaces and names starting with digits`() {
        assertFalse(isValidModuleFileName("42_users"))
        assertFalse(isValidModuleFileName("user profile"))
        assertFalse(isValidModuleFileName("src/user"))
    }

    @Test
    fun `removes an optional OCaml extension`() {
        assertEquals("user_profile", moduleFileBaseName(" user_profile.mli "))
        assertEquals("userProfile", moduleFileBaseName("userProfile.ml"))
    }
}
