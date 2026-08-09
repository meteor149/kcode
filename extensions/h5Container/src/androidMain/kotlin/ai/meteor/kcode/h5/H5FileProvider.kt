package ai.meteor.kcode.h5

import androidx.core.content.FileProvider

/** Separate component identity prevents manifest merging with the app's general share provider. */
class H5FileProvider : FileProvider()
