package ai.meteor.kcode.plugin.packages

import org.cordis.packages.PackageDistribution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LinuxDistributionTest {
    @Test
    fun parsesQuotedVersionsWithoutEvaluatingShellContent() {
        assertEquals(PackageDistribution("ubuntu", "22.04"), parseLinuxOsRelease("ID=ubuntu\nVERSION_ID=\"22.04\"\nNAME=ignored"))
        assertEquals(PackageDistribution("debian", "12"), parseLinuxOsRelease("ID='debian'\nVERSION_ID='12'"))
        assertEquals(PackageDistribution("arch"), parseLinuxOsRelease("ID=arch\nVERSION_ID=rolling"))
    }

    @Test
    fun unknownAndAmbiguousDistributionMetadataIsNotAdvertised() {
        assertNull(parseLinuxOsRelease("VERSION_ID=24.04"))
        assertFailsWith<IllegalArgumentException> { parseLinuxOsRelease("ID=ubuntu\nID=debian") }
        assertFailsWith<IllegalArgumentException> { parseLinuxOsRelease("ID=$(touch /tmp/unsafe)") }
    }
}
