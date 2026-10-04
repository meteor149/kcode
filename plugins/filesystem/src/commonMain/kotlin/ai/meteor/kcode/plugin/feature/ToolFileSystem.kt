package ai.meteor.kcode.plugin.feature

import ai.koog.rag.base.files.FileMetadata
import ai.koog.rag.base.files.FileSystemProvider
import ai.meteor.kcode.plugin.api.FileContentKind
import ai.meteor.kcode.plugin.api.FileKind
import ai.meteor.kcode.plugin.api.FileSystemBackend
import kotlinx.io.Sink
import kotlinx.io.Source

/** Koog is a tool-layer detail. Remote/native providers only implement the application fs API. */
internal class ToolFileSystem(private val backend: FileSystemBackend) : FileSystemProvider.ReadWrite<String> {
    override fun fromAbsolutePathString(path: String): String = backend.normalize(path)
    override fun toAbsolutePathString(path: String): String = backend.normalize(path)
    override fun joinPath(base: String, vararg parts: String): String = backend.joinPath(base, *parts)
    override fun name(path: String): String = backend.name(path)
    override fun extension(path: String): String = backend.extension(path)
    override fun parent(path: String): String? = backend.parent(path)
    override fun relativize(root: String, path: String): String? = backend.relativize(root, path)
    override suspend fun metadata(path: String): FileMetadata? = backend.metadata(path)?.let {
        FileMetadata(if (it.kind == FileKind.File) FileMetadata.FileType.File else FileMetadata.FileType.Directory, it.hidden)
    }
    override suspend fun list(directory: String): List<String> = backend.list(directory)
    override suspend fun exists(path: String): Boolean = backend.exists(path)
    override suspend fun getFileContentType(path: String): FileMetadata.FileContentType =
        if (backend.contentKind(path) == FileContentKind.Text) FileMetadata.FileContentType.Text else FileMetadata.FileContentType.Binary
    override suspend fun readBytes(path: String): ByteArray = backend.readBytes(path)
    override suspend fun inputStream(path: String): Source = backend.inputStream(path)
    override suspend fun size(path: String): Long = backend.size(path)
    override suspend fun create(path: String, type: FileMetadata.FileType) =
        backend.create(path, if (type == FileMetadata.FileType.File) FileKind.File else FileKind.Directory)
    override suspend fun writeBytes(path: String, data: ByteArray) = backend.writeBytes(path, data)
    override suspend fun outputStream(path: String, append: Boolean): Sink = backend.outputStream(path, append)
    override suspend fun move(source: String, target: String) = backend.move(source, target)
    override suspend fun copy(source: String, target: String) = backend.copy(source, target)
    override suspend fun delete(path: String) = backend.delete(path)
}
