@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.kcode.settings

import com.tencent.mmkv.kmp.MMKV
import com.tencent.mmkv.kmp.MMKVConfig
import com.tencent.mmkv.kmp.initialize
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
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecRandomDefault
import platform.Security.kSecValueData
import platform.darwin.NSObject

class IosAppSettingsStore private constructor(
    delegate: AppSettingsStore,
) : AppSettingsStore by delegate {
    constructor() : this(createIosMmkvSettingsStore())
}

private fun createIosMmkvSettingsStore(): AppSettingsStore {
    MMKV.initialize()
    val cryptKeyStore = IosKeychain("mmkv-settings-crypt-key")
    val cryptKey = cryptKeyStore.read()
        ?.takeIf { it.length == CRYPT_KEY_LENGTH }
        ?: createCryptKey().also(cryptKeyStore::write)
    return MmkvAppSettingsStore(
        mmkv = MMKV.mmkvWithID(
            SETTINGS_MMAP_ID,
            MMKVConfig(cryptKey = cryptKey, aes256 = true),
        ),
        protection = SettingsProtection.IosKeychain,
    )
}

private fun createCryptKey(): String {
    val randomBytes = ByteArray(16)
    val status = randomBytes.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, randomBytes.size.toULong(), pinned.addressOf(0))
    }
    check(status == errSecSuccess) { "Failed to generate the MMKV encryption key" }
    return randomBytes.joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private const val SETTINGS_MMAP_ID = "kcode.settings"
private const val CRYPT_KEY_LENGTH = 32

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
