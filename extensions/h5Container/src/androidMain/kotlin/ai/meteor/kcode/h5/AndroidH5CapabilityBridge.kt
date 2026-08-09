package ai.meteor.kcode.h5

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.CancellationSignal
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.MediaStore
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class AndroidH5CapabilityBridge(
    private val activity: Activity,
    private val webView: WebView,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sensorManager = activity.getSystemService(SensorManager::class.java)
    private val locationManager = activity.getSystemService(LocationManager::class.java)
    private val approvedForSession = mutableSetOf<String>()
    private val subscriptions = mutableMapOf<String, () -> Unit>()
    private var permissionRequest: PendingPermission? = null
    private var activityRequest: PendingActivity? = null
    private var nextRequestCode = 41_000

    fun install() {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "The installed Android System WebView does not support secure web messages"
        }
        val origins = setOf(PREVIEW_ORIGIN)
        WebViewCompat.addWebMessageListener(
            webView,
            BRIDGE_OBJECT,
            origins,
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: androidx.webkit.JavaScriptReplyProxy,
                ) {
                    if (!isMainFrame || sourceOrigin.toString() != PREVIEW_ORIGIN) return
                    val payload = message.data ?: return
                    scope.launch { handle(payload) }
                }
            },
        )
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, KCODE_H5_SDK, origins)
        }
    }

    fun injectFallbackSdk() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            webView.evaluateJavascript(KCODE_H5_SDK, null)
        }
    }

    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        val pending = permissionRequest ?: return false
        if (pending.requestCode != requestCode) return false
        permissionRequest = null
        pending.result.complete(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
        return true
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val pending = activityRequest ?: return false
        if (pending.requestCode != requestCode) return false
        activityRequest = null
        pending.result.complete(ActivityResult(resultCode, data))
        return true
    }

    fun handleWebPermissionRequest(request: PermissionRequest) {
        if (request.origin.toString().removeSuffix("/") != PREVIEW_ORIGIN) {
            request.deny()
            return
        }
        scope.launch {
            val requested = request.resources.toSet()
            val permissions = buildList {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in requested) add(Manifest.permission.CAMERA)
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in requested) add(Manifest.permission.RECORD_AUDIO)
            }
            val supported = requested.filter {
                it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
            }
            runCatching {
                require(permissions.isNotEmpty() && supported.size == requested.size) { "Unsupported web permission" }
                requirePermissions(*permissions.toTypedArray())
                request.grant(supported.toTypedArray())
            }.onFailure { request.deny() }
        }
    }

    fun handleGeolocationPermission(origin: String, callback: GeolocationPermissions.Callback) {
        if (origin.removeSuffix("/") != PREVIEW_ORIGIN) {
            callback.invoke(origin, false, false)
            return
        }
        scope.launch {
            val granted = runCatching {
                requirePermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            }.isSuccess
            callback.invoke(origin, granted, false)
        }
    }

    fun handleFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        scope.launch {
            val uris = runCatching {
                val capturesImage = params.isCaptureEnabled && params.acceptTypes.all {
                    it.isBlank() || it.startsWith("image/")
                }
                if (capturesImage) {
                    requirePermissions(Manifest.permission.CAMERA)
                    val file = newMediaFile("photo", "jpg")
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.h5.fileprovider", file)
                    val result = launchRawForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                    if (result.resultCode == Activity.RESULT_OK) arrayOf(uri) else null
                } else {
                    val result = launchRawForResult(params.createIntent())
                    WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                }
            }.getOrNull()
            callback.onReceiveValue(uris)
        }
        return true
    }

    fun close() {
        subscriptions.values.toList().forEach { runCatching(it) }
        subscriptions.clear()
        permissionRequest?.result?.cancel()
        activityRequest?.result?.cancel()
        scope.cancel()
    }

    private suspend fun handle(payload: String) {
        val request = runCatching { H5BridgeJson.decodeFromString<H5BridgeRequest>(payload) }
            .getOrElse {
                send(bridgeFailure("unknown", "invalid_request", it.message ?: "Invalid bridge request"))
                return
            }
        val response = runCatching {
            when (request.type) {
                "list" -> bridgeSuccess(request.id, capabilityList())
                "invoke" -> bridgeSuccess(request.id, invoke(requireNotNull(request.method), request.params))
                "subscribe" -> bridgeSuccess(request.id, subscribe(requireNotNull(request.method), request.params))
                "unsubscribe" -> bridgeSuccess(request.id, unsubscribe(requireNotNull(request.subscriptionId)))
                else -> error("Unsupported request type: ${request.type}")
            }
        }.getOrElse { error ->
            val code = when (error) {
                is SecurityException -> "permission_denied"
                is UnsupportedOperationException -> "not_supported"
                else -> "native_error"
            }
            bridgeFailure(request.id, code, error.message ?: "Capability call failed")
        }
        send(response)
    }

    private fun capabilityList(): JsonArray = buildJsonArray {
        descriptors().forEach { add(H5BridgeJson.encodeToJsonElement(H5CapabilityDescriptor.serializer(), it)) }
    }

    private fun descriptors(): List<H5CapabilityDescriptor> = listOf(
        descriptor("location.current", locationAvailable(), sensitive = true),
        descriptor("location.watch", locationAvailable(), subscription = true, sensitive = true),
        sensorDescriptor("sensor.orientation", Sensor.TYPE_ROTATION_VECTOR),
        sensorDescriptor("sensor.accelerometer", Sensor.TYPE_ACCELEROMETER),
        sensorDescriptor("sensor.gyroscope", Sensor.TYPE_GYROSCOPE),
        sensorDescriptor("sensor.magneticField", Sensor.TYPE_MAGNETIC_FIELD),
        descriptor("device.vibrate", activity.getSystemService(VibratorManager::class.java).defaultVibrator.hasVibrator(), sensitive = true),
        descriptor("device.battery", true),
    )

    private fun descriptor(
        id: String,
        available: Boolean,
        subscription: Boolean = false,
        sensitive: Boolean = false,
    ) = H5CapabilityDescriptor(id, available, subscription, sensitive, PLATFORM, if (available) null else "Unavailable on this device")

    private fun sensorDescriptor(id: String, type: Int) = descriptor(
        id = id,
        available = sensorManager.getDefaultSensor(type) != null,
        subscription = true,
        sensitive = true,
    )

    private suspend fun invoke(method: String, params: JsonObject): JsonElement {
        requireAvailable(method, subscription = false)
        if (isSensitive(method)) requireSessionApproval(method)
        return when (method) {
            "location.current" -> currentLocation(params)
            "device.vibrate" -> vibrate(params)
            "device.battery" -> battery()
            else -> throw UnsupportedOperationException("$method is subscription-only or unsupported")
        }
    }

    private suspend fun subscribe(method: String, params: JsonObject): JsonElement {
        requireAvailable(method, subscription = true)
        if (isSensitive(method)) requireSessionApproval(method)
        val subscriptionId = "subscription-${System.currentTimeMillis()}-${subscriptions.size + 1}"
        val cleanup = when (method) {
            "location.watch" -> subscribeLocation(subscriptionId, params)
            else -> subscribeSensor(subscriptionId, method, params)
        }
        subscriptions[subscriptionId] = cleanup
        return buildJsonObject { put("subscriptionId", subscriptionId) }
    }

    private fun unsubscribe(subscriptionId: String): JsonElement {
        val removed = subscriptions.remove(subscriptionId)
        removed?.invoke()
        return buildJsonObject { put("unsubscribed", removed != null) }
    }

    private fun requireAvailable(method: String, subscription: Boolean) {
        val descriptor = descriptors().firstOrNull { it.id == method }
            ?: throw UnsupportedOperationException("Unknown capability: $method")
        if (!descriptor.available) throw UnsupportedOperationException(descriptor.reason ?: "$method is unavailable")
        if (subscription && !descriptor.subscription) throw UnsupportedOperationException("$method does not support subscriptions")
    }

    private fun isSensitive(method: String): Boolean = descriptors().firstOrNull { it.id == method }?.sensitive == true

    private suspend fun requireSessionApproval(method: String) {
        if (method in approvedForSession) return
        val allowed = suspendCancellableCoroutine { continuation ->
            val dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.h5_capability_allow_title)
                .setMessage(activity.getString(R.string.h5_capability_allow_message, method))
                .setPositiveButton(R.string.h5_capability_allow) { _, _ -> if (continuation.isActive) continuation.resume(true) }
                .setNegativeButton(R.string.h5_capability_deny) { _, _ -> if (continuation.isActive) continuation.resume(false) }
                .setOnCancelListener { if (continuation.isActive) continuation.resume(false) }
                .show()
            continuation.invokeOnCancellation { dialog.dismiss() }
        }
        if (!allowed) throw SecurityException("The user denied $method")
        approvedForSession += method
    }

    private suspend fun requirePermissions(vararg permissions: String) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return
        check(permissionRequest == null) { "Another permission request is active" }
        val requestCode = nextRequestCode++
        val result = CompletableDeferred<Boolean>()
        permissionRequest = PendingPermission(requestCode, result)
        activity.requestPermissions(missing.toTypedArray(), requestCode)
        if (!result.await()) throw SecurityException("Required Android permission was denied")
    }

    private suspend fun launchRawForResult(intent: Intent): ActivityResult {
        check(activityRequest == null) { "Another native picker is active" }
        val requestCode = nextRequestCode++
        val result = CompletableDeferred<ActivityResult>()
        activityRequest = PendingActivity(requestCode, result)
        activity.startActivityForResult(intent, requestCode)
        return result.await()
    }

    private suspend fun currentLocation(params: JsonObject): JsonElement {
        requirePermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val provider = bestLocationProvider()
        val timeoutMs = params["timeoutMs"]?.jsonPrimitive?.intOrNull?.coerceIn(1_000, 60_000) ?: 10_000
        val location = withTimeout(timeoutMs.toLong()) {
            suspendCancellableCoroutine<Location> { continuation ->
                val cancellation = CancellationSignal()
                locationManager.getCurrentLocation(provider, cancellation, activity.mainExecutor) { value ->
                    if (value != null && continuation.isActive) continuation.resume(value)
                    else if (continuation.isActive) continuation.cancel(IllegalStateException("Location is unavailable"))
                }
                continuation.invokeOnCancellation { cancellation.cancel() }
            }
        }
        return locationJson(location)
    }

    private suspend fun subscribeLocation(id: String, params: JsonObject): () -> Unit {
        requirePermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val minTime = params["intervalMs"]?.jsonPrimitive?.intOrNull?.coerceIn(500, 60_000)?.toLong() ?: 1_000L
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = sendEvent(id, locationJson(location))
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        locationManager.requestLocationUpdates(bestLocationProvider(), minTime, 0f, listener)
        return { locationManager.removeUpdates(listener) }
    }

    private fun bestLocationProvider(): String = when {
        runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) -> LocationManager.GPS_PROVIDER
        runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) -> LocationManager.NETWORK_PROVIDER
        else -> throw IllegalStateException("Location services are disabled")
    }

    private fun locationJson(value: Location): JsonElement = buildJsonObject {
        put("latitude", value.latitude)
        put("longitude", value.longitude)
        put("accuracy", value.accuracy.toDouble())
        put("altitude", value.altitude)
        put("speed", value.speed.toDouble())
        put("bearing", value.bearing.toDouble())
        put("timestamp", value.time)
        put("provider", value.provider.orEmpty())
    }

    private fun subscribeSensor(id: String, method: String, params: JsonObject): () -> Unit {
        val type = when (method) {
            "sensor.orientation" -> Sensor.TYPE_ROTATION_VECTOR
            "sensor.accelerometer" -> Sensor.TYPE_ACCELEROMETER
            "sensor.gyroscope" -> Sensor.TYPE_GYROSCOPE
            "sensor.magneticField" -> Sensor.TYPE_MAGNETIC_FIELD
            else -> throw UnsupportedOperationException("Unknown sensor: $method")
        }
        val sensor = requireNotNull(sensorManager.getDefaultSensor(type)) { "$method is unavailable" }
        val hz = params["frequencyHz"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 50) ?: 10
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                val result = if (type == Sensor.TYPE_ROTATION_VECTOR) rotationJson(method, event.values)
                else vectorJson(event.values, event.timestamp)
                sendEvent(id, result)
            }
        }
        check(sensorManager.registerListener(listener, sensor, 1_000_000 / hz)) { "Unable to register $method" }
        return { sensorManager.unregisterListener(listener) }
    }

    private fun rotationJson(method: String, values: FloatArray): JsonElement {
        val rotation = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotation, values)
        SensorManager.getOrientation(rotation, orientation)
        fun degrees(radians: Float) = Math.toDegrees(radians.toDouble())
        val heading = (degrees(orientation[0]) + 360.0) % 360.0
        return buildJsonObject {
            put("timestamp", System.currentTimeMillis())
            put("alpha", heading)
            put("beta", degrees(orientation[1]))
            put("gamma", degrees(orientation[2]))
        }
    }

    private fun vectorJson(values: FloatArray, timestamp: Long): JsonElement = buildJsonObject {
        put("timestamp", timestamp)
        put("x", values.getOrElse(0) { 0f }.toDouble())
        put("y", values.getOrElse(1) { 0f }.toDouble())
        put("z", values.getOrElse(2) { 0f }.toDouble())
    }

    private fun vibrate(params: JsonObject): JsonElement {
        val duration = params["durationMs"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 5_000) ?: 100
        activity.getSystemService(VibratorManager::class.java).defaultVibrator
            .vibrate(VibrationEffect.createOneShot(duration.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        return buildJsonObject { put("durationMs", duration) }
    }

    private fun battery(): JsonElement {
        val state = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = state?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = state?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return buildJsonObject {
            put("level", if (level >= 0 && scale > 0) level.toDouble() / scale else -1.0)
            put("charging", state?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in setOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL))
            put("temperatureC", (state?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0)
        }
    }

    private fun newMediaFile(prefix: String, extension: String): File {
        val directory = File(activity.filesDir, "agent_workspace/media").apply { mkdirs() }.canonicalFile
        return File(directory, "$prefix-${System.currentTimeMillis()}.$extension")
    }

    private fun locationAvailable(): Boolean =
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)

    private fun sendEvent(subscriptionId: String, value: JsonElement) {
        send(bridgeEvent(subscriptionId, value))
    }

    private fun send(payload: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val encoded = H5BridgeJson.encodeToString(payload)
        webView.evaluateJavascript("window.__kcodeDispatch && window.__kcodeDispatch($encoded);", null)
    }

    private data class PendingPermission(val requestCode: Int, val result: CompletableDeferred<Boolean>)
    private data class ActivityResult(val resultCode: Int, val data: Intent?)
    private data class PendingActivity(val requestCode: Int, val result: CompletableDeferred<ActivityResult>)

    private companion object {
        const val BRIDGE_OBJECT = "__kcodeNativeBridge"
        const val PLATFORM = "android"
        const val PREVIEW_ORIGIN = "https://${H5Workspace.PREVIEW_DOMAIN}"
    }
}
