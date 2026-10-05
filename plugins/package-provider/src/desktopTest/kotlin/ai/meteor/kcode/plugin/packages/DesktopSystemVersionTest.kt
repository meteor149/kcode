package ai.meteor.kcode.plugin.packages

import org.cordis.packages.PackageHost
import org.cordis.packages.PackageTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSystemVersionTest {
    @Test
    fun windowsBoundsUseBuildAndRevisionWithoutPretendingUnknownPartsAreZero() {
        val version = completeWindowsSystemVersion("10.0", "26100", "6191")
        assertEquals("10.0.26100.6191", version)
        assertEquals(version, completeWindowsSystemVersion("10.0.26100", null, "6191"))
        val host = PackageHost("windows", "x86_64", version)
        val target = PackageTarget("windows", listOf("x86"), maxSystemVersion = "10.0.26100.1000")
        assertFalse(target.matches(host))
        assertTrue(target.copy(maxSystemVersion = version).matches(host))
        assertNull(completeWindowsSystemVersion("10.0", null, "6191"))
        assertNull(completeWindowsSystemVersion("10.0", "26100", null))
        assertNull(completeWindowsSystemVersion("10.0", "unknown", "6191"))
        assertFalse(target.matches(host.copy(systemVersion = null)))
    }
}
