package ai.meteor.kcode.plugin

import ai.meteor.kcode.createDesktopProfileHost
import ai.meteor.kcode.plugin.api.profiles.ProfileArchiveImport
import ai.meteor.kcode.plugin.api.profiles.ProfileArchiveReference
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableExport
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableImport
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ProfileDesktopAcceptanceVerifierTest {
    @Test
    fun incompleteAndDifferentFilesFailAndCompleteVerifiedExchangePasses(): Unit = runBlocking {
        val home = Files.createTempDirectory("kcode-profile-acceptance-verifier-")
        val host = createDesktopProfileHost(homeDirectory = home)
        try {
            host.state.value.failure?.let { throw it }
            val client = checkNotNull(host.profileCommands)
            val initial = checkNotNull(FileProfileRepository(home.resolve("profiles").toFile()).loadCommitted("native"))
            val initialSelection = client.catalogue().selectedProfileId
            suspend fun verify() = ProfileDesktopAcceptance.verify(home, host, initial, initialSelection)
            assertFailsWith<NoSuchFileException> { verify() }
            val request = ProfilePortableExport(ProfileTarget("native"), client.catalogue().revision)
            val document = client.exportPortable(request)
            Files.writeString(home.resolve("native.kcode-profile.json"), document)
            assertFailsWith<NoSuchFileException> { verify() }
            val archiveFile = home.resolve("native.kprofile")
            var archive: ProfileArchiveReference? = null
            client.exportArchive(request) { reference ->
                Files.copy(java.nio.file.Path.of(reference.archivePath), archiveFile)
                archive = ProfileArchiveReference(archiveFile.toString(), reference.sha256)
            }
            assertFailsWith<IllegalStateException> { verify() }
            client.importPortable(ProfilePortableImport(document, "desktop-json", client.catalogue().revision))
            assertFailsWith<IllegalStateException> { verify() }
            client.importArchive(ProfileArchiveImport(checkNotNull(archive), "desktop-archive", client.catalogue().revision))
            verify()
            Files.writeString(home.resolve("native.kcode-profile.json"), "{}")
            assertFailsWith<IllegalStateException> { verify() }
        } finally {
            try { host.close() } finally { home.toFile().deleteRecursively() }
        }
    }
}
