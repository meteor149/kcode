package ai.meteor.kcode.plugin.filesystem

import ai.meteor.kcode.plugin.nativefilesystem.AndroidAgentFileSystem

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import ai.koog.rag.base.files.model.FileSystemEntry
import ai.koog.serialization.kotlinx.KotlinxSerializer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AndroidMediaFileToolTest {
    @Test
    fun mediaToolReturnsPngAsNativeImageAttachment() = runBlocking {
        val physicalRoot = Files.createTempDirectory("kcode-agent-files")
        val fileSystem = AndroidAgentFileSystem()
        val imageFile = physicalRoot.resolve("images/sample.png")
        val bytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0xff.toByte(), 0x80.toByte(), 0x01,
        )
        fileSystem.writeBytes(fileSystem.fromAbsolutePathString(imageFile.toString()), bytes)
        val read = ReadMediaFileTool(fileSystem).execute(
            ReadMediaFileTool.Args(imageFile.toString()),
        )
        val attachment = ReadMediaFileTool(fileSystem)
            .encodeResultToParts(read, KotlinxSerializer())
            .filterIsInstance<MessagePart.Attachment>()
            .single()
        val source = attachment.source as AttachmentSource.Image
        val attachedBytes = (source.content as AttachmentContent.Binary.Bytes).data

        assertTrue(bytes.contentEquals(Files.readAllBytes(imageFile)))
        assertEquals(bytes.size.toLong(), read.size)
        assertEquals(ReadMediaFileTool.MediaType.Image, read.mediaType)
        assertEquals("image/png", source.mimeType)
        assertTrue(bytes.contentEquals(attachedBytes))
    }

    @Test
    fun mediaToolReturnsMp4AsNativeVideoAttachment() = runBlocking {
        val fileSystem = AndroidAgentFileSystem()
        val videoFile = Files.createTempDirectory("kcode-agent-files").resolve("sample.mp4")
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
            0x69, 0x73, 0x6f, 0x6d, 0x00, 0x00, 0x00, 0x00,
        )
        fileSystem.writeBytes(fileSystem.fromAbsolutePathString(videoFile.toString()), bytes)
        val tool = ReadMediaFileTool(fileSystem)

        val result = tool.execute(ReadMediaFileTool.Args(videoFile.toString()))
        val attachment = tool.encodeResultToParts(result, KotlinxSerializer())
            .filterIsInstance<MessagePart.Attachment>()
            .single()

        assertEquals(ReadMediaFileTool.MediaType.Video, result.mediaType)
        assertEquals("video/mp4", (attachment.source as AttachmentSource.Video).mimeType)
    }


}
