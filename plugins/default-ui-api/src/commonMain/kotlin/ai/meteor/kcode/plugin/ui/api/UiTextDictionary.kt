package ai.meteor.kcode.plugin.ui.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Feature-owned rendering defaults, withdrawn together with their contribution. */
class UiTextDictionary(
    val id: String,
    values: Map<String, String>,
    val available: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow(),
) {
    val values: Map<String, String> = values.toMap()

    init {
        require(id.isNotBlank())
        require(values.keys.all { it.isNotBlank() })
    }

}
