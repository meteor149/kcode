package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.settings.native.MmkvSettingsLease
import com.tencent.mmkv.kmp.MMKV

/** Android settings backed by the stable MMKV scalar keys and committed snapshot. */
class MmkvAppSettingsStore(
    private val lease: MmkvSettingsLease,
    override val protection: SettingsProtection,
) : AppSettingsStore {
    private val codec = ScalarSettingsCodec()
    override suspend fun load(): StoredAppSettings = lease.access { codec.load(MmkvScalarSettingsAccess(it)) }
    override suspend fun save(settings: StoredAppSettings) {
        lease.access { codec.save(MmkvScalarSettingsAccess(it), settings) }
    }
}

internal class MmkvScalarSettingsAccess(private val mmkv: MMKV) : ScalarSettingsAccess {
    override val allKeys get() = mmkv.allKeys.orEmpty().toList()
    override fun decodeString(key: String, default: String?) = mmkv.decodeString(key, default)
    override fun decodeDouble(key: String, default: Double) = mmkv.decodeDouble(key, default)
    override fun encodeString(key: String, value: String) = mmkv.encodeString(key, value)
    override fun encodeDouble(key: String, value: Double) = mmkv.encodeDouble(key, value)
}
