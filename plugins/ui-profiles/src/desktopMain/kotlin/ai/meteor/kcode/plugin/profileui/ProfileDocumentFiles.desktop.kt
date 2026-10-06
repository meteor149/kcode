package ai.meteor.kcode.plugin.profileui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleArchiveReference
import ai.meteor.kcode.plugin.api.profiles.ProfileArchiveReference
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
internal actual fun rememberProfileDocumentFiles(): ProfileDocumentFiles = remember {
    object : ProfileDocumentFiles {
        override suspend fun readArchive(title: String, consume: suspend (ProfileArchiveReference) -> Unit): Boolean {
            val path = choose(title, FileDialog.LOAD) ?: return false
            stageProfileBundles(listOf({ Files.newInputStream(path) })) { inputs ->
                val input = inputs.single()
                consume(ProfileArchiveReference(input.archivePath, input.sha256))
            }
            return true
        }

        override suspend fun writeArchive(title: String, name: String, archive: ProfileArchiveReference): Boolean {
            val path = choose(title, FileDialog.SAVE, name) ?: return false
            return withContext(Dispatchers.IO) {
                ensureActive()
                val temporary = Files.createTempFile(path.parent, ".kcode-profile-", ".tmp")
                try {
                    Files.newOutputStream(temporary).use { output -> copyProfileArchive(archive, output) }
                    FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
                    ensureActive()
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    true
                } finally { Files.deleteIfExists(temporary) }
            }
        }

        override suspend fun readBundles(title: String, consume: suspend (List<ProfileBundleArchiveReference>) -> Unit): Boolean {
            val paths = chooseMany(title)
            if (paths.isEmpty()) return false
            stageProfileBundles(paths.map { path -> { Files.newInputStream(path) } }, consume = consume)
            return true
        }
        override suspend fun read(title: String): String? {
            val path = choose(title, FileDialog.LOAD) ?: return null
            return withContext(Dispatchers.IO) {
                ensureActive()
                val bytes = Files.newInputStream(path).use { it.readNBytes(ProfileDocumentByteLimit + 1) }
                require(bytes.size <= ProfileDocumentByteLimit) { "Profile document is too large" }
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            }
        }

        override suspend fun write(title: String, name: String, document: String): Boolean {
            val bytes = document.toByteArray(Charsets.UTF_8)
            require(bytes.size <= ProfileDocumentByteLimit) { "Profile document is too large" }
            val path = choose(title, FileDialog.SAVE, name) ?: return false
            return withContext(Dispatchers.IO) {
                ensureActive()
                val temporary = Files.createTempFile(path.parent, ".kcode-profile-", ".tmp")
                try {
                    FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) { ensureActive(); channel.write(buffer) }
                        channel.force(true)
                    }
                    ensureActive()
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    true
                } finally { Files.deleteIfExists(temporary) }
            }
        }
    }
}

private suspend fun chooseMany(title: String): List<Path> = suspendCancellableCoroutine { continuation ->
    var dialog: FileDialog? = null
    continuation.invokeOnCancellation { EventQueue.invokeLater { dialog?.dispose() } }
    EventQueue.invokeLater {
        if (!continuation.isActive) return@invokeLater
        try {
            val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow as? Frame
            val picker = FileDialog(owner, title, FileDialog.LOAD)
            dialog = picker
            picker.isMultipleMode = true
            try {
                picker.isVisible = true
                val result = picker.files.map { it.toPath().toAbsolutePath() }
                if (continuation.isActive) continuation.resume(result)
            } finally { picker.dispose() }
        } catch (failure: Exception) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }
}

private suspend fun choose(title: String, mode: Int, name: String? = null): Path? = suspendCancellableCoroutine { continuation ->
    var dialog: FileDialog? = null
    continuation.invokeOnCancellation { EventQueue.invokeLater { dialog?.dispose() } }
    EventQueue.invokeLater {
        if (!continuation.isActive) return@invokeLater
        try {
            val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow as? Frame
            val picker = FileDialog(owner, title, mode)
            dialog = picker
            name?.let { picker.file = it }
            try {
                picker.isVisible = true
                val result = picker.file?.let { Path.of(picker.directory, it).toAbsolutePath() }
                if (continuation.isActive) continuation.resume(result)
            } finally { picker.dispose() }
        } catch (failure: Exception) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }
}
