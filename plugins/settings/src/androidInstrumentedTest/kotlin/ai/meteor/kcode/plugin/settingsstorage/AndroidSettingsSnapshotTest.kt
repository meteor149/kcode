package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.settings.native.MmkvSettingsLease
import ai.meteor.kcode.settings.native.openMmkvSettingsLease
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(AndroidJUnit4::class)
class AndroidSettingsSnapshotTest {
    @Test
    fun partialSnapshotsRetainRawValuesWithoutInventingFeatureDefaults(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "partial-snapshot-${System.nanoTime()}").apply { mkdirs() }
        val lease = openMmkvSettingsLease(context, "partial-snapshot", rootDirectory = directory)
        try {
            val store = MmkvAppSettingsStore(lease, SettingsProtection.Transient)
            check(lease.access { it.encodeString("settings_snapshot.v1", """{"provider":"private.gateway","language":"en"}""") })
            assertEquals(Json.parseToJsonElement("""{"provider":"private.gateway","language":"en"}"""), store.load().legacyValues)
            check(lease.access { it.encodeString("settings_snapshot.v1", """{"provider":"","language":"","temperature":0}""") })
            assertEquals(kotlinx.serialization.json.Json.parseToJsonElement("""{"provider":"","language":"","temperature":0}"""), store.load().legacyValues)
        } finally {
            lease.close()
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 60_000)
    fun failedScalarOrCommitWritesRetainThePreviousSnapshotAcrossNativeReopen(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (failedKey in listOf("settings_snapshot.v2")) {
            val directory = File(context.cacheDir, "snapshot-failure-${System.nanoTime()}").apply { mkdirs() }
            val id = "snapshot-failure"
            var current: MmkvSettingsLease? = openMmkvSettingsLease(context, id, rootDirectory = directory)
            try {
                val previous = StoredAppSettings(namespaces = mapOf("fixture.settings" to
                    (Json.parseToJsonElement("""{"credential":"old-fixture","future":[null,{}]}""") as JsonObject)))
                val replacement = previous.copy(namespaces = mapOf("fixture.settings" to
                    (Json.parseToJsonElement("""{"credential":"new-fixture","future":[null,{}],"unknown.key":""}""") as JsonObject)))
                val first = requireNotNull(current)
                val store = MmkvAppSettingsStore(first, SettingsProtection.Transient)
                store.save(previous)
                assertFailsWith<IllegalStateException> {
                    first.access { mmkv ->
                        val native = MmkvScalarSettingsAccess(mmkv)
                        val failing = object : ScalarSettingsAccess by native {
                            override fun encodeString(key: String, value: String) =
                                if (key == failedKey) false else native.encodeString(key, value)
                        }
                        ScalarSettingsCodec().save(failing, replacement)
                    }
                }
                assertEquals(null, first.access { it.decodeString("model_provider") })
                assertEquals(previous, store.load())
                first.close()
                current = null
                val reopened = openMmkvSettingsLease(context, id, rootDirectory = directory)
                current = reopened
                val restored = MmkvAppSettingsStore(reopened, SettingsProtection.Transient)
                assertEquals(previous, restored.load())
                restored.save(replacement)
                assertEquals(replacement, restored.load())
                assertEquals(null, reopened.access { it.decodeString("model_api_key.obsolete") })
            } finally {
                current?.let { lease -> try { lease.access { it.clearAll() } } finally { lease.close() } }
                directory.deleteRecursively()
            }
        }
    }
}
