package ai.meteor.kcode.plugin

import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.createDesktopProfileHost
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableExport
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.recovery.ProfileHostContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import ai.meteor.kcode.plugin.profiles.CommittedProfileGeneration
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.cordis.packages.PluginPackageArchive
import org.cordis.packages.packageFileSha256

/**
 * Opt-in native acceptance through the actual shipped root/settings contribution and dialogs.
 * Inputs are selected manually; checks run only after closing the window. The task fails if
 * the required exports/imports were not completed. Its fresh home is retained as local evidence.
 */
object ProfileDesktopAcceptance {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "The acceptance task always creates a fresh isolated home" }
        Locale.setDefault(Locale.ENGLISH)
        val home = Files.createTempDirectory("kcode-profile-desktop-acceptance-")
        println("PROFILE_ACCEPTANCE_HOME=$home")
        println("Export native to native.kcode-profile.json and native.kprofile in this directory.")
        println("Import those selected files as desktop-json and desktop-archive; close the window to verify.")
        val applicationWindow = AtomicReference<Frame?>()
        val host = runBlocking {
            createDesktopProfileHost(homeDirectory = home, applicationWindow = applicationWindow::get)
        }
        try {
            val repository = FileProfileRepository(home.resolve("profiles").toFile())
            val initial = runBlocking { checkNotNull(repository.loadCommitted("native")) }
            val initialSelection = runBlocking { checkNotNull(host.profileCommands).catalogue().selectedProfileId }
            application(exitProcessOnExit = false) {
                Window(
                    title = "kcode Profile desktop acceptance",
                    state = rememberWindowState(
                        size = DpSize(1180.dp, 780.dp),
                        position = WindowPosition(Alignment.Center),
                    ),
                    onCloseRequest = ::exitApplication,
                ) {
                    DisposableEffect(window) {
                        applicationWindow.set(window)
                        onDispose { applicationWindow.compareAndSet(window, null) }
                    }
                    ProfileHostContent(host, ApplicationHostOptions(conversationSettingsControlsAvailable = true), "en")
                }
            }
            runBlocking { verify(home, host, initial, initialSelection) }
            println("PROFILE_ACCEPTANCE_PASSED: selected JSON/archive exports and verified draft imports; native generation unchanged")
        } finally {
            runBlocking { host.close() }
        }
    }

    internal suspend fun verify(home: Path, host: KcodeProfileHost, initial: CommittedProfileGeneration, initialSelection: String?) {
        val repository = FileProfileRepository(home.resolve("profiles").toFile())
        val client = checkNotNull(host.profileCommands)
        val catalogue = client.catalogue()
        check(catalogue.activeProfileId == "native" && catalogue.selectedProfileId == initialSelection) {
            "File exchange must not activate a draft"
        }
        check(repository.loadCommitted("native") == initial) { "File exchange changed the committed generation" }
        val exported = Files.readString(home.resolve("native.kcode-profile.json"))
        val reviewed = client.exportPortable(ProfilePortableExport(ProfileTarget("native"), catalogue.revision))
        check(Json.parseToJsonElement(exported) == Json.parseToJsonElement(reviewed)) { "Selected JSON export differs from reviewed intent" }
        val archive = home.resolve("native.kprofile").toFile()
        check(Files.size(archive.toPath()) > 0) { "No selected archive export" }
        val inspected = PluginPackageArchive().inspect(archive, packageFileSha256(archive))
        check(inspected.manifest.variants.single().runtime.id == "kcode-profile-archive") { "Selected export is not a Profile archive" }
        ZipFile(archive).use { zip ->
            val entry = checkNotNull(zip.getEntry("profile.json"))
            check(entry.size in 1..2_097_152) { "Invalid archived Profile document size" }
            val document = zip.getInputStream(entry).use { it.readBytes().decodeToString(throwOnInvalidSequence = true) }
            check(Json.parseToJsonElement(document) == Json.parseToJsonElement(reviewed)) { "Selected archive differs from reviewed intent" }
        }
        for (id in listOf("desktop-json", "desktop-archive")) {
            val draft = checkNotNull(client.draft(id)) { "Missing selected-file import: $id" }
            check(draft.bundles == initial.definition.bundles && draft.patches == initial.definition.patches) {
                "Imported composition differs from the source: $id"
            }
            val preview = client.preview(ProfileTarget(id, ProfileSource.Draft))
            check(preview.packagesVerified && preview.diagnostics.isEmpty()) { "Imported draft does not verify: $id" }
            check(repository.loadCommitted(id) == null) { "Import unexpectedly committed: $id" }
        }
    }
}
