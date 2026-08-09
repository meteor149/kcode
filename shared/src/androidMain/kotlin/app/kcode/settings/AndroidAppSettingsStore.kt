package app.kcode.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.tencent.mmkv.kmp.MMKV
import com.tencent.mmkv.kmp.MMKVConfig
import com.tencent.mmkv.kmp.initialize
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

fun createAndroidAppSettingsStore(context: Context): AppSettingsStore {
    AndroidStoreHolder.instance?.let { return it }
    return synchronized(AndroidStoreHolder) {
        AndroidStoreHolder.instance ?: run {
            val applicationContext = context.applicationContext
            val cipher = AndroidKeyCipher()
            MMKV.initialize(applicationContext)
            val bootstrap = MMKV.mmkvWithID(BOOTSTRAP_MMAP_ID)
            val cryptKey = bootstrap.decodeString(ENCRYPTED_CRYPT_KEY)
                ?.let(cipher::decrypt)
                ?.takeIf { it.length == CRYPT_KEY_LENGTH }
                ?: createCryptKey().also {
                    check(bootstrap.encodeString(ENCRYPTED_CRYPT_KEY, cipher.encrypt(it))) {
                        "Failed to persist the MMKV encryption key"
                    }
                }
            MmkvAppSettingsStore(
                mmkv = MMKV.mmkvWithID(
                    SETTINGS_MMAP_ID,
                    MMKVConfig(cryptKey = cryptKey, aes256 = true),
                ),
                protection = SettingsProtection.AndroidKeystore,
            ).also { AndroidStoreHolder.instance = it }
        }
    }
}

private fun createCryptKey(): String = ByteArray(16)
    .also(SecureRandom()::nextBytes)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val BOOTSTRAP_MMAP_ID = "kcode.settings.bootstrap"
private const val SETTINGS_MMAP_ID = "kcode.settings"
private const val ENCRYPTED_CRYPT_KEY = "encrypted_crypt_key"
private const val CRYPT_KEY_LENGTH = 32

private object AndroidStoreHolder {
    @Volatile
    var instance: AppSettingsStore? = null
}

private class AndroidKeyCipher {
    private val key: SecretKey
        get() {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
        }

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val encrypted = cipher.doFinal(plainText.encodeToByteArray())
        val payload = ByteBuffer.allocate(4 + cipher.iv.size + encrypted.size)
            .putInt(cipher.iv.size)
            .put(cipher.iv)
            .put(encrypted)
            .array()
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun decrypt(payload: String): String {
        if (payload.isEmpty()) return ""
        return runCatching {
            val bytes = ByteBuffer.wrap(Base64.decode(payload, Base64.NO_WRAP))
            val iv = ByteArray(bytes.int).also(bytes::get)
            val encrypted = ByteArray(bytes.remaining()).also(bytes::get)
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                doFinal(encrypted).decodeToString()
            }
        }.getOrDefault("")
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "kcode.settings.api-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
