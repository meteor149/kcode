package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.settings.StoredAppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class MemoryAppSettingsStoreTest {
    @Test
    fun instancesDoNotShareSettingsAndNeitherSaveNorLoadLeaksMutableKeys() = runTest {
        val first = MemoryAppSettingsStore()
        val second = MemoryAppSettingsStore()
        val keys = mutableMapOf("custom" to "original", "other" to "retained")
        val searchKeys = mutableMapOf("custom.search" to "search", "other.search" to "retained")
        first.save(StoredAppSettings(modelApiKeys = keys, searchApiKeys = searchKeys, language = "en"))
        keys["custom"] = "changed"
        searchKeys["custom.search"] = "changed"
        assertEquals("original", first.load().modelApiKeys["custom"])
        assertEquals("search", first.load().searchApiKeys["custom.search"])
        val searchSnapshot = first.load().searchApiKeys as MutableMap<String, String>
        searchSnapshot.clear()
        assertEquals(2, first.load().searchApiKeys.size)
        val snapshot = first.load().modelApiKeys as MutableMap<String, String>
        snapshot.clear()
        assertEquals(2, first.load().modelApiKeys.size)
        assertEquals(defaultSettings(), second.load())
        first.close()
        assertFailsWith<IllegalStateException> { first.load() }
        assertFailsWith<IllegalStateException> { first.save(StoredAppSettings()) }
        second.close()
    }
}
