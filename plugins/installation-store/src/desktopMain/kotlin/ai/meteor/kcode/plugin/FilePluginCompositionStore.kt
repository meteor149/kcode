package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import ai.meteor.kcode.plugin.api.PluginCompositionStore
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Manifest is app-private; artifact trust is checked again by the native loader at every boot. */
class FilePluginCompositionStore(directory: File) : PluginCompositionStore {
    private val root = Files.createDirectories(directory.toPath()).toRealPath()
    private val manifest = root.resolve("plugin-installations.json")
    private val mutex = Mutex()
    private val json = Json { prettyPrint = true }

    override suspend fun load(): PluginCompositionSnapshot = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) return@withContext PluginCompositionSnapshot()
            require(!Files.isSymbolicLink(manifest) && Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) { "Invalid plugin manifest file" }
            val bytes = Files.newInputStream(manifest).use { it.readNBytes(MaxManifestBytes + 1) }
            require(bytes.size <= MaxManifestBytes) { "Plugin manifest is too large" }
            json.decodeFromString<PluginCompositionSnapshot>(bytes.decodeToString(throwOnInvalidSequence = true)).also { it.validate() }
        }
    }

    override suspend fun save(snapshot: PluginCompositionSnapshot) = mutex.withLock {
        snapshot.validate()
        val bytes = json.encodeToString(snapshot).encodeToByteArray()
        require(bytes.size <= MaxManifestBytes) { "Plugin manifest is too large" }
        withContext(Dispatchers.IO) {
            val temporary = Files.createTempFile(root, "plugin-installations-", ".tmp")
            try {
                FileOutputStream(temporary.toFile()).use { output -> output.write(bytes); output.fd.sync() }
                Files.move(temporary, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                Unit
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    private companion object { const val MaxManifestBytes = 1_048_576 }
}
