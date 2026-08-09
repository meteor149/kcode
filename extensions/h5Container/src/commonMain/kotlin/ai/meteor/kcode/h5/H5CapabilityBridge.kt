package ai.meteor.kcode.h5

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class H5CapabilityDescriptor(
    val id: String,
    val available: Boolean,
    val subscription: Boolean = false,
    val sensitive: Boolean = false,
    val platform: String,
    val reason: String? = null,
)

@Serializable
internal data class H5BridgeRequest(
    val id: String,
    val type: String,
    val method: String? = null,
    val params: JsonObject = JsonObject(emptyMap()),
    val subscriptionId: String? = null,
)

internal val H5BridgeJson = Json {
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

/** Injected before page scripts so every platform exposes the same Promise-based API. */
val KCODE_H5_SDK: String = """
    (function () {
      if (window.kcode && window.kcode.capabilities) return;
      var pending = new Map();
      var subscriptions = new Map();
      var sequence = 0;
      function nextId(prefix) { sequence += 1; return prefix + '-' + Date.now() + '-' + sequence; }
      function post(message) {
        if (!window.kcodeNative || typeof window.kcodeNative.postMessage !== 'function') {
          throw new Error('kcode native bridge is unavailable on this platform');
        }
        window.kcodeNative.postMessage(JSON.stringify(message));
      }
      function request(type, method, params, subscriptionId) {
        var id = nextId('request');
        return new Promise(function (resolve, reject) {
          pending.set(id, { resolve: resolve, reject: reject });
          try { post({ id: id, type: type, method: method || null, params: params || {}, subscriptionId: subscriptionId || null }); }
          catch (error) { pending.delete(id); reject(error); }
        });
      }
      window.__kcodeDispatch = function (payload) {
        var message = typeof payload === 'string' ? JSON.parse(payload) : payload;
        if (message.type === 'event') {
          var callback = subscriptions.get(message.subscriptionId);
          if (callback) callback(message.result);
          return;
        }
        var waiter = pending.get(message.id);
        if (!waiter) return;
        pending.delete(message.id);
        if (message.error) {
          var error = new Error(message.error.message || 'Capability call failed');
          error.code = message.error.code || 'native_error';
          waiter.reject(error);
        } else waiter.resolve(message.result);
      };
      var capabilities = {
        list: function () { return request('list', null, {}); },
        invoke: function (method, params) { return request('invoke', method, params || {}); },
        subscribe: function (method, params, onEvent) {
          if (typeof onEvent !== 'function') return Promise.reject(new TypeError('onEvent must be a function'));
          return request('subscribe', method, params || {}).then(function (result) {
            var subscriptionId = result.subscriptionId;
            subscriptions.set(subscriptionId, onEvent);
            return Object.freeze({
              id: subscriptionId,
              unsubscribe: function () {
                subscriptions.delete(subscriptionId);
                return request('unsubscribe', null, {}, subscriptionId);
              }
            });
          });
        },
        unsubscribe: function (subscriptionId) {
          subscriptions.delete(subscriptionId);
          return request('unsubscribe', null, {}, subscriptionId);
        }
      };
      window.kcode = Object.freeze({ capabilities: Object.freeze(capabilities), version: '1.0.0' });
      window.dispatchEvent(new CustomEvent('kcode-ready'));
    })();
""".trimIndent()
