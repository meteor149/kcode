package ai.meteor.kcode.plugin.ui.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Feature-owned rendering defaults, withdrawn together with their contribution. */
class UiTextDictionary(val id: String, values: Map<String, String>) {
    val values: Map<String, String> = values.toMap()
    private val live = MutableStateFlow(true)
    val available: StateFlow<Boolean> = live.asStateFlow()

    init {
        require(id.isNotBlank())
        require(values.keys.all { it.isNotBlank() })
    }

    internal fun revoke() { live.value = false }
}
