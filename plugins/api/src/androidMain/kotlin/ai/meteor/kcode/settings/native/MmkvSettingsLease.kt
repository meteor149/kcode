package ai.meteor.kcode.settings.native

import android.content.Context
import com.tencent.mmkv.kmp.MMKV
import com.tencent.mmkv.kmp.MMKVConfig
import com.tencent.mmkv.kmp.initialize
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Host SDK primitive only: no application setting keys, defaults, or codecs. */
private object MmkvHandles {
    val mutex = Mutex()
    val entries = mutableMapOf<Pair<String, String>, MmkvHandle>()
}
internal class MmkvHandle(val mmkv: MMKV, var cryptKey: String?) {
    val mutex = Mutex()
    var references = 1
}

class MmkvSettingsLease internal constructor(
    private val key: Pair<String, String>,
    private val handle: MmkvHandle,
) {
    private var closed = false
    suspend fun <T> access(block: (MMKV) -> T): T = withContext(Dispatchers.IO) {
        handle.mutex.withLock {
            check(!closed) { "MMKV storage lease is closed" }
            block(handle.mmkv)
        }
    }
    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        handle.mutex.withLock {
            if (!closed) {
                closed = true
                MmkvHandles.mutex.withLock {
                    handle.references--
                    if (handle.references == 0) {
                        try { handle.mmkv.close() } finally {
                            handle.cryptKey = null
                            MmkvHandles.entries.remove(key)
                        }
                    }
                }
            }
        }
    }
}

suspend fun openMmkvSettingsLease(
    context: Context,
    id: String,
    cryptKey: String? = null,
    rootDirectory: File = File(context.applicationContext.filesDir, "mmkv"),
): MmkvSettingsLease {
    var retained: MmkvSettingsLease? = null
    try {
        val lease = withContext(Dispatchers.IO) {
            val root = rootDirectory.canonicalPath
            val key = root to id
            MmkvHandles.mutex.withLock {
                val existing = MmkvHandles.entries[key]
                if (existing != null) {
                    require(existing.cryptKey == cryptKey) { "Conflicting MMKV encryption configuration" }
                    existing.references++
                    MmkvSettingsLease(key, existing)
                } else {
                    MMKV.initialize(context.applicationContext, root)
                    val mmkv = MMKV.mmkvWithID(id, MMKVConfig(cryptKey = cryptKey, aes256 = cryptKey != null, rootPath = root))
                    val handle = MmkvHandle(mmkv, cryptKey)
                    MmkvHandles.entries[key] = handle
                    MmkvSettingsLease(key, handle)
                }.also { retained = it }
            }
        }
        retained = null
        return lease
    } finally {
        retained?.close()
    }
}
