package app.kcode.h5

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class H5CapabilityBridgeTest {
    @Test
    fun requestProtocolDecodesUnknownFutureFields() {
        val request = H5BridgeJson.decodeFromString<H5BridgeRequest>(
            """{"id":"1","type":"invoke","method":"location.current","params":{"accuracy":"high"},"future":true}""",
        )
        assertEquals("location.current", request.method)
        assertEquals("high", request.params.getValue("accuracy").jsonPrimitive.content)
    }

    @Test
    fun responsesHaveStableEnvelope() {
        val response = H5BridgeJson.parseToJsonElement(
            bridgeFailure("7", "permission_denied", "Denied"),
        ).jsonObject
        assertEquals("7", response.getValue("id").jsonPrimitive.content)
        assertEquals("permission_denied", response.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertTrue("kcode.capabilities" in KCODE_H5_SDK)
    }
}
