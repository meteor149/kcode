package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.export.ConversationImageSaver

/** Bounded per-mount allocation. Release follows cancellation and joining of all saving calls. */
fun interface ConversationImageSaverFactory {
    suspend fun create(): ConversationImageSaverResource
}

class ConversationImageSaverResource(
    val saver: ConversationImageSaver,
    val close: suspend () -> Unit,
)
