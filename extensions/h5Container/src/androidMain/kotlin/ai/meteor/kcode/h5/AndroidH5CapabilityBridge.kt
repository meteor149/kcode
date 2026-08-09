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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.CancellationSignal
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.io.IOException
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
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
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var torchCameraId: String? = null

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
        pending.result.complete(if (resultCode == Activity.RESULT_OK) data ?: Intent() else null)
        return true
    }

    fun close() {
        subscriptions.values.toList().forEach { runCatching(it) }
        subscriptions.clear()
        stopRecordingSilently()
        runCatching { setTorch(false) }
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
        descriptor("camera.capture", cameraAvailable(), sensitive = true),
        descriptor("camera.pick", true, sensitive = true),
        descriptor("location.current", locationAvailable(), sensitive = true),
        descriptor("location.watch", locationAvailable(), subscription = true, sensitive = true),
        sensorDescriptor("sensor.compass", Sensor.TYPE_ROTATION_VECTOR),
        sensorDescriptor("sensor.orientation", Sensor.TYPE_ROTATION_VECTOR),
        sensorDescriptor("sensor.accelerometer", Sensor.TYPE_ACCELEROMETER),
        sensorDescriptor("sensor.gyroscope", Sensor.TYPE_GYROSCOPE),
        sensorDescriptor("sensor.magneticField", Sensor.TYPE_MAGNETIC_FIELD),
        sensorDescriptor("sensor.pressure", Sensor.TYPE_PRESSURE),
        sensorDescriptor("sensor.light", Sensor.TYPE_LIGHT),
        sensorDescriptor("sensor.proximity", Sensor.TYPE_PROXIMITY),
        descriptor("device.vibrate", activity.getSystemService(VibratorManager::class.java).defaultVibrator.hasVibrator(), sensitive = true),
        descriptor("device.flashlight", flashlightAvailable(), sensitive = true),
        descriptor("device.battery", true),
        descriptor("device.network", true),
        descriptor("device.openSettings", true, sensitive = true),
        descriptor("media.recordAudio", activity.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE), sensitive = true),
        H5CapabilityDescriptor("media.scanQrCode", false, sensitive = true, platform = PLATFORM, reason = "QR decoding is not bundled"),
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
            "camera.capture" -> capturePhoto(params)
            "camera.pick" -> pickPhoto()
            "location.current" -> currentLocation(params)
            "device.vibrate" -> vibrate(params)
            "device.flashlight" -> flashlight(params)
            "device.battery" -> battery()
            "device.network" -> network()
            "device.openSettings" -> openSettings(params)
            "media.recordAudio" -> recordAudio(params)
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

    private suspend fun launchForResult(intent: Intent): Intent? {
        check(activityRequest == null) { "Another native picker is active" }
        val requestCode = nextRequestCode++
        val result = CompletableDeferred<Intent?>()
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
            "sensor.compass", "sensor.orientation" -> Sensor.TYPE_ROTATION_VECTOR
            "sensor.accelerometer" -> Sensor.TYPE_ACCELEROMETER
            "sensor.gyroscope" -> Sensor.TYPE_GYROSCOPE
            "sensor.magneticField" -> Sensor.TYPE_MAGNETIC_FIELD
            "sensor.pressure" -> Sensor.TYPE_PRESSURE
            "sensor.light" -> Sensor.TYPE_LIGHT
            "sensor.proximity" -> Sensor.TYPE_PROXIMITY
            else -> throw UnsupportedOperationException("Unknown sensor: $method")
        }
        val sensor = requireNotNull(sensorManager.getDefaultSensor(type)) { "$method is unavailable" }
        val hz = params["frequencyHz"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 50) ?: 10
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                val result = if (type == Sensor.TYPE_ROTATION_VECTOR) rotationJson(method, event.values)
                else vectorJson(method, event.values, event.timestamp)
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
            if (method == "sensor.compass") put("heading", heading)
            else {
                put("alpha", heading)
                put("beta", degrees(orientation[1]))
                put("gamma", degrees(orientation[2]))
            }
        }
    }

    private fun vectorJson(method: String, values: FloatArray, timestamp: Long): JsonElement = buildJsonObject {
        put("timestamp", timestamp)
        when (method) {
            "sensor.pressure" -> put("pressure", values.firstOrNull()?.toDouble() ?: 0.0)
            "sensor.light" -> put("lux", values.firstOrNull()?.toDouble() ?: 0.0)
            "sensor.proximity" -> put("distance", values.firstOrNull()?.toDouble() ?: 0.0)
            else -> {
                put("x", values.getOrElse(0) { 0f }.toDouble())
                put("y", values.getOrElse(1) { 0f }.toDouble())
                put("z", values.getOrElse(2) { 0f }.toDouble())
            }
        }
    }

    private suspend fun capturePhoto(params: JsonObject): JsonElement {
        requirePermissions(Manifest.permission.CAMERA)
        val file = newMediaFile("photo", "jpg")
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.h5.fileprovider", file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        require(intent.resolveActivity(activity.packageManager) != null) { "No camera application is available" }
        if (launchForResult(intent) == null || !file.isFile || file.length() == 0L) {
            file.delete()
            throw IllegalStateException("Photo capture was cancelled")
        }
        return mediaFileJson(file, "image/jpeg")
    }

    private suspend fun pickPhoto(): JsonElement {
        val data = launchForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }) ?: throw IllegalStateException("Image selection was cancelled")
        val uri = requireNotNull(data.data) { "The selected image has no URI" }
        val type = activity.contentResolver.getType(uri) ?: "image/*"
        val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "bin"
        val file = newMediaFile("picked", extension)
        activity.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyToLimited(output, MAX_MEDIA_BYTES) }
        } ?: throw IOException("Unable to read selected image")
        return mediaFileJson(file, type)
    }

    private fun vibrate(params: JsonObject): JsonElement {
        val duration = params["durationMs"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 5_000) ?: 100
        activity.getSystemService(VibratorManager::class.java).defaultVibrator
            .vibrate(VibrationEffect.createOneShot(duration.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        return buildJsonObject { put("durationMs", duration) }
    }

    private fun flashlight(params: JsonObject): JsonElement {
        val enabled = params["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        setTorch(enabled)
        return buildJsonObject { put("enabled", enabled) }
    }

    private fun setTorch(enabled: Boolean) {
        val manager = activity.getSystemService(CameraManager::class.java)
        val camera = torchCameraId ?: manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }?.also { torchCameraId = it } ?: throw UnsupportedOperationException("No flashlight is available")
        manager.setTorchMode(camera, enabled)
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

    private fun network(): JsonElement {
        val manager = activity.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        return buildJsonObject {
            put("connected", capabilities != null)
            put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            put("metered", manager.isActiveNetworkMetered)
            put("transport", when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "vpn"
                else -> "unknown"
            })
        }
    }

    private fun openSettings(params: JsonObject): JsonElement {
        val target = params["target"]?.jsonPrimitive?.content ?: "app"
        val intent = when (target) {
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
        }
        activity.startActivity(intent)
        return buildJsonObject { put("opened", target) }
    }

    private suspend fun recordAudio(params: JsonObject): JsonElement {
        return when (params["action"]?.jsonPrimitive?.content ?: "start") {
            "start" -> {
                requirePermissions(Manifest.permission.RECORD_AUDIO)
                check(recorder == null) { "Audio recording is already active" }
                val file = newMediaFile("recording", "m4a")
                val mediaRecorder = MediaRecorder(activity).apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128_000)
                    setAudioSamplingRate(44_100)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
                recorder = mediaRecorder
                recordingFile = file
                buildJsonObject { put("recording", true) }
            }
            "stop" -> {
                val file = requireNotNull(recordingFile) { "No audio recording is active" }
                val active = requireNotNull(recorder)
                recorder = null
                recordingFile = null
                try { active.stop() } finally { active.release() }
                mediaFileJson(file, "audio/mp4")
            }
            else -> error("media.recordAudio action must be start or stop")
        }
    }

    private fun stopRecordingSilently() {
        val active = recorder ?: return
        recorder = null
        runCatching { active.stop() }
        active.release()
        recordingFile = null
    }

    private fun newMediaFile(prefix: String, extension: String): File {
        val directory = File(activity.filesDir, "agent_workspace/media").apply { mkdirs() }.canonicalFile
        return File(directory, "$prefix-${System.currentTimeMillis()}.$extension")
    }

    private fun mediaFileJson(file: File, mimeType: String): JsonElement {
        require(file.length() <= MAX_MEDIA_BYTES) { "Media file exceeds the ${MAX_MEDIA_BYTES / 1024 / 1024} MB limit" }
        val relative = H5Workspace.relativePath(activity, file)
        return buildJsonObject {
            put("path", "/workspace/$relative")
            put("url", H5Workspace.previewUrl(relative))
            put("mimeType", mimeType)
            put("size", file.length())
        }
    }

    private fun java.io.InputStream.copyToLimited(output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("Selected media exceeds the size limit")
            output.write(buffer, 0, count)
        }
    }

    private fun cameraAvailable(): Boolean =
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    private fun locationAvailable(): Boolean =
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)

    private fun flashlightAvailable(): Boolean = runCatching {
        val manager = activity.getSystemService(CameraManager::class.java)
        manager.cameraIdList.any {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrDefault(false)

    private fun sendEvent(subscriptionId: String, value: JsonElement) {
        send(bridgeEvent(subscriptionId, value))
    }

    private fun send(payload: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val encoded = H5BridgeJson.encodeToString(payload)
        webView.evaluateJavascript("window.__kcodeDispatch && window.__kcodeDispatch($encoded);", null)
    }

    private data class PendingPermission(val requestCode: Int, val result: CompletableDeferred<Boolean>)
    private data class PendingActivity(val requestCode: Int, val result: CompletableDeferred<Intent?>)

    private companion object {
        const val BRIDGE_OBJECT = "kcodeNative"
        const val PLATFORM = "android"
        const val PREVIEW_ORIGIN = "https://${H5Workspace.PREVIEW_DOMAIN}"
        const val MAX_MEDIA_BYTES = 32L * 1024L * 1024L
    }
}
