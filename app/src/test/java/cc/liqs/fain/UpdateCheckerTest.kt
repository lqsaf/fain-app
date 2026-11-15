package cc.liqs.fain

import cc.liqs.fain.ui.UpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun testIsNewerVersion_newerMajorMinorPatch() {
        assertTrue(UpdateChecker.isNewerVersion("0.4", "v0.5"))
        assertTrue(UpdateChecker.isNewerVersion("0.4", "0.5"))
        assertTrue(UpdateChecker.isNewerVersion("0.4.0", "v0.4.1"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.0", "v2.0.0"))
        assertTrue(UpdateChecker.isNewerVersion("0.4", "v1.0"))
    }

    @Test
    fun testIsNewerVersion_sameOrOlderVersion() {
        assertFalse(UpdateChecker.isNewerVersion("0.4", "v0.4"))
        assertFalse(UpdateChecker.isNewerVersion("0.4", "0.4.0"))
        assertFalse(UpdateChecker.isNewerVersion("0.5", "v0.4"))
        assertFalse(UpdateChecker.isNewerVersion("1.2.3", "v1.2.3"))
    }

    @Test
    fun testIsNewerVersion_withSuffixes() {
        assertTrue(UpdateChecker.isNewerVersion("0.4", "v0.5-beta"))
        assertFalse(UpdateChecker.isNewerVersion("0.5", "v0.5-alpha"))
    }
}
