package ai.meteor.kcode.plugin.api

import kotlinx.io.Sink
import kotlinx.io.Source

/** Execution-world paths are interpreted exclusively by their provider, never by tool consumers. */
interface FileSystemBackend {
    fun normalize(path: String): String
    fun joinPath(base: String, vararg parts: String): String
    fun name(path: String): String
    fun extension(path: String): String
    fun parent(path: String): String?
    fun relativize(root: String, path: String): String?
    suspend fun metadata(path: String): FileInfo?
    suspend fun list(directory: String): List<String>
    suspend fun exists(path: String): Boolean
    suspend fun contentKind(path: String): FileContentKind
    suspend fun readBytes(path: String): ByteArray
    /** Caller owns the returned stream and must close it. */
    suspend fun inputStream(path: String): Source
    suspend fun size(path: String): Long
    suspend fun create(path: String, kind: FileKind)
    suspend fun writeBytes(path: String, data: ByteArray)
    /** Caller owns the returned stream and must close it. */
    suspend fun outputStream(path: String, append: Boolean = false): Sink
    suspend fun move(source: String, target: String)
    suspend fun copy(source: String, target: String)
    suspend fun delete(path: String)
}

enum class FileKind { File, Directory }
enum class FileContentKind { Text, Binary }
data class FileInfo(val kind: FileKind, val hidden: Boolean)

/** Foreground contract independent of model schemas, java Process and platform privilege APIs. */
fun interface ShellBackend {
    suspend fun run(request: ShellRequest): ShellResult
}

data class ShellRequest(val command: String, val workingDirectory: String? = null) {
    init {
        require(command.isNotBlank() && '\u0000' !in command) { "Invalid shell command" }
        require(workingDirectory == null || '\u0000' !in workingDirectory) { "Invalid working directory" }
    }
}

data class ShellResult(val output: String, val exitCode: Int?)
