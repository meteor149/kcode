package ai.meteor.kcode.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsCommandEnvelopeTest {
    @Test
    fun arbitraryFeatureIdentitiesAndExplicitEmptyValuesNeedNoSdkFields() {
        val fields = mapOf("plugin.example/display.scale" to "1.5", "plugin.example/api-key" to "")
        val update = SettingsUpdate(fields)
        assertEquals(fields, update.values)
        assertEquals(fields.keys.toList(), update.suppliedFields)
        assertFalse(update.isEmpty)
        assertTrue(SettingsUpdate().isEmpty)
        assertEquals(update, SettingsUpdate(fields))
    }

    @Test
    fun sourceAndReturnedMapsCannotChangeAnAdmittedRequest() {
        val source = linkedMapOf("plugin.example/api-key" to "private-value", "plugin.example/endpoint" to "original")
        val update = SettingsUpdate(source)
        source["plugin.example/api-key"] = "changed"
        source["extra"] = "late"
        (update.values as MutableMap<String, String>)["plugin.example/endpoint"] = "changed"
        assertEquals(mapOf("plugin.example/api-key" to "private-value", "plugin.example/endpoint" to "original"), update.values)
        assertFalse("private-value" in update.toString())
        assertEquals(listOf("plugin.example/api-key", "plugin.example/endpoint"), update.suppliedFields)
    }

    @Test
    fun invalidFieldIdentitiesFailBeforeFeatureDispatch() {
        for (identity in listOf("", " ", " feature/field", "feature/field ", "feature\nfield")) {
            assertFailsWith<IllegalArgumentException> { SettingsUpdate(mapOf(identity to "value")) }
        }
    }
}
