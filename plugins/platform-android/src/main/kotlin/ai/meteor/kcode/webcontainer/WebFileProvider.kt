package ai.meteor.kcode.webcontainer

import androidx.core.content.FileProvider

/** Separate component identity prevents manifest merging with the app's general share provider. */
class WebFileProvider : FileProvider()
