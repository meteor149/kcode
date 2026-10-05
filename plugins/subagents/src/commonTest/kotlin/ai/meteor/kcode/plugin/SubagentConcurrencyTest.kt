package ai.meteor.kcode.plugin

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SubagentConcurrencyTest {
    @Test
    fun providerCapacityConfigRejectsAmbiguousOrUnsupportedLimits() {
        assertEquals(5, resolveConcurrencyLimit(Unit).value)
        assertEquals(2, resolveConcurrencyLimit(Json.parseToJsonElement("""{"maxConcurrency":2}""")).value)
        for (value in listOf(
            """{"maxConcurrency":0}""", """{"maxConcurrency":65}""",
            """{"maxConcurrency":2.5}""", """{"maxConcurrency":"2"}""", """{"other":2}""",
        )) {
            assertFailsWith<IllegalArgumentException> {
                resolveConcurrencyLimit(Json.parseToJsonElement(value))
            }
        }
    }
}
