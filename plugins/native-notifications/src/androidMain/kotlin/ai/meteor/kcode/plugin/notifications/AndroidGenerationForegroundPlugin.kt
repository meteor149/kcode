package ai.meteor.kcode.plugin.notifications

import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.KcodeGeneration
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeLocalization
import ai.meteor.kcode.localization.configuredLanguage
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.plugin.api.PluginCleanupException
import ai.meteor.kcode.plugin.api.PluginHostInputs
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

fun generationForegroundConfig(channelName: String, title: String, text: String): String = buildJsonObject {
    put("channelName", channelName)
    put("title", title)
    put("text", text)
}.toString()

private data class ForegroundStrings(val channelName: String, val title: String, val text: String)

private fun parseGenerationForegroundConfig(config: String): ForegroundStrings {
    val strings = Json.parseToJsonElement(config).jsonObject
    fun text(key: String): String {
        val primitive = requireNotNull(strings[key]?.jsonPrimitive) { "Missing generation notification $key" }
        require(primitive.isString && primitive.content.isNotBlank()) { "Generation notification $key must be a nonblank string" }
        return primitive.content
    }
    return ForegroundStrings(text("channelName"), text("title"), text("text"))
}

/** Explicit notification text only requires generation and the generic OS host. */
class AndroidGenerationForegroundPlugin : Plugin<String> {
    override val name = "android-generation-foreground"
    override val config = ConfigValidator<String> { value -> parseGenerationForegroundConfig(value); value }
    override val inject = dependencies(KcodeGeneration.Key)
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        val strings = parseGenerationForegroundConfig(config)
        applyGenerationForeground(ctx, effect) { strings }
    }
}

class LocalizedAndroidGenerationForegroundPlugin : Plugin<Unit> {
    override val name = "android-localized-generation-foreground"
    override val config = ConfigValidator<Unit> { it }
    override val inject = dependencies(KcodeGeneration.Key, KcodeSettings.Key, KcodeLocalization.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val catalog = ctx.require(KcodeLocalization.Key).catalog
        val settings = ctx.require(KcodeSettings.Key).store
        applyGenerationForeground(ctx, effect) {
            val language = catalog.configuredLanguage(settings.load())
            ForegroundStrings(
                catalog.translate(language, UiText.GenerationNotificationChannel),
                catalog.translate(language, UiText.GenerationNotificationTitle),
                catalog.translate(language, UiText.GenerationNotificationText),
            )
        }
    }
}

/** Retiring either entry releases its allowance without canceling the model response. */
private suspend fun applyGenerationForeground(
    ctx: Context,
    effect: EffectScope,
    resolveStrings: suspend () -> ForegroundStrings,
) {
    val inputs = requireNotNull(PluginHostInputs.current(ctx, effect) as? AndroidPluginHostInputs) {
        "Android foreground execution requires native host inputs"
    }
    val context = inputs.applicationContext()
    val runner = ctx.require(KcodeGeneration.Key).runner
    val job = SupervisorJob()
    val cleanupFailure = AtomicReference<Throwable?>(null)
    val errors = CoroutineExceptionHandler { _, error -> cleanupFailure.compareAndSet(null, error) }
    effect.collect {
        withContext(NonCancellable) {
            job.cancelAndJoin()
            cleanupFailure.get()?.let { throw PluginCleanupException("Android foreground execution", listOf(it)) }
        }
    }
    val scope = CoroutineScope(job + Dispatchers.Main.immediate + errors)
    scope.launch {
        runner.activeTasks.map { it > 0 }.distinctUntilChanged().collectLatest { active ->
            if (!active) return@collectLatest
            val strings = resolveStrings()
            val channelName = strings.channelName
            val title = strings.title
            val description = strings.text
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("llm_generation", channelName, NotificationManager.IMPORTANCE_LOW),
            )
            val notification = Notification.Builder(context, "llm_generation")
                .setSmallIcon(context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(description)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .apply {
                    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
                        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        setContentIntent(PendingIntent.getActivity(context, 0, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    }
                }.build()
            // Foreground allowance failure must not abort the existing model response.
            val lease = runCatching { inputs.foregroundExecution().acquire(notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
                .getOrNull() ?: return@collectLatest
            try { awaitCancellation() } finally { lease.close() }
        }
    }
}
