package ai.meteor.kcode.plugin.modelsettings

import ai.meteor.kcode.plugin.api.KcodeModelSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.EffectScope
import org.cordis.Plugin

internal data class TemperatureRange(val minimum: Double, val maximum: Double)

internal fun resolveTemperatureRange(value: Any?): TemperatureRange {
    if (value is TemperatureRange) return value
    val fields = when (value) {
        Unit -> JsonObject(emptyMap())
        is JsonObject -> value
        else -> error("Model settings configuration must be an object")
    }
    require(fields.keys.all { it in setOf("minimumTemperature", "maximumTemperature") }) {
        "Unknown model settings configuration field"
    }
    fun number(key: String, default: Double): Double {
        val raw = fields[key] ?: return default
        require(raw is JsonPrimitive && !raw.isString) { "$key must be a number" }
        return checkNotNull(raw.doubleOrNull).also {
            require(it.isFinite() && it in 0.0..2.0) { "$key must be between 0 and 2" }
        }
    }
    val minimum = number("minimumTemperature", 0.0)
    val maximum = number("maximumTemperature", 1.0)
    require(minimum <= maximum) { "minimumTemperature must not exceed maximumTemperature" }
    return TemperatureRange(minimum, maximum)
}

object ModelSettingsProviderPlugin : Plugin<Any?> {
    override val name = "provider.model-settings.catalog"
    override val config = ConfigValidator<Any?> { resolveTemperatureRange(it) }

    override suspend fun apply(ctx: Context, config: Any?, effect: EffectScope) {
        val policy = CatalogModelSettingsPolicy(config as TemperatureRange)
        effect.collect(Disposable { policy.close() })
        KcodeModelSettings(ctx, policy)
    }
}
