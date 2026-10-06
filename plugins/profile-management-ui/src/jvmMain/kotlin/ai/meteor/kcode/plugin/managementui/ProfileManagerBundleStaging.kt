package ai.meteor.kcode.plugin.managementui

import ai.meteor.kcode.plugin.api.profiles.ProfileBundleArchiveReference
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal const val ProfileManagerBundleLimit = 512L * 1024 * 1024

internal suspend fun stageManagerBundles(
    sources: List<Pair<String, () -> InputStream>>,
    consume: suspend (List<ProfileManagerBundleFile>) -> Unit,
) = withContext(Dispatchers.IO) {
    require(sources.isNotEmpty() && sources.size <= 16) { "Select between one and sixteen Bundle archives" }
    val operation = currentCoroutineContext()
    val directory = Files.createTempDirectory("kcode-manager-bundles-")
    val staged = mutableListOf<Path>()
    try {
        var total = 0L
        val references = sources.mapIndexed { index, (name, open) ->
            operation.ensureActive()
            val path = directory.resolve("$index.kbundle")
            staged.add(path)
            val digest = MessageDigest.getInstance("SHA-256")
            runInterruptible {
                open().use { input ->
                    Files.newOutputStream(path).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            operation.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            require(total <= ProfileManagerBundleLimit) { "Bundle selection is too large" }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                        }
                    }
                }
            }
            ProfileManagerBundleFile(
                ProfileBundleArchiveReference(
                    path.toString(),
                    digest.digest().joinToString("") { "%02x".format(it) },
                ),
                name.take(256).ifBlank { "Bundle ${index + 1}" },
            )
        }
        operation.ensureActive()
        consume(references)
    } finally {
        var cleanupFailure: Exception? = null
        (staged.asReversed() + directory).forEach { path ->
            try {
                Files.deleteIfExists(path)
            } catch (failure: Exception) {
                if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure!!.addSuppressed(failure)
            }
        }
        cleanupFailure?.let { throw it }
    }
}

internal fun selectedPath(path: Path): Pair<String, () -> InputStream> =
    path.fileName.toString() to { Files.newInputStream(path) }
