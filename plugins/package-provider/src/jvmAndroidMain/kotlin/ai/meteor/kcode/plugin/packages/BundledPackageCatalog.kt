package ai.meteor.kcode.plugin.packages

import ai.meteor.kcode.plugin.PluginPackageImport
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cordis.packages.PackageHost
import org.cordis.packages.PackageVariant
import org.cordis.packages.packageFileSha256

data class NativeBundledPluginPackage(val id: String, val release: PluginPackageImport)

private data class BundledCatalogRecord(
    val id: String,
    val name: String,
    val digest: String,
    val variants: List<PackageVariant>,
)

/** Validates all trusted metadata, then skips incompatible archives before touching payloads. */
suspend fun stageBundledPackageCatalog(
    directory: File,
    host: PackageHost,
    openResource: (String) -> InputStream,
): List<NativeBundledPluginPackage> = runInterruptible(Dispatchers.IO) {
    host.validate()
    val catalogBytes = openResource("kcode/plugins/index.json").use { it.readNBytes(1_048_577) }
    require(catalogBytes.size <= 1_048_576) { "Bundled package catalog is too large" }
    val catalog = Json.parseToJsonElement(catalogBytes.decodeToString(throwOnInvalidSequence = true)).jsonArray
    val identities = mutableSetOf<String>()
    val records = catalog.map { item ->
        val record = item.jsonObject
        require(record.keys == setOf("id", "file", "sha256", "variants")) { "Invalid bundled package record" }
        fun field(name: String) = record.getValue(name).jsonPrimitive.also { require(it.isString) }.content
        val id = field("id")
        val name = field("file")
        val digest = field("sha256")
        require(id.isNotBlank() && digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid bundled package identity" }
        require(identities.add(id)) { "Duplicate bundled plugin identity" }
        require(name.matches(Regex("[a-zA-Z0-9._-]+\\.kplugin")) && !name.startsWith('.')) { "Invalid bundled resource name" }
        val variants = Json.decodeFromJsonElement<List<PackageVariant>>(record.getValue("variants"))
        require(variants.isNotEmpty() && variants.map { it.id }.distinct().size == variants.size) { "Invalid bundled variants" }
        variants.forEach { it.validate() }
        variants.forEachIndexed { index, left ->
            require(variants.drop(index + 1).none { left.overlaps(it) }) { "Overlapping bundled variants" }
        }
        BundledCatalogRecord(id, name, digest, variants)
    }
    // Reject invalid metadata in every target before opening a payload or touching the cache.
    val root = directory.toPath().toAbsolutePath().normalize().resolve("bundled-imports")
    require(!Files.isSymbolicLink(root)) { "Invalid bundled import directory" }
    Files.createDirectories(root)
    records.filter { record -> record.variants.any { it.matches(host) }
    }.map { record ->
        val (id, name, digest) = record
        val target = root.resolve("$digest.kplugin")
        require(!Files.isSymbolicLink(target)) { "Invalid bundled package destination" }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) || packageFileSha256(target.toFile()) != digest) {
            val temporary = Files.createTempFile(root, "bundled-", ".tmp")
            try {
                openResource("kcode/plugins/$name").use { input ->
                    Files.newOutputStream(temporary).use { output ->
                        val buffer = ByteArray(65536)
                        var size = 0L
                        while (true) {
                            check(!Thread.currentThread().isInterrupted) { "Bundled package staging interrupted" }
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            require(size <= 536_870_912L) { "Bundled package archive is too large" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                require(packageFileSha256(temporary.toFile()) == digest) { "Bundled resource digest mismatch" }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
        NativeBundledPluginPackage(id, PluginPackageImport(target.toString(), digest))
    }
}
