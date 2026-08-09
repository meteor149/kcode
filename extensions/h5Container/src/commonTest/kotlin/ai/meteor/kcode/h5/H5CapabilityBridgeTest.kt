package ai.meteor.kcode.h5

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertTrue("window.__kcodeDispatch" in KCODE_H5_SDK)
    }

    @Test
    fun sdkPolyfillsStandardWebApis() {
        assertTrue("Object.defineProperty(navigator, 'geolocation'" in KCODE_H5_SDK)
        assertTrue("Object.defineProperty(navigator, 'getBattery'" in KCODE_H5_SDK)
        assertTrue("installSensor('Accelerometer'" in KCODE_H5_SDK)
        assertTrue("installSensor('Gyroscope'" in KCODE_H5_SDK)
        assertTrue("installSensor('Magnetometer'" in KCODE_H5_SDK)
        assertTrue("installSensor('AbsoluteOrientationSensor'" in KCODE_H5_SDK)
        assertFalse("window.kcode =" in KCODE_H5_SDK)
        assertFalse("kcode.capabilities" in KCODE_H5_SDK)
    }

    @Test
    fun sdkDoesNotExposeHostOnlyCapabilities() {
        listOf(
            "sensor.pressure",
            "sensor.light",
            "sensor.proximity",
            "device.openSettings",
            "media.scanQrCode",
        ).forEach { capability ->
            assertFalse(capability in KCODE_H5_SDK, capability)
        }
    }
}
