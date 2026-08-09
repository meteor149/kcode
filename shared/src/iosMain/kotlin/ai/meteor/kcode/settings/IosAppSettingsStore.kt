@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ai.meteor.kcode.settings

import com.tencent.mmkv.kmp.MMKV
import com.tencent.mmkv.kmp.MMKVConfig
import com.tencent.mmkv.kmp.initialize
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
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
    return decorateIosSettingsStore(MmkvAppSettingsStore(
        mmkv = MMKV.mmkvWithID(
            SETTINGS_MMAP_ID,
            MMKVConfig(cryptKey = cryptKey, aes256 = true),
        ),
        protection = SettingsProtection.IosKeychain,
    ))
}

internal expect fun decorateIosSettingsStore(delegate: AppSettingsStore): AppSettingsStore

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
        val query = baseQuery() ?: return null
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        val result = alloc<CFTypeRefVar>()
        if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) {
            CFRelease(query)
            return null
        }
        val data = result.value ?: run {
            CFRelease(query)
            return null
        }
        val length = CFDataGetLength(data.reinterpret()).toInt()
        val value = CFDataGetBytePtr(data.reinterpret())?.readBytes(length)?.decodeToString()
        CFRelease(data)
        CFRelease(query)
        value
    }

    fun write(value: String) {
        val query = baseQuery() ?: return
        SecItemDelete(query)
        if (value.isEmpty()) {
            CFRelease(query)
            return
        }
        val bytes = value.encodeToByteArray()
        val data = bytes.usePinned { pinned ->
            CFDataCreate(kCFAllocatorDefault, pinned.addressOf(0).reinterpret(), bytes.size.toLong())
        }
        CFDictionarySetValue(query, kSecValueData, data)
        SecItemAdd(query, null)
        CFRelease(data)
        CFRelease(query)
    }

    private fun baseQuery(): CFMutableDictionaryRef? {
        val query = CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        ) ?: return null
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        setString(query, kSecAttrService, "ai.meteor.kcode.credentials")
        setString(query, kSecAttrAccount, account)
        return query
    }

    private fun setString(query: CFMutableDictionaryRef, key: COpaquePointer?, value: String) {
        val string = CFStringCreateWithCString(kCFAllocatorDefault, value, kCFStringEncodingUTF8) ?: return
        CFDictionarySetValue(query, key, string)
        CFRelease(string)
    }
}
