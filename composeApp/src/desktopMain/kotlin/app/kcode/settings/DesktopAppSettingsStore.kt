package app.kcode.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path

fun createDesktopAppSettingsStore(): AppSettingsStore {
    val settingsPath = Path.of(System.getProperty("user.home"), ".kcode", "settings.preferences_pb")
    Files.createDirectories(settingsPath.parent)
    return DataStoreAppSettingsStore(
        dataStore = PreferenceDataStoreFactory.createWithPath(
            produceFile = { settingsPath.toString().toPath() },
        ),
        protect = SecretCodec { it },
        reveal = SecretCodec { it },
        protection = SettingsProtection.DesktopAppData,
    )
}
