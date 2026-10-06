package ai.meteor.kcode.plugin.profileui

import android.net.Uri
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleArchiveReference
import ai.meteor.kcode.plugin.api.profiles.ProfileArchiveReference
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private class DocumentRequests {
    var read: CompletableDeferred<Uri?>? = null
    var write: CompletableDeferred<Uri?>? = null
    var bundles: CompletableDeferred<List<Uri>>? = null
    var archiveWrite: CompletableDeferred<Uri?>? = null
    fun busy() = read != null || write != null || bundles != null || archiveWrite != null
}

@Composable
internal actual fun rememberProfileDocumentFiles(): ProfileDocumentFiles {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val requests = remember { DocumentRequests() }
    val bundles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        requests.bundles?.complete(uris)
        requests.bundles = null
    }
    val read = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        requests.read?.complete(uri)
        requests.read = null
    }
    val write = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        requests.write?.complete(uri)
        requests.write = null
    }
    val archiveWrite = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        requests.archiveWrite?.complete(uri)
        requests.archiveWrite = null
    }
    DisposableEffect(requests) {
        onDispose {
            requests.read?.cancel()
            requests.write?.cancel()
            requests.bundles?.cancel()
            requests.archiveWrite?.cancel()
            requests.read = null
            requests.write = null
            requests.bundles = null
            requests.archiveWrite = null
        }
    }
    return remember(resolver, read, write, bundles, archiveWrite) {
        object : ProfileDocumentFiles {
            override suspend fun readArchive(title: String, consume: suspend (ProfileArchiveReference) -> Unit): Boolean {
                val uri = withContext(Dispatchers.Main.immediate) {
                    check(!requests.busy()) { "Document picker is busy" }
                    val pending = CompletableDeferred<Uri?>()
                    requests.read = pending
                    try { read.launch(arrayOf("*/*")) } catch (failure: Exception) {
                        if (requests.read === pending) requests.read = null
                        throw failure
                    }
                    try { pending.await() } finally { pending.cancel() }
                } ?: return false
                stageProfileBundles(listOf({ requireNotNull(resolver.openInputStream(uri)) })) { inputs ->
                    val input = inputs.single()
                    consume(ProfileArchiveReference(input.archivePath, input.sha256))
                }
                return true
            }

            override suspend fun writeArchive(title: String, name: String, archive: ProfileArchiveReference): Boolean {
                val uri = withContext(Dispatchers.Main.immediate) {
                    check(!requests.busy()) { "Document picker is busy" }
                    val pending = CompletableDeferred<Uri?>()
                    requests.archiveWrite = pending
                    try { archiveWrite.launch(name) } catch (failure: Exception) {
                        if (requests.archiveWrite === pending) requests.archiveWrite = null
                        throw failure
                    }
                    try { pending.await() } finally { pending.cancel() }
                } ?: return false
                withContext(Dispatchers.IO) {
                    ensureActive()
                    requireNotNull(resolver.openOutputStream(uri, "wt")).use { output -> copyProfileArchive(archive, output) }
                    ensureActive()
                }
                return true
            }
            override suspend fun readBundles(title: String, consume: suspend (List<ProfileBundleArchiveReference>) -> Unit): Boolean {
                val uris = withContext(Dispatchers.Main.immediate) {
                    check(!requests.busy()) { "Document picker is busy" }
                    val pending = CompletableDeferred<List<Uri>>()
                    requests.bundles = pending
                    try { bundles.launch(arrayOf("*/*")) } catch (failure: Exception) {
                        if (requests.bundles === pending) requests.bundles = null
                        throw failure
                    }
                    try { pending.await() } finally { pending.cancel() }
                }
                if (uris.isEmpty()) return false
                stageProfileBundles(uris.map { uri -> { requireNotNull(resolver.openInputStream(uri)) } }, consume = consume)
                return true
            }
            override suspend fun read(title: String): String? {
                val uri = withContext(Dispatchers.Main.immediate) {
                    check(!requests.busy()) { "Document picker is busy" }
                    val pending = CompletableDeferred<Uri?>()
                    requests.read = pending
                    try {
                        read.launch(arrayOf("application/json", "text/plain"))
                    } catch (failure: Exception) {
                        if (requests.read === pending) requests.read = null
                        throw failure
                    }
                    // Keep the slot until the OS returns: a late result must not complete a newer request.
                    try { pending.await() } finally { pending.cancel() }
                } ?: return null
                return withContext(Dispatchers.IO) {
                    ensureActive()
                    val bytes = requireNotNull(resolver.openInputStream(uri)).use { it.readNBytes(ProfileDocumentByteLimit + 1) }
                    ensureActive()
                    require(bytes.size <= ProfileDocumentByteLimit) { "Profile document is too large" }
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                }
            }

            override suspend fun write(title: String, name: String, document: String): Boolean {
                val bytes = document.toByteArray(Charsets.UTF_8)
                require(bytes.size <= ProfileDocumentByteLimit) { "Profile document is too large" }
                val uri = withContext(Dispatchers.Main.immediate) {
                    check(!requests.busy()) { "Document picker is busy" }
                    val pending = CompletableDeferred<Uri?>()
                    requests.write = pending
                    try {
                        write.launch(name)
                    } catch (failure: Exception) {
                        if (requests.write === pending) requests.write = null
                        throw failure
                    }
                    try { pending.await() } finally { pending.cancel() }
                } ?: return false
                return withContext(Dispatchers.IO) {
                    ensureActive()
                    requireNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(bytes); it.flush() }
                    ensureActive()
                    true
                }
            }
        }
    }
}
