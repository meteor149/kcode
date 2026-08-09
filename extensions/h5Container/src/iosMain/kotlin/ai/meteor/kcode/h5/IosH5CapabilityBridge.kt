package ai.meteor.kcode.h5

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.AudioToolbox.kSystemSoundID_Vibrate
import platform.CoreLocation.CLHeading
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreMotion.CMAccelerometerData
import platform.CoreMotion.CMDeviceMotion
import platform.CoreMotion.CMGyroData
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.darwin.NSObject
import kotlin.coroutines.resume

private const val IOS_BRIDGE_NAME = "kcodeNative"
private const val IOS_SETTINGS_URL = "app-settings:"
private class H5PermissionDeniedException(message: String) : IllegalStateException(message)

/** Native half of the stable `window.kcode.capabilities` API on iOS. */
@OptIn(ExperimentalForeignApi::class)
internal class IosH5CapabilityBridge(
    private val presentingViewController: UIViewController,
    private val webView: WKWebView,
    private val workspaceRoot: String,
) : NSObject(), WKScriptMessageHandlerProtocol, CLLocationManagerDelegateProtocol,
    UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val locationManager = CLLocationManager()
    private val motionManager = CMMotionManager()
    private val subscriptions = mutableMapOf<String, () -> Unit>()
    private val approvedForSession = mutableSetOf<String>()
    private val approvalMutex = Mutex()
    private var pendingLocationRequest: String? = null
    private var pendingMediaRequest: String? = null
    private var locationSubscription: String? = null
    private var headingSubscription: String? = null
    private var sequence = 0L

    init {
        locationManager.delegate = this
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
    }

    fun install(userContentController: WKUserContentController) {
        val bootstrap = """
            window.kcodeNative = { postMessage: function(message) {
              window.webkit.messageHandlers.$IOS_BRIDGE_NAME.postMessage(message);
            }};
            $KCODE_H5_SDK
        """.trimIndent()
        userContentController.addScriptMessageHandler(this, IOS_BRIDGE_NAME)
        userContentController.addUserScript(
            WKUserScript(
                source = bootstrap,
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                forMainFrameOnly = true,
            ),
        )
    }

    fun close(userContentController: WKUserContentController) {
        subscriptions.values.toList().forEach { runCatching(it) }
        subscriptions.clear()
        locationManager.stopUpdatingLocation()
        locationManager.stopUpdatingHeading()
        motionManager.stopAccelerometerUpdates()
        motionManager.stopGyroUpdates()
        motionManager.stopDeviceMotionUpdates()
        userContentController.removeScriptMessageHandlerForName(IOS_BRIDGE_NAME)
        scope.cancel()
    }

    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val payload = didReceiveScriptMessage.body as? String ?: return
        scope.launch { handle(payload) }
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
                "invoke" -> {
                    val method = requireNotNull(request.method)
                    ensureApproved(method)
                    bridgeSuccess(request.id, invoke(request.id, method, request.params))
                }
                "subscribe" -> {
                    val method = requireNotNull(request.method)
                    ensureApproved(method)
                    bridgeSuccess(request.id, subscribe(method, request.params))
                }
                "unsubscribe" -> bridgeSuccess(request.id, unsubscribe(requireNotNull(request.subscriptionId)))
                else -> error("Unsupported request type: ${request.type}")
            }
        }.getOrElse { error ->
            bridgeFailure(
                request.id,
                if (error is H5PermissionDeniedException) "permission_denied" else "native_error",
                error.message ?: "Capability call failed",
            )
        }
        // Location requests complete asynchronously from CLLocationManagerDelegate.
        if (request.id != pendingLocationRequest && request.id != pendingMediaRequest) send(response)
    }

    private fun capabilityList(): JsonArray = buildJsonArray {
        descriptors().forEach { add(H5BridgeJson.encodeToJsonElement(H5CapabilityDescriptor.serializer(), it)) }
    }

    private fun descriptors(): List<H5CapabilityDescriptor> = listOf(
        descriptor("camera.capture", UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera), sensitive = true),
        descriptor("camera.pick", UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary), sensitive = true),
        descriptor("location.current", CLLocationManager.locationServicesEnabled(), sensitive = true),
        descriptor("location.watch", CLLocationManager.locationServicesEnabled(), subscription = true, sensitive = true),
        descriptor("sensor.compass", CLLocationManager.headingAvailable(), subscription = true, sensitive = true),
        descriptor("sensor.orientation", motionManager.deviceMotionAvailable, subscription = true, sensitive = true),
        descriptor("sensor.accelerometer", motionManager.accelerometerAvailable, subscription = true, sensitive = true),
        descriptor("sensor.gyroscope", motionManager.gyroAvailable, subscription = true, sensitive = true),
        descriptor("sensor.magneticField", motionManager.magnetometerAvailable, subscription = true, sensitive = true,
            reason = if (motionManager.magnetometerAvailable) null else "Magnetometer unavailable"),
        descriptor("sensor.pressure", false, subscription = true, reason = "Barometer bridge is unavailable"),
        descriptor("sensor.light", false, subscription = true, reason = "iOS does not expose the ambient light sensor"),
        descriptor("sensor.proximity", true, subscription = true, sensitive = true),
        descriptor("device.vibrate", true, sensitive = true),
        descriptor("device.flashlight", false, sensitive = true, reason = "Torch bridge is unavailable"),
        descriptor("device.battery", true),
        descriptor("device.network", false, reason = "Network status bridge is unavailable"),
        descriptor("device.openSettings", true, sensitive = true),
        descriptor("media.recordAudio", false, sensitive = true, reason = "Use MediaRecorder in secure web content"),
        descriptor("media.scanQrCode", false, sensitive = true, reason = "QR scanner bridge is unavailable"),
    )

    private fun descriptor(
        id: String,
        available: Boolean,
        subscription: Boolean = false,
        sensitive: Boolean = false,
        reason: String? = if (available) null else "Capability unavailable on this iOS device",
    ) = H5CapabilityDescriptor(id, available, subscription, sensitive, "ios", reason)

    private fun invoke(requestId: String, method: String, params: kotlinx.serialization.json.JsonObject): JsonElement = when (method) {
        "camera.capture" -> openImagePicker(requestId, UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera)
        "camera.pick" -> openImagePicker(requestId, UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary)
        "location.current" -> {
            checkAvailable(method)
            locationManager.requestWhenInUseAuthorization()
            check(pendingLocationRequest == null) { "A location request is already in progress" }
            pendingLocationRequest = requestId
            locationManager.requestLocation()
            buildJsonObject { put("pending", true) }
        }
        "device.vibrate" -> {
            val duration = params["durationMs"]?.jsonPrimitive?.doubleOrNull ?: 200.0
            if (duration > 0) AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
            buildJsonObject { put("vibrated", duration > 0) }
        }
        "device.battery" -> {
            val device = UIDevice.currentDevice
            device.batteryMonitoringEnabled = true
            buildJsonObject {
                put("level", device.batteryLevel.toDouble().coerceAtLeast(0.0))
                put("state", device.batteryState.toString())
            }
        }
        "device.openSettings" -> {
            val url = platform.Foundation.NSURL.URLWithString(IOS_SETTINGS_URL) ?: error("Settings URL unavailable")
            UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
            buildJsonObject { put("opened", true) }
        }
        else -> throw UnsupportedOperationException("Capability $method is unavailable on iOS")
    }

    private fun openImagePicker(requestId: String, sourceType: platform.UIKit.UIImagePickerControllerSourceType): JsonElement {
        check(pendingMediaRequest == null) { "A camera or photo picker request is already in progress" }
        check(UIImagePickerController.isSourceTypeAvailable(sourceType)) { "Requested image source is unavailable" }
        pendingMediaRequest = requestId
        val picker = UIImagePickerController().apply {
            this.sourceType = sourceType
            delegate = this@IosH5CapabilityBridge
        }
        presentingViewController.presentViewController(picker, animated = true, completion = null)
        return buildJsonObject { put("pending", true) }
    }

    override fun imagePickerController(
        picker: UIImagePickerController,
        didFinishPickingMediaWithInfo: Map<Any?, *>,
    ) {
        val requestId = pendingMediaRequest ?: return
        pendingMediaRequest = null
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        val response = runCatching {
            requireNotNull(image) { "The picker did not return an image" }
            val directory = workspaceRoot.trimEnd('/') + "/media"
            NSFileManager.defaultManager.createDirectoryAtPath(
                directory,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
            val name = "image-${NSUUID.UUID().UUIDString}.jpg"
            val path = "$directory/$name"
            val data = requireNotNull(UIImageJPEGRepresentation(image, 0.9)) { "Could not encode image" }
            check(NSFileManager.defaultManager.createFileAtPath(path, contents = data, attributes = null)) {
                "Could not save image"
            }
            bridgeSuccess(requestId, buildJsonObject {
                put("path", "/workspace/media/$name")
                put("mimeType", "image/jpeg")
                put("size", data.length.toLong())
            })
        }.getOrElse { bridgeFailure(requestId, "media_error", it.message ?: "Could not save image") }
        picker.dismissViewControllerAnimated(true) { send(response) }
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        val requestId = pendingMediaRequest
        pendingMediaRequest = null
        picker.dismissViewControllerAnimated(true) {
            if (requestId != null) send(bridgeFailure(requestId, "cancelled", "Image selection was cancelled"))
        }
    }

    private fun subscribe(method: String, params: kotlinx.serialization.json.JsonObject): JsonElement {
        checkAvailable(method)
        val id = "subscription-${++sequence}"
        when (method) {
            "location.watch" -> {
                locationManager.requestWhenInUseAuthorization()
                locationSubscription = id
                locationManager.startUpdatingLocation()
                subscriptions[id] = { locationSubscription = null; locationManager.stopUpdatingLocation() }
            }
            "sensor.compass" -> {
                headingSubscription = id
                locationManager.startUpdatingHeading()
                subscriptions[id] = { headingSubscription = null; locationManager.stopUpdatingHeading() }
            }
            "sensor.accelerometer" -> startAccelerometer(id, params)
            "sensor.gyroscope" -> startGyroscope(id, params)
            "sensor.orientation" -> startDeviceMotion(id, params)
            "sensor.proximity" -> startProximity(id)
            else -> throw UnsupportedOperationException("Subscription $method is unavailable on iOS")
        }
        return buildJsonObject { put("subscriptionId", id) }
    }

    private fun unsubscribe(id: String): JsonElement {
        subscriptions.remove(id)?.invoke()
        return buildJsonObject { put("unsubscribed", true) }
    }

    private fun startAccelerometer(id: String, params: kotlinx.serialization.json.JsonObject) {
        motionManager.accelerometerUpdateInterval = interval(params)
        motionManager.startAccelerometerUpdatesToQueue(NSOperationQueue.mainQueue) { data: CMAccelerometerData?, error: NSError? ->
            if (data != null && error == null) data.acceleration.useContents { event(id, vector(x, y, z)) }
        }
        subscriptions[id] = { motionManager.stopAccelerometerUpdates() }
    }

    private fun startGyroscope(id: String, params: kotlinx.serialization.json.JsonObject) {
        motionManager.gyroUpdateInterval = interval(params)
        motionManager.startGyroUpdatesToQueue(NSOperationQueue.mainQueue) { data: CMGyroData?, error: NSError? ->
            if (data != null && error == null) data.rotationRate.useContents { event(id, vector(x, y, z)) }
        }
        subscriptions[id] = { motionManager.stopGyroUpdates() }
    }

    private fun startDeviceMotion(id: String, params: kotlinx.serialization.json.JsonObject) {
        motionManager.deviceMotionUpdateInterval = interval(params)
        motionManager.startDeviceMotionUpdatesToQueue(NSOperationQueue.mainQueue) { data: CMDeviceMotion?, error: NSError? ->
            if (data != null && error == null) event(id, buildJsonObject {
                put("roll", data.attitude.roll); put("pitch", data.attitude.pitch); put("yaw", data.attitude.yaw)
            })
        }
        subscriptions[id] = { motionManager.stopDeviceMotionUpdates() }
    }

    private fun startProximity(id: String) {
        UIDevice.currentDevice.proximityMonitoringEnabled = true
        // iOS exposes proximity as state rather than raw distance; emit the initial state.
        event(id, buildJsonObject { put("near", UIDevice.currentDevice.proximityState) })
        subscriptions[id] = { UIDevice.currentDevice.proximityMonitoringEnabled = false }
    }

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return
        val result = locationJson(location)
        pendingLocationRequest?.let { requestId ->
            pendingLocationRequest = null
            send(bridgeSuccess(requestId, result))
        }
        locationSubscription?.let { event(it, result) }
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        pendingLocationRequest?.let { requestId ->
            pendingLocationRequest = null
            send(bridgeFailure(requestId, "location_error", didFailWithError.localizedDescription))
        }
    }

    override fun locationManager(manager: CLLocationManager, didUpdateHeading: CLHeading) {
        headingSubscription?.let { id ->
            event(id, buildJsonObject {
                put("magneticHeading", didUpdateHeading.magneticHeading)
                put("trueHeading", didUpdateHeading.trueHeading)
                put("accuracy", didUpdateHeading.headingAccuracy)
            })
        }
    }

    private fun locationJson(location: CLLocation) = buildJsonObject {
        put("latitude", location.coordinate.useContents { latitude })
        put("longitude", location.coordinate.useContents { longitude })
        put("altitude", location.altitude)
        put("accuracy", location.horizontalAccuracy)
        put("speed", location.speed)
    }

    private fun vector(x: Double, y: Double, z: Double) = buildJsonObject {
        put("x", x); put("y", y); put("z", z)
    }

    private fun interval(params: kotlinx.serialization.json.JsonObject): Double =
        ((params["intervalMs"]?.jsonPrimitive?.doubleOrNull ?: 100.0).coerceIn(16.0, 10_000.0)) / 1000.0

    private fun checkAvailable(method: String) {
        val descriptor = descriptors().firstOrNull { it.id == method }
        requireNotNull(descriptor) { "Unknown capability: $method" }
        if (!descriptor.available) throw UnsupportedOperationException(descriptor.reason ?: "Capability unavailable")
    }

    private suspend fun ensureApproved(method: String) {
        val descriptor = descriptors().firstOrNull { it.id == method } ?: error("Unknown capability: $method")
        if (!descriptor.sensitive || method in approvedForSession) return
        approvalMutex.withLock {
            if (method in approvedForSession) return@withLock
            val approved = suspendCancellableCoroutine { continuation ->
                val alert = UIAlertController.alertControllerWithTitle(
                    title = "Allow H5 capability?",
                    message = "This local H5 app wants to use $method. Access lasts only for this preview session.",
                    preferredStyle = UIAlertControllerStyleAlert,
                )
                alert.addAction(UIAlertAction.actionWithTitle("Deny", UIAlertActionStyleCancel) {
                    if (continuation.isActive) continuation.resume(false)
                })
                alert.addAction(UIAlertAction.actionWithTitle("Allow", UIAlertActionStyleDefault) {
                    if (continuation.isActive) continuation.resume(true)
                })
                presentingViewController.presentViewController(alert, animated = true, completion = null)
                continuation.invokeOnCancellation { alert.dismissViewControllerAnimated(true, completion = null) }
            }
            if (!approved) throw H5PermissionDeniedException("H5 capability permission denied: $method")
            approvedForSession += method
        }
    }

    private fun event(id: String, result: JsonElement) = send(bridgeEvent(id, result))

    private fun send(message: String) {
        val encoded = H5BridgeJson.encodeToString(JsonPrimitive(message))
        webView.evaluateJavaScript("window.__kcodeDispatch(JSON.parse($encoded))", completionHandler = null)
    }

}
