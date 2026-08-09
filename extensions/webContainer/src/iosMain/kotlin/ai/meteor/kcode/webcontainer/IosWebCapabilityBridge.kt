package ai.meteor.kcode.webcontainer

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
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreMotion.CMAccelerometerData
import platform.CoreMotion.CMAttitudeReferenceFrameXMagneticNorthZVertical
import platform.CoreMotion.CMDeviceMotion
import platform.CoreMotion.CMGyroData
import platform.CoreMotion.CMMagnetometerData
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSError
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIDevice
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIViewController
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKMediaCaptureType
import platform.WebKit.WKPermissionDecision
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKSecurityOrigin
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.math.PI

private const val IOS_BRIDGE_NAME = "kcodeNative"
private class WebPermissionDeniedException(message: String) : IllegalStateException(message)

/** Internal native fallback used by the injected standard Web API compatibility layer on iOS. */
@OptIn(ExperimentalForeignApi::class)
internal class IosWebCapabilityBridge(
    private val presentingViewController: UIViewController,
    private val webView: WKWebView,
) : NSObject(), WKScriptMessageHandlerProtocol, CLLocationManagerDelegateProtocol,
    WKUIDelegateProtocol {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val locationManager = CLLocationManager()
    private val motionManager = CMMotionManager()
    private val subscriptions = mutableMapOf<String, () -> Unit>()
    private val approvedForSession = mutableSetOf<String>()
    private val approvalMutex = Mutex()
    private var pendingLocationRequest: String? = null
    private var locationSubscription: String? = null
    private var sequence = 0L

    init {
        locationManager.delegate = this
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
    }

    fun install(userContentController: WKUserContentController) {
        val bootstrap = """
            Object.defineProperty(window, '__kcodeNativeBridge', { value: { postMessage: function(message) {
              window.webkit.messageHandlers.$IOS_BRIDGE_NAME.postMessage(message);
            }}});
            $KCODE_WEB_CONTAINER_SDK
        """.trimIndent()
        userContentController.addScriptMessageHandler(this, IOS_BRIDGE_NAME)
        webView.UIDelegate = this
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
        motionManager.stopAccelerometerUpdates()
        motionManager.stopGyroUpdates()
        motionManager.stopMagnetometerUpdates()
        motionManager.stopDeviceMotionUpdates()
        userContentController.removeScriptMessageHandlerForName(IOS_BRIDGE_NAME)
        webView.UIDelegate = null
        scope.cancel()
    }

    override fun webView(
        webView: WKWebView,
        requestMediaCapturePermissionForOrigin: WKSecurityOrigin,
        initiatedByFrame: WKFrameInfo,
        type: WKMediaCaptureType,
        decisionHandler: (WKPermissionDecision) -> Unit,
    ) {
        decisionHandler(WKPermissionDecision.WKPermissionDecisionPrompt)
    }

    override fun webView(
        webView: WKWebView,
        requestDeviceOrientationAndMotionPermissionForOrigin: WKSecurityOrigin,
        initiatedByFrame: WKFrameInfo,
        decisionHandler: (WKPermissionDecision) -> Unit,
    ) {
        decisionHandler(WKPermissionDecision.WKPermissionDecisionPrompt)
    }

    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val payload = didReceiveScriptMessage.body as? String ?: return
        scope.launch { handle(payload) }
    }

    private suspend fun handle(payload: String) {
        val request = runCatching { WebBridgeJson.decodeFromString<WebBridgeRequest>(payload) }
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
                if (error is WebPermissionDeniedException) "permission_denied" else "native_error",
                error.message ?: "Capability call failed",
            )
        }
        // Location requests complete asynchronously from CLLocationManagerDelegate.
        if (request.id != pendingLocationRequest) send(response)
    }

    private fun capabilityList(): JsonArray = buildJsonArray {
        descriptors().forEach { add(WebBridgeJson.encodeToJsonElement(WebCapabilityDescriptor.serializer(), it)) }
    }

    private fun descriptors(): List<WebCapabilityDescriptor> = listOf(
        descriptor("location.current", CLLocationManager.locationServicesEnabled(), sensitive = true),
        descriptor("location.watch", CLLocationManager.locationServicesEnabled(), subscription = true, sensitive = true),
        descriptor("sensor.orientation", motionManager.deviceMotionAvailable, subscription = true, sensitive = true),
        descriptor("sensor.accelerometer", motionManager.accelerometerAvailable, subscription = true, sensitive = true),
        descriptor("sensor.gyroscope", motionManager.gyroAvailable, subscription = true, sensitive = true),
        descriptor("sensor.magneticField", motionManager.magnetometerAvailable, subscription = true, sensitive = true,
            reason = if (motionManager.magnetometerAvailable) null else "Magnetometer unavailable"),
        descriptor("device.vibrate", true, sensitive = true),
        descriptor("device.battery", true),
    )

    private fun descriptor(
        id: String,
        available: Boolean,
        subscription: Boolean = false,
        sensitive: Boolean = false,
        reason: String? = if (available) null else "Capability unavailable on this iOS device",
    ) = WebCapabilityDescriptor(id, available, subscription, sensitive, "ios", reason)

    private fun invoke(requestId: String, method: String, params: kotlinx.serialization.json.JsonObject): JsonElement = when (method) {
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
            val state = device.batteryState.toString()
            buildJsonObject {
                put("level", device.batteryLevel.toDouble().coerceAtLeast(0.0))
                put("charging", state.contains("charging", ignoreCase = true) || state.contains("full", ignoreCase = true))
            }
        }
        else -> throw UnsupportedOperationException("Capability $method is unavailable on iOS")
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
            "sensor.accelerometer" -> startAccelerometer(id, params)
            "sensor.gyroscope" -> startGyroscope(id, params)
            "sensor.magneticField" -> startMagnetometer(id, params)
            "sensor.orientation" -> startDeviceMotion(id, params)
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

    private fun startMagnetometer(id: String, params: kotlinx.serialization.json.JsonObject) {
        motionManager.magnetometerUpdateInterval = interval(params)
        motionManager.startMagnetometerUpdatesToQueue(
            NSOperationQueue.mainQueue,
        ) { data: CMMagnetometerData?, error: NSError? ->
            if (data != null && error == null) {
                data.magneticField.useContents { event(id, vector(x, y, z)) }
            }
        }
        subscriptions[id] = { motionManager.stopMagnetometerUpdates() }
    }

    private fun startDeviceMotion(id: String, params: kotlinx.serialization.json.JsonObject) {
        motionManager.deviceMotionUpdateInterval = interval(params)
        motionManager.startDeviceMotionUpdatesUsingReferenceFrame(
            CMAttitudeReferenceFrameXMagneticNorthZVertical,
            NSOperationQueue.mainQueue,
        ) { data: CMDeviceMotion?, error: NSError? ->
            if (data != null && error == null) event(id, buildJsonObject {
                fun degrees(radians: Double) = radians * 180.0 / PI
                put("alpha", (degrees(data.attitude.yaw) + 360.0) % 360.0)
                put("beta", degrees(data.attitude.pitch))
                put("gamma", degrees(data.attitude.roll))
            })
        }
        subscriptions[id] = { motionManager.stopDeviceMotionUpdates() }
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

    private fun interval(params: kotlinx.serialization.json.JsonObject): Double {
        val frequency = params["frequencyHz"]?.jsonPrimitive?.doubleOrNull
        if (frequency != null) return 1.0 / frequency.coerceIn(1.0, 60.0)
        return ((params["intervalMs"]?.jsonPrimitive?.doubleOrNull ?: 100.0).coerceIn(16.0, 10_000.0)) / 1000.0
    }

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
                    title = "Allow Web capability?",
                    message = "This local Web app wants to use $method. Access lasts only for this preview session.",
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
            if (!approved) throw WebPermissionDeniedException("Web capability permission denied: $method")
            approvedForSession += method
        }
    }

    private fun event(id: String, result: JsonElement) = send(bridgeEvent(id, result))

    private fun send(message: String) {
        val encoded = WebBridgeJson.encodeToString(JsonPrimitive(message))
        webView.evaluateJavaScript("window.__kcodeDispatch(JSON.parse($encoded))", completionHandler = null)
    }

}
