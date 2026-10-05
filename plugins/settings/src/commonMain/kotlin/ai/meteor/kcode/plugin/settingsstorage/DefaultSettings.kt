package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.settings.StoredAppSettings

/** Infrastructure has no feature defaults; providers interpret absent configuration. */
internal fun defaultSettings() = StoredAppSettings()
