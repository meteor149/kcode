package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.settings.StoredAppSettings

/** New installations and absent legacy fields use this provider's product defaults. */
internal fun defaultSettings() = StoredAppSettings(
    provider = "OpenAI",
    modelId = "gpt-4o-mini",
    dashscopeRegion = "china_mainland",
    webSearchProvider = "google",
    temperature = 0.7,
    language = "zh",
    shellExecutionMode = "app",
    toolPermissionMode = "ask",
)
