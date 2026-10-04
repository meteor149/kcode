package ai.meteor.kcode.plugin.export

import ai.meteor.kcode.export.ConversationImageSaver
import ai.meteor.kcode.export.ImageSaveResult
import ai.meteor.kcode.plugin.api.ConversationImageSaverFactory
import ai.meteor.kcode.plugin.api.ConversationImageSaverResource
import ai.meteor.kcode.plugin.api.KcodeConversationImageSaving
import ai.meteor.kcode.plugin.api.PluginCleanupException
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

object ConversationImageSavingProviderPlugin : Plugin<ConversationImageSaverFactory> {
    override val name = "conversation-image-saving"

    override suspend fun apply(ctx: Context, config: ConversationImageSaverFactory, effect: EffectScope) {
        val owner = PluginOperationOwner(name)
        val resource = withContext(NonCancellable) {
            owner.run { config.create() }.also { resource ->
                effect.collect {
                    owner.requireCanClose()
                    withContext(NonCancellable) {
                        val failures = mutableListOf<Throwable>()
                        runCatching { owner.close() }.exceptionOrNull()?.let(failures::add)
                        runCatching { resource.close() }.exceptionOrNull()?.let(failures::add)
                        if (failures.isNotEmpty()) throw PluginCleanupException(name, failures)
                    }
                }
            }
        }
        if (!effect.isActive) return
        KcodeConversationImageSaving(ctx, object : ConversationImageSaver {
            override suspend fun save(image: ImageBitmap, fileName: String) =
                owner.run { resource.saver.save(image, fileName) }

            override suspend fun share(image: ImageBitmap, fileName: String) =
                owner.run { resource.saver.share(image, fileName) }
        })
    }
}

/** Explicit default for headless/custom bundles without a native saving implementation. */
fun unsupportedConversationImageSaverFactory() = ConversationImageSaverFactory {
    ConversationImageSaverResource(object : ConversationImageSaver {
        override suspend fun save(image: ImageBitmap, fileName: String) = ImageSaveResult.Unsupported
        override suspend fun share(image: ImageBitmap, fileName: String) = ImageSaveResult.Unsupported
    }) {}
}
