package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import org.cordis.include.CompositionLayer
import org.cordis.include.CompositionResult
import org.cordis.include.PatchOptions
import org.cordis.include.composeEntries
import org.cordis.loader.EntryOptions
import org.cordis.loader.changeTo
import org.cordis.loader.IsolationRule
import org.cordis.loader.FieldPatch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull

/** Compiles user intent only. Package verification and resource allocation happen during activation. */
class ProfileCompiler {
    fun compile(
        profile: ProfileDefinition,
        bundles: List<ProfileBundle>,
        machineOverrides: List<ProfileOperation> = emptyList(),
        launchOverrides: List<ProfileOperation> = emptyList(),
    ): CompositionResult {
        profile.validate()
        require(bundles.map { it.id }.distinct().size == bundles.size) { "Duplicate resolved bundle" }
        val available = bundles.associateBy { it.id }
        val layers = profile.bundles.map { reference ->
            val bundle = requireNotNull(available[reference.id]) { "Missing profile bundle '${reference.id}'" }
            require(bundle.formatVersion == 1 && bundle.version == reference.version) { "Incompatible profile bundle '${reference.id}'" }
            CompositionLayer("bundle:${bundle.id}@${bundle.version}", bundle.patches.map(::patch))
        } + listOf(
            CompositionLayer("profile:${profile.id}", profile.patches.map(::patch)),
            CompositionLayer("machine", machineOverrides.map(::patch)),
            CompositionLayer("launch", launchOverrides.map(::patch)),
        )
        return composeEntries(emptyList(), layers)
    }

    private fun entry(value: ProfileEntry): EntryOptions {
        require(value.id.isNotBlank() && value.packageId.isNotBlank()) { "Incomplete profile entry" }
        require(value.children == null || (value.config == null && value.packageId in setOf("core.group", "cordis:group"))) {
            "Profile groups use core.group and children rather than a plugin configuration"
        }
        require((value.inject.keys + value.intercept.keys + value.isolate.keys).all { it.isNotBlank() }) {
            "Profile context service identities must not be blank"
        }
        return EntryOptions(
            id = value.id,
            name = value.packageId,
            config = value.children?.map(::entry) ?: value.config,
            group = value.children?.let { true },
            disabled = !value.enabled,
            extra = mapOf("kcode.configurationKind" to value.configurationKind),
            inject = value.inject.takeIf { it.isNotEmpty() }?.mapValues { (_, value) -> contextValue(value) },
            intercept = value.intercept.takeIf { it.isNotEmpty() }?.mapValues { (_, value) -> contextValue(value) },
            isolate = value.isolate.takeIf { it.isNotEmpty() }?.mapValues { (_, realm) ->
                if (realm == null) IsolationRule.Local else IsolationRule.Shared(realm.also { require(it.isNotBlank()) { "Empty shared realm" } })
            },
        )
    }

    private fun contextValue(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { (_, child) -> contextValue(child) }
        is JsonArray -> value.map(::contextValue)
        is JsonPrimitive -> if (value.isString) value.content else
            value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull ?: error("Invalid context value")
    }

    private fun patch(operation: ProfileOperation): PatchOptions = when (operation) {
        is ProfileOperation.Insert -> PatchOptions(id = operation.parent, insert = operation.entries.map(::entry), position = operation.position)
        is ProfileOperation.Configure -> PatchOptions(
            id = operation.target,
            config = changeTo(operation.config),
            extra = mapOf("kcode.configurationKind" to operation.configurationKind),
        )
        is ProfileOperation.Enable -> PatchOptions(id = operation.target, disabled = changeTo(false))
        is ProfileOperation.Disable -> PatchOptions(id = operation.target, disabled = changeTo(true))
        is ProfileOperation.Replace -> PatchOptions(
            id = operation.target,
            name = operation.expectedPackageId,
            replacement = operation.packageId,
        )
        is ProfileOperation.Remove -> PatchOptions(id = operation.target, remove = true)
        is ProfileOperation.Move -> PatchOptions(id = operation.target, parent = changeTo(operation.parent), position = operation.position)
        is ProfileOperation.Context -> {
            require((operation.inject.orEmpty().keys + operation.intercept.orEmpty().keys + operation.isolate.orEmpty().keys).all { it.isNotBlank() }) {
                "Profile context service identities must not be blank"
            }
            PatchOptions(
                id = operation.target,
                inject = operation.inject?.let { changeTo(it.mapValues { (_, value) -> contextValue(value) }) } ?: FieldPatch.Keep,
                intercept = operation.intercept?.let { changeTo(it.mapValues { (_, value) -> contextValue(value) }) } ?: FieldPatch.Keep,
                isolate = operation.isolate?.let { rules -> changeTo(rules.mapValues { (_, realm) ->
                    if (realm == null) IsolationRule.Local else IsolationRule.Shared(realm.also { require(it.isNotBlank()) { "Empty shared realm" } })
                }) } ?: FieldPatch.Keep,
            )
        }
    }
}
