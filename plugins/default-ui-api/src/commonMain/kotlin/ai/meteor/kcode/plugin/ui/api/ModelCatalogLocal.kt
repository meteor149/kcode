package ai.meteor.kcode.plugin.ui.api

import ai.meteor.kcode.model.ModelCatalogSnapshot
import androidx.compose.runtime.staticCompositionLocalOf

val LocalModelCatalog = staticCompositionLocalOf { ModelCatalogSnapshot() }
