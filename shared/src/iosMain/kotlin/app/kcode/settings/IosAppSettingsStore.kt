@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.kcode.settings

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.Foundation.NSData
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.NSObject

class IosAppSettingsStore : AppSettingsStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val modelKeychain = IosKeychain("model-api-key")
    private val searchKeychain = IosKeychain("web-search-api-key")
    private val exaKeychain = IosKeychain("exa-search-api-key")

    override val protection = SettingsProtection.IosKeychain

    override suspend fun load() = StoredAppSettings(
        provider = defaults.stringForKey(PROVIDER) ?: "OpenAI",
        modelId = defaults.stringForKey(MODEL_ID) ?: "gpt-4o-mini",
        apiKey = modelKeychain.read().orEmpty(),
        modelEndpoint = defaults.stringForKey(MODEL_ENDPOINT).orEmpty(),
        modelRegion = defaults.stringForKey(MODEL_REGION).orEmpty(),
        modelDeployment = defaults.stringForKey(MODEL_DEPLOYMENT).orEmpty(),
        modelApiVersion = defaults.stringForKey(MODEL_API_VERSION).orEmpty(),
        webSearchApiKey = searchKeychain.read().orEmpty(),
        exaSearchApiKey = exaKeychain.read().orEmpty(),
        webSearchProvider = defaults.stringForKey(WEB_SEARCH_PROVIDER)
            ?: if (searchKeychain.read().isNullOrBlank()) "google" else "bright_data",
        temperature = defaults.objectForKey(TEMPERATURE)?.let { defaults.doubleForKey(TEMPERATURE) } ?: 0.7,
        language = defaults.stringForKey(LANGUAGE) ?: "zh",
        shellExecutionMode = defaults.stringForKey(SHELL_EXECUTION_MODE) ?: ShellExecutionMode.App.code,
        toolPermissionMode = defaults.stringForKey(TOOL_PERMISSION_MODE)
            ?: defaults.stringForKey(LEGACY_SHELL_PERMISSION_MODE)
            ?: ToolPermissionMode.Ask.code,
    )

    override suspend fun save(settings: StoredAppSettings) {
        defaults.setObject(settings.provider, PROVIDER)
        defaults.setObject(settings.modelId, MODEL_ID)
        defaults.setObject(settings.modelEndpoint, MODEL_ENDPOINT)
        defaults.setObject(settings.modelRegion, MODEL_REGION)
        defaults.setObject(settings.modelDeployment, MODEL_DEPLOYMENT)
        defaults.setObject(settings.modelApiVersion, MODEL_API_VERSION)
        defaults.setDouble(settings.temperature, TEMPERATURE)
        defaults.setObject(settings.language, LANGUAGE)
        defaults.setObject(settings.shellExecutionMode, SHELL_EXECUTION_MODE)
        defaults.setObject(settings.toolPermissionMode, TOOL_PERMISSION_MODE)
        defaults.removeObjectForKey(LEGACY_SHELL_PERMISSION_MODE)
        modelKeychain.write(settings.apiKey)
        searchKeychain.write(settings.webSearchApiKey)
        exaKeychain.write(settings.exaSearchApiKey)
        defaults.setObject(settings.webSearchProvider, WEB_SEARCH_PROVIDER)
    }

    private companion object {
        const val PROVIDER = "kcode.model.provider"
        const val MODEL_ID = "kcode.model.id"
        const val MODEL_ENDPOINT = "kcode.model.endpoint"
        const val MODEL_REGION = "kcode.model.region"
        const val MODEL_DEPLOYMENT = "kcode.model.deployment"
        const val MODEL_API_VERSION = "kcode.model.api.version"
        const val TEMPERATURE = "kcode.model.temperature"
        const val LANGUAGE = "kcode.ui.language"
        const val SHELL_EXECUTION_MODE = "kcode.shell.execution.mode"
        const val TOOL_PERMISSION_MODE = "kcode.tool.permission.mode"
        const val LEGACY_SHELL_PERMISSION_MODE = "kcode.shell.permission.mode"
        const val WEB_SEARCH_PROVIDER = "kcode.web.search.provider"
    }
}

private class IosKeychain(private val account: String) {
    fun read(): String? = memScoped {
        val query = baseQuery().apply { setObject(true, kSecReturnData as platform.Foundation.NSString) }
        val result = alloc<COpaquePointerVar>()
        if (SecItemCopyMatching(query as CFDictionaryRef, result.ptr) != errSecSuccess) return null
        val data = CFBridgingRelease(result.value) as? NSData ?: return null
        data.bytes?.readBytes(data.length.toInt())?.decodeToString()
    }

    fun write(value: String) {
        val query = baseQuery()
        SecItemDelete(query as CFDictionaryRef)
        if (value.isEmpty()) return
        val bytes = value.encodeToByteArray()
        val data = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        query.setObject(data, kSecValueData as platform.Foundation.NSString)
        SecItemAdd(query as CFDictionaryRef, null)
    }

    private fun baseQuery() = NSMutableDictionary().apply {
        setObject(kSecClassGenericPassword as NSObject, kSecClass as platform.Foundation.NSString)
        setObject("app.kcode.credentials", kSecAttrService as platform.Foundation.NSString)
        setObject(account, kSecAttrAccount as platform.Foundation.NSString)
    }
}
