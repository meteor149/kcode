package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.PluginPackageImport
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileImportedOffersTest {
    @Test
    fun importedCodeUsesItsDigestInsteadOfANewerOfferAndHistoryNeverRewritesOffers(): Unit = runBlocking {
        val directory = Files.createTempDirectory("profile-imported-offers").toFile()
        try {
            val repository = FileProfileRepository(directory)
            val definition = ProfileDefinition(id = "imported")
            val digest = "a".repeat(64)
            val locked = LockedProfilePackage("example.code", "1.0.0", digest, "desktop", "b".repeat(64))
            val imported = PortableProfileDocument(definition = definition, bundles = emptyList(), lock = ProfileLock(packages = listOf(locked)))
            repository.writeDraft(ProfileDraftDocument(definition, imported = imported), 0, createOnly = true)
            val offers = mapOf("example.code" to ProfilePackageOffer(PluginPackageImport("newer.kplugin", "c".repeat(64))))
            val lookedUp = mutableListOf<String>()
            val resolved = profileImportedOffers(repository, "imported", ProfileTarget("imported", ProfileSource.Draft), offers) {
                lookedUp += it
                PluginPackageImport("cache/$it/release.kplugin", it)
            }
            assertEquals(listOf(digest), lookedUp)
            assertEquals(digest, resolved.getValue("example.code").release.sha256)
            assertEquals(offers, profileImportedOffers(repository, "imported", ProfileTarget("imported", ProfileSource.History, 1), offers) {
                error("Historical generations use their committed deployment graph")
            })
            assertEquals(offers, profileImportedOffers(repository, "imported", ProfileTarget("imported"), offers) {
                error("Committed targets use their committed deployment graph")
            })
        } finally { directory.deleteRecursively() }
    }
}
