package ai.meteor.kcode.plugin.packages

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.cordis.packages.PackageDistribution
import org.cordis.packages.PackageDistributionTarget
import org.cordis.packages.PackageHost
import org.cordis.packages.PackageRuntime
import org.cordis.packages.PackageTarget
import org.cordis.packages.PackageVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BundledPackageCatalogTest {
    private val payload = "verified bundled payload".encodeToByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun variant(target: PackageTarget = PackageTarget("windows", listOf("arm", "x86")), runtime: String = "jvm") =
        PackageVariant("entry", listOf(target), PackageRuntime(runtime, "example.Plugin", "1"), "payload.bin")
    private fun record(id: String, variants: String = Json.encodeToString(listOf(variant()))) =
        """{"id":"$id","file":"$id.kplugin","sha256":"$digest","variants":$variants}"""
    private val windows = PackageHost("windows", "x86_64", runtimes = mapOf("jvm" to "21"))

    @Test
    fun selectsEachSystemAndMinimumBeforeOpeningPayload(): Unit = runBlocking {
        for ((system, version) in listOf("windows" to "10.0.26100", "macos" to "14.1", "linux" to "6.10", "android" to "16", "ios" to "17.1")) {
            val catalog = "[${record("example.target", Json.encodeToString(listOf(variant(PackageTarget(system, listOf("arm"), listOf(64), version), "native-library"))))}]"
            val host = PackageHost(system, "arm64", version, runtimes = mapOf("native-library" to "1"))
            for (eligible in listOf(true, false)) {
                val directory = Files.createTempDirectory("bundled-system").toFile()
                var opened = 0
                try {
                    val result = stageBundledPackageCatalog(directory, if (eligible) host else host.copy(systemVersion = "0")) {
                        opened++
                        ByteArrayInputStream(if (it.endsWith("index.json")) catalog.encodeToByteArray() else payload)
                    }
                    assertEquals(if (eligible) 1 else 0, result.size)
                    assertEquals(if (eligible) 2 else 1, opened)
                } finally { directory.deleteRecursively() }
            }
        }
    }

    @Test
    fun kernelDistributionFeaturesAndLoaderSkipIneligiblePayloads(): Unit = runBlocking {
        val target = PackageTarget("linux", listOf("x86"), listOf(64), "6.5", "6.8", PackageDistributionTarget("ubuntu", "22.04", "24.04"), setOf("linux.io-uring"))
        val host = PackageHost("linux", "x86_64", "6.8", PackageDistribution("ubuntu", "24.04"), setOf("linux.io-uring"), mapOf("native-library" to "2"))
        val catalog = "[${record("example.native", Json.encodeToString(listOf(variant(target, "native-library"))))}]"
        for (candidate in listOf(host, host.copy(systemVersion = "6.1"), host.copy(systemVersion = "6.9"), host.copy(distribution = PackageDistribution("ubuntu", "26.04")), host.copy(distribution = null), host.copy(distribution = PackageDistribution("ubuntu", "20.04")), host.copy(distribution = PackageDistribution("fedora", "40")), host.copy(features = emptySet()), host.copy(runtimes = mapOf("jvm" to "21")))) {
            val directory = Files.createTempDirectory("bundled-native").toFile()
            var payloadReads = 0
            try {
                val result = stageBundledPackageCatalog(directory, candidate) {
                    ByteArrayInputStream(if (it.endsWith("index.json")) catalog.encodeToByteArray() else payload.also { payloadReads++ })
                }
                assertEquals(if (candidate == host) 1 else 0, result.size)
                assertEquals(result.size, payloadReads)
            } finally { directory.deleteRecursively() }
        }
    }

    @Test
    fun skipsUnsupportedArchitectureBeforePayload(): Unit = runBlocking {
        val catalog = "[${record("ubuntu", Json.encodeToString(listOf(variant(PackageTarget("android", listOf("arm"), listOf(64)), "android-dex"))))}]"
        for (arch in listOf("arm64", "armv7", "x86_64")) {
            val directory = Files.createTempDirectory("bundled-bits").toFile()
            try {
                val result = stageBundledPackageCatalog(directory, PackageHost("android", arch, runtimes = mapOf("android-dex" to "35"))) {
                    check(arch == "arm64" || it.endsWith("index.json"))
                    ByteArrayInputStream(if (it.endsWith("index.json")) catalog.encodeToByteArray() else payload)
                }
                assertEquals(if (arch == "arm64") 1 else 0, result.size)
            } finally { directory.deleteRecursively() }
        }
    }

    @Test
    fun validatesUnsupportedRecordsBeforeAnyCacheMutation(): Unit = runBlocking {
        val valid = record("valid")
        val ios = Json.encodeToString(listOf(variant(PackageTarget("ios", listOf("arm"), listOf(64)), "native-library")))
        for (invalid in listOf(record("bad", ios.replace("\"bits\":[64]", "\"bits\":[]")), record("bad", ios.replace("\"arm\"", "\"arm64\"")), record("bad", "[]"), record("bad").replace("bad.kplugin", "../bad.kplugin"), record("valid"), record("bad").replace("\"variants\":", "\"platforms\":[] ,\"variants\":"))) {
            val directory = Files.createTempDirectory("bundled-preflight").toFile()
            try {
                assertFailsWith<IllegalArgumentException> {
                    stageBundledPackageCatalog(directory, windows) {
                        check(it.endsWith("index.json"))
                        ByteArrayInputStream("[$valid,$invalid]".encodeToByteArray())
                    }
                }
                assertFalse(directory.resolve("bundled-imports").exists())
            } finally { directory.deleteRecursively() }
        }
    }

    @Test
    fun runtimeAlternativesDoNotEraseTargetOrLoaderRequirements(): Unit = runBlocking {
        val native = variant(runtime = "native-library")
        val variants = listOf(variant(), native.copy(id = "native"))
        val directory = Files.createTempDirectory("bundled-runtime").toFile()
        try {
            val catalog = "[${record("example.runtime", Json.encodeToString(variants))}]"
            assertEquals(1, stageBundledPackageCatalog(directory, windows) {
                ByteArrayInputStream(if (it.endsWith("index.json")) catalog.encodeToByteArray() else payload)
            }.size)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun overlappingVariantsRejectEvenForUnsupportedLoader(): Unit = runBlocking {
        val directory = Files.createTempDirectory("bundled-overlap").toFile()
        try {
            val native = variant(runtime = "native-library")
            val catalog = "[${record("example.overlap", Json.encodeToString(listOf(native, native.copy(id = "other"))))}]"
            assertFailsWith<IllegalArgumentException> {
                stageBundledPackageCatalog(directory, windows) { ByteArrayInputStream(catalog.encodeToByteArray()) }
            }
            assertFalse(directory.resolve("bundled-imports").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun reusesVerifiedCacheAndRepairsCorruptionWithoutPublishingFailedCopies(): Unit = runBlocking {
        val directory = Files.createTempDirectory("bundled-cache").toFile()
        try {
            val catalog = "[${record("example")}]".encodeToByteArray()
            var reads = 0
            var served = payload
            suspend fun stage() = stageBundledPackageCatalog(directory, windows) {
                ByteArrayInputStream(if (it.endsWith("index.json")) catalog else served.also { reads++ })
            }
            stage()
            stage()
            assertEquals(1, reads)
            val cached = directory.resolve("bundled-imports/$digest.kplugin")
            cached.writeText("corrupted cache")
            served = "wrong resource".encodeToByteArray()
            assertFailsWith<IllegalArgumentException> { stage() }
            assertEquals("corrupted cache", cached.readText())
            assertEquals(listOf("$digest.kplugin"), cached.parentFile.list()!!.toList())
            served = payload
            stage()
            assertTrue(cached.readBytes().contentEquals(payload))
            assertEquals(3, reads)
        } finally { directory.deleteRecursively() }
    }
}
