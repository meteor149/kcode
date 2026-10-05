package ai.meteor.kcode.plugin.packages

import java.io.File
import org.cordis.packages.PackageDistribution
import org.cordis.packages.validateSystemVersion

internal fun readLinuxDistribution(): PackageDistribution? = runCatching {
    val file = listOf(File("/etc/os-release"), File("/usr/lib/os-release")).firstOrNull { it.isFile } ?: return@runCatching null
    val bytes = file.inputStream().use { it.readNBytes(65_537) }
    require(bytes.size <= 65_536) { "OS release metadata is too large" }
    parseLinuxOsRelease(bytes.decodeToString(throwOnInvalidSequence = true))
}.getOrNull()

/** Parse only identity/version data; os-release is never executed as a shell script. */
internal fun parseLinuxOsRelease(source: String): PackageDistribution? {
    val fields = mutableMapOf<String, String>()
    source.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.forEach { line ->
        val key = line.substringBefore('=')
        if (key in setOf("ID", "VERSION_ID")) {
            require('=' in line && key !in fields) { "Invalid OS release metadata" }
            val value = line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
            fields[key] = value
        }
    }
    val id = fields["ID"] ?: return null
    val version = fields["VERSION_ID"]?.takeIf { runCatching { validateSystemVersion(it) }.isSuccess }
    return PackageDistribution(id, version).also { it.validate() }
}
