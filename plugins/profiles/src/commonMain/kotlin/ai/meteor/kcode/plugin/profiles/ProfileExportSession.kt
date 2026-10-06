package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import org.cordis.loader.EntryOptions

/** Reviews original values in execution order and retains their source across module replacement. */
internal class ProfileExportSession(
    private val review: ProfileExportReview,
    private val definition: ProfileDefinition,
) {
    private data class Approved(val source: ProfileExportValue, val portable: JsonElement)
    private val fields = mutableMapOf<String, MutableMap<String, Approved>>()
    private val applied = mutableListOf<ProfileOperation>()
    private var entries = emptyMap<String, EntryOptions>()

    private fun approved(source: ProfileExportValue): Approved {
        val portable = try {
            // Policies cannot retain mutable aliases into source intent or previously reviewed output.
            review.review(source.copy(value = detached(source.value)))?.let(::detached)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Feature policy exceptions may contain credentials; do not expose their message/cause.
            throw IllegalArgumentException("Profile export review failed at ${source.location}/${source.field}")
        }
        return Approved(source, requireNotNull(portable) {
            "Profile export requires configuration review at ${source.location}/${source.field}"
        })
    }

    private fun detached(value: JsonElement): JsonElement = Json.parseToJsonElement(value.toString())

    private fun value(
        packageId: String,
        entryId: String,
        kind: String,
        location: String,
        field: String,
        original: JsonElement,
    ): JsonElement {
        val result = approved(ProfileExportValue(packageId, entryId, kind, location, field, original))
        fields.getOrPut(entryId) { linkedMapOf() }[field] = result
        return result.portable
    }

    private fun map(
        original: Map<String, JsonElement>,
        packageId: String,
        entryId: String,
        kind: String,
        location: String,
        field: String,
    ): Map<String, JsonElement> {
        fields[entryId]?.keys?.removeAll { it.startsWith("$field/") }
        return original.mapValues { (key, item) -> value(packageId, entryId, kind, location, "$field/$key", item) }
    }

    private fun entry(item: ProfileEntry, location: String): ProfileEntry {
        fields.remove(item.id)
        return item.copy(
            config = item.config?.let { value(item.packageId, item.id, item.configurationKind, location, "config", it) },
            inject = map(item.inject, item.packageId, item.id, item.configurationKind, location, "inject"),
            intercept = map(item.intercept, item.packageId, item.id, item.configurationKind, location, "intercept"),
            children = item.children?.mapIndexed { index, child -> entry(child, "$location/children/$index") },
        )
    }

    private fun rebuild() {
        val result = ProfileCompiler().compile(definition.copy(bundles = emptyList(), patches = applied.toList()), emptyList()).requireValid()
        val next = linkedMapOf<String, EntryOptions>()
        fun visit(items: List<EntryOptions>) {
            items.forEach { item ->
                next[item.id] = item
                if (item.group == true) visit((item.config as List<*>).map { it as EntryOptions })
            }
        }
        visit(result.entries)
        entries = next
        fields.keys.retainAll(next.keys)
    }

    fun operations(values: List<ProfileOperation>, layer: String): List<ProfileOperation> =
        values.mapIndexed { index, operation ->
            val location = "$layer/patches/$index"
            val output = when (operation) {
                is ProfileOperation.Insert -> operation.copy(
                    entries = operation.entries.mapIndexed { child, item -> entry(item, "$location/entries/$child") },
                )
                is ProfileOperation.Configure -> {
                    val target = entries.getValue(operation.target)
                    operation.copy(config = value(target.name, target.id, operation.configurationKind, location, "config", operation.config))
                }
                is ProfileOperation.Context -> {
                    val target = entries.getValue(operation.target)
                    val kind = target.extra["kcode.configurationKind"] as? String ?: "json"
                    operation.copy(
                        inject = operation.inject?.let { map(it, target.name, target.id, kind, location, "inject") },
                        intercept = operation.intercept?.let { map(it, target.name, target.id, kind, location, "intercept") },
                    )
                }
                is ProfileOperation.Replace -> {
                    fields[operation.target].orEmpty().values.forEach { inherited ->
                        val next = approved(inherited.source.copy(packageId = operation.packageId))
                        require(next.portable == inherited.portable) {
                            "Conflicting Profile export reviews for inherited configuration at ${next.source.location}/${next.source.field}"
                        }
                    }
                    operation
                }
                else -> operation
            }
            applied += operation
            rebuild()
            output
        }
}
