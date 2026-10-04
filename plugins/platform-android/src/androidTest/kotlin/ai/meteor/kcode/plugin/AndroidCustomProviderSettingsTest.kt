package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.settingsstorage.MmkvAppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.meteor.kcode.settings.native.openMmkvSettingsLease
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class AndroidCustomProviderSettingsTest {
    @Test
    fun legacyAndCustomProviderKeysRestoreWithoutCatalogAndClearWhenRemoved(): Unit = runBlocking {
        val lease = openMmkvSettingsLease(InstrumentationRegistry.getInstrumentation().targetContext, "test.provider-settings.${System.nanoTime()}")
        val store = MmkvAppSettingsStore(lease, SettingsProtection.Transient)
        try {
            check(lease.access { it.encodeString("model_api_key.OpenAI", "legacy") })
            assertEquals(mapOf("OpenAI" to "legacy"), store.load().modelApiKeys)
            val settings = StoredAppSettings(provider = "acme.gateway:v1", modelId = "private-model",
                modelApiKeys = mapOf("OpenAI" to "legacy", "acme.gateway:v1" to "fixture"))
            store.save(settings)
            assertEquals(settings, MmkvAppSettingsStore(lease, SettingsProtection.Transient).load())
            store.save(settings.copy(modelApiKeys = emptyMap()))
            assertEquals(emptyMap(), store.load().modelApiKeys)
            assertEquals("", lease.access { it.decodeString("model_api_key.acme.gateway:v1") })
        } finally {
            lease.access { it.clearAll() }
            lease.close()
        }
    }
}
