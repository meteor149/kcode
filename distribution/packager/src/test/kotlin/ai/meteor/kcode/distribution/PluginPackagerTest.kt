package ai.meteor.kcode.distribution

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.cordis.packages.PluginPackageManifest

class PluginPackagerTest {
    @Test
    fun nativeApkRequiresExplicitArchitecturesWithoutReplacingExistingOutput() = fixture(native = true) { apk, abi, output ->
        output.writeText("previous release")
        assertFailsWith<IllegalArgumentException> { main(arguments(apk, abi, output)) }
        assertEquals("previous release", output.readText())
        assertTrue(output.parentFile.listFiles().orEmpty().none { it.name.startsWith(".payload-") })
    }

    @Test
    fun nativeApkPublishesMatchingArchitectureAndRejectsMissingAbi() = fixture(native = true) { apk, abi, output ->
        val targets = File(output.parentFile, "native-targets.json").apply {
            writeText("""[{"system":"android","arch":["arm"],"bits":[64]}]""")
        }
        main(arguments(apk, abi, output) + arrayOf("-", "-", targets.absolutePath))
        ZipFile(output).use { zip ->
            val manifest = Json.decodeFromString<PluginPackageManifest>(zip.getInputStream(zip.getEntry("plugin.json")).bufferedReader().use { it.readText() })
            assertEquals(1, manifest.formatVersion)
            assertEquals(listOf("arm"), manifest.variants.single().targets.single().arch)
            assertEquals(listOf(64), manifest.variants.single().targets.single().bits)
            assertEquals("android", manifest.variants.single().targets.single().system)
        }
        val previous = output.readBytes()
        targets.writeText("""[{"system":"android","arch":["x86"],"bits":[64]}]""")
        assertFailsWith<IllegalArgumentException> { main(arguments(apk, abi, output) + arrayOf("-", "-", targets.absolutePath)) }
        assertTrue(previous.contentEquals(output.readBytes()))
    }

    @Test
    fun bytecodeOnlyApkRemainsPortableAndMixedSelectorsAreRejected() = fixture(native = false) { apk, abi, output ->
        main(arguments(apk, abi, output))
        ZipFile(output).use { zip ->
            val manifest = Json.decodeFromString<PluginPackageManifest>(zip.getInputStream(zip.getEntry("plugin.json")).bufferedReader().use { it.readText() })
            assertEquals(listOf("arm", "x86"), manifest.variants.single().targets.single().arch)
            assertEquals(listOf(32, 64), manifest.variants.single().targets.single().bits)
        }
        val invalid = File(output.parentFile, "invalid-targets.json").apply { writeText("""[{"system":"android","arch":["any","arm"]}]""") }
        assertFailsWith<IllegalArgumentException> { main(arguments(apk, abi, output) + arrayOf("-", "-", invalid.absolutePath)) }
    }

    @Test
    fun explicitSystemVersionAndFamiliesArePackedAndCatalogCopiesTheVerifiedTargets() = fixture(native = true) { apk, abi, output ->
        val targets = File(output.parentFile, "targets.json").apply {
            writeText("""[{"system":"android","arch":["arm"],"bits":[64],"minSystemVersion":"16.1","maxSystemVersion":"17.2"}]""")
        }
        main(arguments(apk, abi, output) + arrayOf("-", "-", targets.absolutePath))
        ZipFile(output).use { zip ->
            val manifest = Json.decodeFromString<PluginPackageManifest>(zip.getInputStream(zip.getEntry("plugin.json")).bufferedReader().use { it.readText() })
            assertEquals("16.1", manifest.variants.single().targets.single().minSystemVersion)
            assertEquals("17.2", manifest.variants.single().targets.single().maxSystemVersion)
        }
        main(arrayOf("--catalog", output.parentFile.absolutePath))
        assertTrue(File(output.parentFile, "index.json").readText().contains("16.1"))
        assertTrue(File(output.parentFile, "index.json").readText().contains("17.2"))
        val previous = output.readBytes()
        targets.writeText("""[{"system":"android","arch":["arm"],"bits":[32,64]}]""")
        assertFailsWith<IllegalArgumentException> { main(arguments(apk, abi, output) + arrayOf("-", "-", targets.absolutePath)) }
        assertTrue(previous.contentEquals(output.readBytes()))
    }

    private fun arguments(apk: File, abi: File, output: File) = arrayOf(
        "fixture.ubuntu", "1.0.0", "ai.example.UbuntuPlugin", "-", "-",
        apk.absolutePath, "ai.example.ubuntu", abi.absolutePath, output.absolutePath, "-",
    )

    private fun fixture(native: Boolean, test: (File, File, File) -> Unit) {
        val directory = Files.createTempDirectory("package-architecture").toFile()
        try {
            val apk = File(directory, "plugin.apk")
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(if (native) "lib/arm64-v8a/libfixture.so" else "classes.dex"))
                zip.write("fixture".encodeToByteArray())
                zip.closeEntry()
            }
            test(apk, File(directory, "sdk.txt").apply { writeText("fixture SDK") }, File(directory, "plugin.kplugin"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
