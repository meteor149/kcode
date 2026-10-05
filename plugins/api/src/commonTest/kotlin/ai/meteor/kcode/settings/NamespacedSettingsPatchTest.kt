package ai.meteor.kcode.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class NamespacedSettingsPatchTest {
    @Test
    fun concurrentChangesPreserveUnknownNamespacesAndFieldsWhileClearingOnlyEditedValues() {
        fun document(value: String) = Json.parseToJsonElement(value) as JsonObject
        val before = StoredAppSettings(namespaces = mapOf("plugin.example/v1" to
            document("""{"options":{"token":"old","remove":"old"},"unknown":null}""")))
        val draft = before.copy(namespaces = mapOf("plugin.example/v1" to
            document("""{"options":{"token":""},"unknown":null}""")))
        val latest = before.copy(namespaces = mapOf(
            "plugin.example/v1" to document("""{"options":{"token":"old","remove":"old","concurrent":7},"unknown":null}"""),
            "disabled.plugin" to document("""{"future":[null,{"key.with.dots":false}]}"""),
        ))
        val result = SettingsPatch.between(before, draft).apply(latest)
        assertEquals(latest.namespaces["disabled.plugin"], result.namespaces["disabled.plugin"])
        assertEquals(document("""{"options":{"token":"","concurrent":7},"unknown":null}"""),
            result.namespaces["plugin.example/v1"])
    }
    @Test
    fun creatingANamespaceMergesOnlyDraftFieldsIntoTheLatestDocument() {
        fun document(value: String) = Json.parseToJsonElement(value) as JsonObject
        val before = StoredAppSettings()
        val draft = before.copy(namespaces = mapOf("feature.web-search" to document(
            """{"provider":"exa","apiKeys":{"ui.route":""},"options":{"enabled":true}}""",
        )))
        val latest = before.copy(namespaces = mapOf("feature.web-search" to document(
            """{"provider":"google","apiKeys":{"command.route":"saved"},"options":{"future":null},"unknown":[1,2]}""",
        )))
        val merged = SettingsPatch.between(before, draft).apply(latest)
        assertEquals(document(
            """{"provider":"exa","apiKeys":{"command.route":"saved","ui.route":""},"options":{"future":null,"enabled":true},"unknown":[1,2]}""",
        ), merged.namespaces["feature.web-search"])
        assertEquals(draft, SettingsPatch.between(before, draft).apply(before))
    }

    @Test
    fun creatingEmptyObjectsPreservesConcurrentContentsAndExplicitNullsStayAtomic() {
        fun document(value: String) = Json.parseToJsonElement(value) as JsonObject
        val before = StoredAppSettings()
        val draft = before.copy(namespaces = mapOf("plugin.example" to document(
            """{"empty":{},"clear":null,"array":[1]}""",
        )))
        val patch = SettingsPatch.between(before, draft)
        assertEquals(draft, patch.apply(before))
        val latest = before.copy(namespaces = mapOf("plugin.example" to document(
            """{"empty":{"future":7},"clear":{"old":true},"array":[2,3]}""",
        )))
        assertEquals(document("""{"empty":{"future":7},"clear":null,"array":[1]}"""),
            patch.apply(latest).namespaces["plugin.example"])
    }

}
