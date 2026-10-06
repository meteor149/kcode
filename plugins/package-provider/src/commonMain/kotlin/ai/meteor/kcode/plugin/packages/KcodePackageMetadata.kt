package ai.meteor.kcode.plugin.packages

import ai.meteor.kcode.plugin.CurrentPluginApiVersion
import ai.meteor.kcode.plugin.MinimumCompatiblePluginApiVersion
import ai.meteor.kcode.plugin.api.StoredPluginConfiguration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.cordis.packages.PackageVariant
import org.cordis.packages.PluginPackageManifest

const val KcodePackageExtension = "ai.meteor.kcode"

internal expect val NativePackageRuntimeAbi: String

data class PluginApiCompatibilityRange(val minimum: Int, val maximum: Int) {
    init {
        require(minimum > 0 && minimum <= maximum) { "Invalid plugin API compatibility range" }
    }

    operator fun contains(apiVersion: Int): Boolean = apiVersion in minimum..maximum
}

data class KcodePackageVariantMetadata(
    val pluginApi: Int,
    val pluginApiRange: PluginApiCompatibilityRange?,
    val runtimeAbi: String,
    val capabilities: Set<String>,
)

fun PackageVariant.nativePackageName(): String? {
    require(runtime.id in setOf("jvm", "android-dex")) { "Unsupported kcode package loader: ${runtime.id}" }
    require(runtime.entryPoint.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))) { "Invalid native plugin class entry" }
    require(runtime.minVersion?.toIntOrNull()?.let { it > 0 } == true) { "Native loader requires an integer minimum runtime version" }
    if (runtime.id == "jvm") {
        require(runtime.metadata.isEmpty() && artifact.endsWith(".jar")) { "Invalid JVM artifact metadata" }
        return null
    }
    require(runtime.metadata.keys == setOf("packageName") && artifact.endsWith(".apk")) { "Invalid Android artifact metadata" }
    return runtime.metadata.getValue("packageName").jsonPrimitive.also { require(it.isString) }.content.also {
        require(it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+"))) { "Invalid Android package name" }
    }
}

fun PackageVariant.kcodeMetadata(): KcodePackageVariantMetadata {
    val extension = extensions[KcodePackageExtension] as? JsonObject ?: error("Missing kcode variant metadata")
    val requiredFields = setOf("pluginApi", "runtimeAbi", "capabilities")
    require(extension.keys == requiredFields || extension.keys == requiredFields + "pluginApiRange") {
        "Invalid kcode variant metadata fields"
    }
    val apiValue = extension.getValue("pluginApi").jsonPrimitive
    val abiValue = extension.getValue("runtimeAbi").jsonPrimitive
    require(!apiValue.isString && abiValue.isString) { "Invalid kcode ABI field types" }
    val api = apiValue.int
    val abi = abiValue.content
    val apiRange = extension["pluginApiRange"]?.let { rangeValue ->
        val range = rangeValue as? JsonObject ?: error("Invalid plugin API compatibility range")
        require(range.keys == setOf("minimum", "maximum")) { "Invalid plugin API compatibility range fields" }
        val minimumValue = range.getValue("minimum").jsonPrimitive
        val maximumValue = range.getValue("maximum").jsonPrimitive
        require(!minimumValue.isString && !maximumValue.isString) { "Invalid plugin API compatibility range types" }
        PluginApiCompatibilityRange(minimumValue.int, maximumValue.int).also {
            require(api in it) { "Compiled plugin API must be inside its declared compatibility range" }
        }
    }
    val capabilities = Json.decodeFromJsonElement<List<String>>(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), extension.getValue("capabilities"))
    require(api > 0 && abi.matches(Regex("[a-f0-9]{64}"))) { "Invalid kcode ABI requirement" }
    require(capabilities.all { it.isNotBlank() } && capabilities.distinct().size == capabilities.size) { "Invalid kcode capabilities" }
    return KcodePackageVariantMetadata(api, apiRange, abi, capabilities.toSet())
}

fun PluginPackageManifest.kcodeConfiguration(): StoredPluginConfiguration {
    val extension = extensions[KcodePackageExtension] as? JsonObject ?: error("Missing kcode package metadata")
    require(extension.keys == setOf("configuration")) { "Invalid kcode package metadata fields" }
    return Json.decodeFromJsonElement(StoredPluginConfiguration.serializer(), extension.getValue("configuration")).also { it.decode() }
}

fun kcodeVariantExtension(
    runtimeAbi: String,
    capabilities: Set<String> = emptySet(),
    pluginApi: Int = CurrentPluginApiVersion,
    pluginApiRange: PluginApiCompatibilityRange = PluginApiCompatibilityRange(
        MinimumCompatiblePluginApiVersion,
        CurrentPluginApiVersion,
    ),
): JsonObject = buildJsonObject {
    require(pluginApi in pluginApiRange) { "Compiled plugin API must be inside its declared compatibility range" }
    put(KcodePackageExtension, buildJsonObject {
        put("pluginApi", pluginApi)
        put("pluginApiRange", buildJsonObject {
            put("minimum", pluginApiRange.minimum)
            put("maximum", pluginApiRange.maximum)
        })
        put("runtimeAbi", runtimeAbi)
        put("capabilities", kotlinx.serialization.json.JsonArray(capabilities.sorted().map(::JsonPrimitive)))
    })
}

fun kcodeConfigurationExtension(
    configuration: StoredPluginConfiguration = StoredPluginConfiguration("unit"),
): JsonObject = buildJsonObject {
    put(KcodePackageExtension, buildJsonObject {
        put("configuration", Json.encodeToJsonElement(StoredPluginConfiguration.serializer(), configuration))
    })
}
