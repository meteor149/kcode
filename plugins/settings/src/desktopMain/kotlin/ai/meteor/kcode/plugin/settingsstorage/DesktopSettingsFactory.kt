package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.plugin.api.SettingsStoreFactory
import ai.meteor.kcode.plugin.api.SettingsStoreResource
import ai.meteor.kcode.settings.SettingsProtection
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import okio.Path.Companion.toPath

fun desktopSettingsStoreFactory(
    settingsPath: Path = Path.of(System.getProperty("user.home"), ".kcode", "settings.preferences_pb"),
): SettingsStoreFactory = SettingsStoreFactory {
    Files.createDirectories(settingsPath.toAbsolutePath().parent)
    val job = SupervisorJob()
    try {
        val dataStore = PreferenceDataStoreFactory.createWithPath(
            scope = CoroutineScope(job + Dispatchers.IO),
            produceFile = { settingsPath.toAbsolutePath().toString().toPath() },
        )
        val store = DataStoreAppSettingsStore(dataStore, SecretCodec { it }, SecretCodec { it },
            SettingsProtection.DesktopAppData, active = { job.isActive })
        SettingsStoreResource(store) { job.cancelAndJoin() }
    } catch (error: Throwable) {
        job.cancelAndJoin()
        throw error
    }
}
