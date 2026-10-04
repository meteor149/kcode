package ai.meteor.kcode.plugin.ui.api

import ai.meteor.kcode.plugin.api.UiContributionsSnapshot
import ai.meteor.kcode.plugin.api.UiSlotKey
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/** This vocabulary belongs to the default application, not the kernel registry. */
val DefaultUiSnapshotKey = UiSlotKey<ApplicationUiSlots>("default.compose.ui")

fun UiContributionsSnapshot.defaultUi(): ApplicationUiSlots = this[DefaultUiSnapshotKey] ?: ApplicationUiSlots()

@OptIn(InternalCoroutinesApi::class)
fun StateFlow<UiContributionsSnapshot>.defaultUiState(): StateFlow<ApplicationUiSlots> {
    val source = this
    return object : StateFlow<ApplicationUiSlots> {
        override val value: ApplicationUiSlots get() = source.value.defaultUi()
        override val replayCache: List<ApplicationUiSlots> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<ApplicationUiSlots>): Nothing =
            source.collect(object : FlowCollector<UiContributionsSnapshot> {
                override suspend fun emit(value: UiContributionsSnapshot) { collector.emit(value.defaultUi()) }
            })
    }
}
