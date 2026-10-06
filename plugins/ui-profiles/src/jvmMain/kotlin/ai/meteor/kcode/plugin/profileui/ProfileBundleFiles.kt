package ai.meteor.kcode.plugin.profileui

import ai.meteor.kcode.plugin.api.profiles.ProfileBundleArchiveReference
import ai.meteor.kcode.plugin.api.profiles.ProfileArchiveReference
import java.io.OutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Bound the complete selection without buffering archives in memory. */
internal const val ProfileBundleSelectionByteLimit = 512L * 1024 * 1024

/** The caller owns the output; hash verification completes before Desktop publishes its temporary file. */
internal suspend fun copyProfileArchive(archive: ProfileArchiveReference, output: OutputStream) = withContext(Dispatchers.IO) {
    val operation = currentCoroutineContext()
    require(archive.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid Profile archive digest" }
    runInterruptible {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newInputStream(Path.of(archive.archivePath)).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                operation.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= ProfileBundleSelectionByteLimit) { "Profile archive is too large" }
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == archive.sha256) { "Profile archive changed" }
        operation.ensureActive()
        output.flush()
    }
}

internal suspend fun stageProfileBundles(
    sources: List<() -> InputStream>,
    byteLimit: Long = ProfileBundleSelectionByteLimit,
    consume: suspend (List<ProfileBundleArchiveReference>) -> Unit,
) = withContext(Dispatchers.IO) {
    require(sources.isNotEmpty() && sources.size <= 16) { "Select between one and sixteen Bundles" }
    val operationContext = currentCoroutineContext()
    val directory = Files.createTempDirectory("kcode-bundle-import-")
    val files = mutableListOf<Path>()
    try {
        var total = 0L
        val references = sources.mapIndexed { index, open ->
            operationContext.ensureActive()
            val file = directory.resolve("$index.kbundle")
            files.add(file)
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                runInterruptible {
                    open().use { input ->
                        Files.newOutputStream(file).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                operationContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                total += count
                                require(total <= byteLimit) { "Bundle selection is too large" }
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                            }
                        }
                    }
                }
            } catch (failure: InterruptedIOException) {
                operationContext.ensureActive()
                throw failure
            }
            ProfileBundleArchiveReference(file.toString(), digest.digest().joinToString("") { "%02x".format(it) })
        }
        operationContext.ensureActive()
        consume(references)
    } finally {
        var cleanupFailure: Exception? = null
        (files.asReversed() + listOf(directory)).forEach { file ->
            try { Files.deleteIfExists(file) } catch (failure: Exception) {
                if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure!!.addSuppressed(failure)
            }
        }
        cleanupFailure?.let { throw it }
    }
}
