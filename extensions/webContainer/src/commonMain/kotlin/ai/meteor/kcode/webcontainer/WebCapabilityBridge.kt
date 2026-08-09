package ai.meteor.kcode.webcontainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
internal data class WebCapabilityDescriptor(
    val id: String,
    val available: Boolean,
    val subscription: Boolean = false,
    val sensitive: Boolean = false,
    val platform: String,
    val reason: String? = null,
)

@Serializable
internal data class WebBridgeRequest(
    val id: String,
    val type: String,
    val method: String? = null,
    val params: JsonObject = JsonObject(emptyMap()),
    val subscriptionId: String? = null,
)

internal val WebBridgeJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

internal fun bridgeSuccess(id: String, result: kotlinx.serialization.json.JsonElement): String =
    buildJsonObject {
        put("type", "response")
        put("id", id)
        put("result", result)
    }.toString()

internal fun bridgeFailure(id: String, code: String, message: String): String =
    buildJsonObject {
        put("type", "response")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }.toString()

internal fun bridgeEvent(subscriptionId: String, result: kotlinx.serialization.json.JsonElement): String =
    buildJsonObject {
        put("type", "event")
        put("subscriptionId", subscriptionId)
        put("result", result)
    }.toString()

/** Injected at document start to fill standard Web API gaps with native implementations. */
internal val KCODE_WEB_CONTAINER_SDK: String = """
    (function () {
      if (window.__kcodeStandardFallbackInstalled) return;
      Object.defineProperty(window, '__kcodeStandardFallbackInstalled', { value: true });
      var pending = new Map();
      var nativeSubscriptions = new Map();
      var sequence = 0;
      function nativeAvailable() {
        return !!(window.__kcodeNativeBridge && typeof window.__kcodeNativeBridge.postMessage === 'function');
      }
      if (!nativeAvailable()) return;
      function nextId(prefix) { sequence += 1; return prefix + '-' + Date.now() + '-' + sequence; }
      function nativeRequest(type, method, params, subscriptionId) {
        var id = nextId('request');
        return new Promise(function (resolve, reject) {
          pending.set(id, { resolve: resolve, reject: reject });
          try {
            window.__kcodeNativeBridge.postMessage(JSON.stringify({ id: id, type: type, method: method || null,
              params: params || {}, subscriptionId: subscriptionId || null }));
          } catch (error) { pending.delete(id); reject(error); }
        });
      }
      window.__kcodeDispatch = function (payload) {
        var message = typeof payload === 'string' ? JSON.parse(payload) : payload;
        if (message.type === 'event') {
          var callback = nativeSubscriptions.get(message.subscriptionId);
          if (callback) callback(message.result);
          return;
        }
        var waiter = pending.get(message.id);
        if (!waiter) return;
        pending.delete(message.id);
        if (message.error) {
          var error = new Error(message.error.message || 'Native Web API fallback failed');
          error.code = message.error.code || 'native_error';
          waiter.reject(error);
        } else waiter.resolve(message.result);
      };
      function nativeSubscribe(method, params, callback) {
        return nativeRequest('subscribe', method, params || {}).then(function (result) {
          nativeSubscriptions.set(result.subscriptionId, callback);
          return result.subscriptionId;
        });
      }
      function nativeUnsubscribe(id) {
        nativeSubscriptions.delete(id);
        return nativeRequest('unsubscribe', null, {}, id);
      }
      function positionFromNative(value) {
        var coordinates = {
          latitude: value.latitude, longitude: value.longitude, accuracy: value.accuracy,
          altitude: value.altitude == null ? null : value.altitude, altitudeAccuracy: null,
          heading: value.heading == null ? null : value.heading, speed: value.speed == null ? null : value.speed
        };
        return Object.freeze({ coords: Object.freeze(coordinates), timestamp: value.timestamp || Date.now() });
      }
      function geolocationOptions(options) {
        options = options || {};
        return { highAccuracy: options.enableHighAccuracy === true, timeoutMs: options.timeout,
          maximumAgeMs: options.maximumAge };
      }
      function installGeolocationFallback() {
        var original = navigator.geolocation;
        var originalCurrent = original && original.getCurrentPosition.bind(original);
        var originalWatch = original && original.watchPosition.bind(original);
        var originalClear = original && original.clearWatch.bind(original);
        var watches = new Map();
        var watchSequence = 0;
        function current(success, failure, options) {
          function useNative() {
            nativeRequest('invoke', 'location.current', geolocationOptions(options))
              .then(function (value) { success(positionFromNative(value)); }, failure || function () {});
          }
          if (!originalCurrent) { useNative(); return; }
          originalCurrent(success, function (error) {
            if (error && error.code === 1) { if (failure) failure(error); return; }
            useNative();
          }, options);
        }
        function watch(success, failure, options) {
          var id = ++watchSequence;
          var state = { standardId: null, nativeId: null, cancelled: false };
          watches.set(id, state);
          function useNative() {
            nativeSubscribe('location.watch', geolocationOptions(options), function (value) {
              success(positionFromNative(value));
            }).then(function (nativeId) {
              state.nativeId = nativeId;
              if (state.cancelled) nativeUnsubscribe(nativeId);
            }, failure || function () {});
          }
          if (originalWatch) {
            state.standardId = originalWatch(success, function (error) {
              if (error && error.code === 1) { if (failure) failure(error); return; }
              if (state.standardId != null && originalClear) originalClear(state.standardId);
              state.standardId = null;
              useNative();
            }, options);
          } else useNative();
          return id;
        }
        function clear(id) {
          var state = watches.get(id);
          if (!state) return;
          state.cancelled = true;
          watches.delete(id);
          if (state.standardId != null && originalClear) originalClear(state.standardId);
          if (state.nativeId) nativeUnsubscribe(state.nativeId);
        }
        var fallback = { getCurrentPosition: current, watchPosition: watch, clearWatch: clear };
        if (original) {
          try {
            Object.defineProperties(original, {
              getCurrentPosition: { value: current, configurable: true },
              watchPosition: { value: watch, configurable: true },
              clearWatch: { value: clear, configurable: true }
            });
            return;
          } catch (_) {}
        }
        try { Object.defineProperty(navigator, 'geolocation', { value: fallback, configurable: true }); } catch (_) {}
      }
      function installVibrationFallback() {
        if (typeof navigator.vibrate === 'function') return;
        try {
          Object.defineProperty(navigator, 'vibrate', { configurable: true, value: function (pattern) {
            var duration = Array.isArray(pattern) ? pattern[0] : pattern;
            nativeRequest('invoke', 'device.vibrate', { durationMs: Number(duration) || 0 }).catch(function () {});
            return true;
          }});
        } catch (_) {}
      }
      class NativeBatteryManager extends EventTarget {
        constructor(value) {
          super();
          this.charging = !!value.charging;
          this.chargingTime = value.chargingTime == null ? Infinity : value.chargingTime;
          this.dischargingTime = value.dischargingTime == null ? Infinity : value.dischargingTime;
          this.level = value.level;
          this.onchargingchange = null;
          this.onchargingtimechange = null;
          this.ondischargingtimechange = null;
          this.onlevelchange = null;
        }
      }
      function installBatteryFallback() {
        var original = typeof navigator.getBattery === 'function' ? navigator.getBattery.bind(navigator) : null;
        function fallback() {
          return nativeRequest('invoke', 'device.battery', {}).then(function (value) { return new NativeBatteryManager(value); });
        }
        var getBattery = original ? function () {
          return original().catch(function (error) {
            if (error && error.name === 'NotAllowedError') throw error;
            return fallback();
          });
        } : fallback;
        try { Object.defineProperty(navigator, 'getBattery', { value: getBattery, configurable: true }); } catch (_) {}
      }
      function sensorError(target, error) {
        var event = new Event('error');
        Object.defineProperty(event, 'error', { value: error });
        target.dispatchEvent(event);
        if (typeof target.onerror === 'function') target.onerror(event);
      }
      class NativeSensor extends EventTarget {
        constructor(method, options) {
          super();
          this._method = method;
          this._frequency = options && options.frequency;
          this._subscriptionId = null;
          this._starting = false;
          this._stopped = true;
          this.activated = false;
          this.hasReading = false;
          this.timestamp = null;
          this.onactivate = null;
          this.onerror = null;
          this.onreading = null;
        }
        start() {
          if (this.activated || this._starting) return;
          this._starting = true;
          this._stopped = false;
          var self = this;
          nativeSubscribe(this._method, { frequencyHz: this._frequency || 10 }, function (value) {
            self._update(value);
            self.hasReading = true;
            self.timestamp = value.timestamp || performance.now();
            var event = new Event('reading');
            self.dispatchEvent(event);
            if (typeof self.onreading === 'function') self.onreading(event);
          }).then(function (id) {
            self._starting = false;
            if (self._stopped) { nativeUnsubscribe(id); return; }
            self._subscriptionId = id;
            self.activated = true;
            var event = new Event('activate');
            self.dispatchEvent(event);
            if (typeof self.onactivate === 'function') self.onactivate(event);
          }, function (error) { self._starting = false; sensorError(self, error); });
        }
        stop() {
          this._starting = false;
          this._stopped = true;
          this.activated = false;
          this.hasReading = false;
          if (this._subscriptionId) nativeUnsubscribe(this._subscriptionId);
          this._subscriptionId = null;
        }
      }
      class NativeVectorSensor extends NativeSensor {
        constructor(method, options) { super(method, options); this.x = null; this.y = null; this.z = null; }
        _update(value) { this.x = value.x; this.y = value.y; this.z = value.z; }
      }
      function orientationQuaternion(value) {
        var alpha = (value.alpha || 0) * Math.PI / 360;
        var beta = (value.beta || 0) * Math.PI / 360;
        var gamma = (value.gamma || 0) * Math.PI / 360;
        var cX = Math.cos(beta), cY = Math.cos(gamma), cZ = Math.cos(alpha);
        var sX = Math.sin(beta), sY = Math.sin(gamma), sZ = Math.sin(alpha);
        return [sX * cY * cZ - cX * sY * sZ, cX * sY * cZ + sX * cY * sZ,
          cX * cY * sZ + sX * sY * cZ, cX * cY * cZ - sX * sY * sZ];
      }
      class NativeOrientationSensor extends NativeSensor {
        constructor(options) { super('sensor.orientation', options); this.quaternion = null; }
        _update(value) { this.quaternion = Object.freeze(orientationQuaternion(value)); }
        populateMatrix() { throw new DOMException('populateMatrix is unavailable in this container', 'NotSupportedError'); }
      }
      function installSensor(name, factory) {
        if (typeof window[name] === 'function') return;
        try { Object.defineProperty(window, name, { value: factory, configurable: true }); } catch (_) {}
      }
      installGeolocationFallback();
      installVibrationFallback();
      installBatteryFallback();
      installSensor('Sensor', NativeSensor);
      installSensor('Accelerometer', class Accelerometer extends NativeVectorSensor {
        constructor(options) { super('sensor.accelerometer', options); }
      });
      installSensor('Gyroscope', class Gyroscope extends NativeVectorSensor {
        constructor(options) { super('sensor.gyroscope', options); }
      });
      installSensor('Magnetometer', class Magnetometer extends NativeVectorSensor {
        constructor(options) { super('sensor.magneticField', options); }
      });
      installSensor('AbsoluteOrientationSensor', NativeOrientationSensor);
    })();
""".trimIndent()
