package ai.meteor.kcode

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.validate
import ai.koog.agents.core.tools.validateNotNull
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import ai.koog.rag.base.files.FileMetadata
import ai.koog.rag.base.files.FileSystemProvider
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Returns image and video files as native Koog media attachments instead of Base64 text. */
class ReadMediaFileTool<Path>(
    private val fileSystem: FileSystemProvider.ReadOnly<Path>,
) : Tool<ReadMediaFileTool.Args, ReadMediaFileTool.Result>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    name = "read_media_file",
    description = """
        Reads an image or video file and sends it to the model as a native media attachment for
        visual analysis. Supported image containers include PNG, JPEG, GIF, WebP, BMP, and AVIF.
        Supported video containers include MP4, MOV, 3GP, WebM, Matroska, AVI, and MPEG. The selected
        model and provider must support the detected media type.
    """.trimIndent(),
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Absolute path to an image or video accessible through the current file provider")
        val path: String,
    )

    @Serializable
    data class Result(
        val path: String,
        val size: Long,
        val mediaType: MediaType,
        val format: String,
        val mimeType: String,
        @Transient val bytes: ByteArray = ByteArray(0),
    )

    @Serializable
    enum class MediaType {
        Image,
        Video,
    }

    override suspend fun execute(args: Args): Result {
        val path = fileSystem.fromAbsolutePathString(args.path)
        val metadata = validateNotNull(fileSystem.metadata(path)) { "File not found: ${args.path}" }
        validate(metadata.type == FileMetadata.FileType.File) { "Not a file: ${args.path}" }
        val bytes = fileSystem.readBytes(path)
        val media = validateNotNull(detectMedia(bytes, fileSystem.extension(path))) {
            "Unsupported or unrecognized image/video format: ${args.path}"
        }
        return Result(
            path = fileSystem.toAbsolutePathString(path),
            size = bytes.size.toLong(),
            mediaType = media.mediaType,
            format = media.format,
            mimeType = media.mimeType,
            bytes = bytes,
        )
    }

    override fun encodeResultToString(result: Result, serializer: JSONSerializer): String =
        "Read ${result.mediaType.name.lowercase()} ${result.path} " +
            "(${result.mimeType}, ${result.size} bytes) and attached it for visual analysis."

    override fun encodeResultToParts(
        result: Result,
        serializer: JSONSerializer,
    ): List<MessagePart.ContentPart> {
        val content = AttachmentContent.Binary.Bytes(result.bytes)
        val fileName = result.path.substringAfterLast('/').substringAfterLast('\\')
        val source = when (result.mediaType) {
            MediaType.Image -> AttachmentSource.Image(
                content = content,
                format = result.format,
                mimeType = result.mimeType,
                fileName = fileName,
            )

            MediaType.Video -> AttachmentSource.Video(
                content = content,
                format = result.format,
                mimeType = result.mimeType,
                fileName = fileName,
            )
        }
        return listOf(
            MessagePart.Text(encodeResultToString(result, serializer)),
            MessagePart.Attachment(source),
        )
    }
}

private data class DetectedMedia(
    val mediaType: ReadMediaFileTool.MediaType,
    val format: String,
    val mimeType: String,
)

private fun detectMedia(bytes: ByteArray, extension: String): DetectedMedia? {
    val normalizedExtension = extension.lowercase()
    return when {
        bytes.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ->
            image("png", "image/png")
        bytes.startsWith(0xff, 0xd8, 0xff) -> image("jpeg", "image/jpeg")
        bytes.asciiAt(0, "GIF87a") || bytes.asciiAt(0, "GIF89a") -> image("gif", "image/gif")
        bytes.asciiAt(0, "RIFF") && bytes.asciiAt(8, "WEBP") -> image("webp", "image/webp")
        bytes.asciiAt(0, "BM") -> image("bmp", "image/bmp")
        bytes.isIsoBaseMedia() && bytes.asciiAt(8, "avif") -> image("avif", "image/avif")
        bytes.asciiAt(0, "RIFF") && bytes.asciiAt(8, "AVI ") -> video("avi", "video/x-msvideo")
        bytes.startsWith(0x1a, 0x45, 0xdf, 0xa3) && bytes.containsAscii("webm", 64) ->
            video("webm", "video/webm")
        bytes.startsWith(0x1a, 0x45, 0xdf, 0xa3) -> video("mkv", "video/x-matroska")
        bytes.startsWith(0x00, 0x00, 0x01, 0xba) || bytes.startsWith(0x00, 0x00, 0x01, 0xb3) ->
            video("mpeg", "video/mpeg")
        bytes.isIsoBaseMedia() && normalizedExtension == "mov" -> video("mov", "video/quicktime")
        bytes.isIsoBaseMedia() && normalizedExtension in setOf("3gp", "3gpp") -> video("3gp", "video/3gpp")
        bytes.isIsoBaseMedia() -> video("mp4", "video/mp4")
        else -> null
    }
}

private fun image(format: String, mimeType: String) = DetectedMedia(
    ReadMediaFileTool.MediaType.Image,
    format,
    mimeType,
)

private fun video(format: String, mimeType: String) = DetectedMedia(
    ReadMediaFileTool.MediaType.Video,
    format,
    mimeType,
)

private fun ByteArray.startsWith(vararg expected: Int): Boolean =
    size >= expected.size && expected.indices.all { index -> this[index].toInt() and 0xff == expected[index] }

private fun ByteArray.asciiAt(offset: Int, expected: String): Boolean =
    size >= offset + expected.length && expected.indices.all { index -> this[offset + index].toInt() == expected[index].code }

private fun ByteArray.containsAscii(expected: String, limit: Int): Boolean {
    val lastStart = minOf(size, limit) - expected.length
    return lastStart >= 0 && (0..lastStart).any { asciiAt(it, expected) }
}

private fun ByteArray.isIsoBaseMedia(): Boolean = asciiAt(4, "ftyp")
