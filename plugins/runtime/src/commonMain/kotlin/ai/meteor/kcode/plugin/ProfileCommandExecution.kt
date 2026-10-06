package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.profiles.ProfileCompositionState
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/** Records this command's publication before cancellation can interrupt its return path. */
internal class ProfileCommandExecution : AbstractCoroutineContextElement(Key) {
    var committed: ProfileCompositionState? = null
        private set

    companion object Key : CoroutineContext.Key<ProfileCommandExecution>

    fun complete(state: ProfileCompositionState) {
        committed = state
    }
}

internal suspend fun recordProfileCommandPublication(state: ProfileCompositionState) {
    currentCoroutineContext()[ProfileCommandExecution]?.complete(state)
}
