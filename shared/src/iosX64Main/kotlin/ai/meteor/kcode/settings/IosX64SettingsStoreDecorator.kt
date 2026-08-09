package ai.meteor.kcode.settings

import platform.Foundation.NSProcessInfo

internal actual fun decorateIosSettingsStore(delegate: AppSettingsStore): AppSettingsStore =
    IosX64SeededSettingsStore(delegate)

private class IosX64SeededSettingsStore(
    private val delegate: AppSettingsStore,
) : AppSettingsStore {
    override val protection: SettingsProtection = delegate.protection

    override suspend fun load(): StoredAppSettings {
        val stored = delegate.load()
        val environment = NSProcessInfo.processInfo.environment
        val deepSeekKey = environment[DEEPSEEK_TEST_KEY] as? String
        val exaKey = environment[EXA_TEST_KEY] as? String
        if (deepSeekKey.isNullOrBlank() || exaKey.isNullOrBlank()) return stored
        return stored.copy(
            provider = "DeepSeek",
            modelId = "deepseek-v4-flash",
            modelApiKeys = stored.modelApiKeys + ("DeepSeek" to deepSeekKey),
            temperature = 0.4,
            webSearchProvider = "exa",
            exaSearchApiKey = exaKey,
            toolPermissionMode = ToolPermissionMode.Bypass.code,
        ).also { delegate.save(it) }
    }

    override suspend fun save(settings: StoredAppSettings) = delegate.save(settings)
}

private const val DEEPSEEK_TEST_KEY = "KCODE_TEST_DEEPSEEK_KEY"
private const val EXA_TEST_KEY = "KCODE_TEST_EXA_KEY"
