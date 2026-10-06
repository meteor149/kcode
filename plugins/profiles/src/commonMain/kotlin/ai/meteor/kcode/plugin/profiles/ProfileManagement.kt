package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileCatalogue
import ai.meteor.kcode.plugin.api.profiles.ProfileCloneRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileCompositionState
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileDiagnostic
import ai.meteor.kcode.plugin.api.profiles.ProfileDraftWrite
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOrigin
import ai.meteor.kcode.plugin.api.profiles.ProfilePreview
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileDataScope
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.cordis.loader.EntryOptions
import org.cordis.loader.IsolationRule
import org.cordis.include.CompositionDiagnostic
import org.cordis.include.CompositionResult

/** Metadata commands never mount providers. Activation stays with the native runtime owner. */
class ProfileManagement private constructor(
    private val repository: ProfileGenerationRepository,
    private val bundles: () -> List<ProfileBundle>,
    private val prepare: suspend (ProfileActivationRequest) -> ProfileActivation,
    private val exportReview: ProfileExportReview,
) {
    constructor(
        repository: ProfileGenerationRepository,
        bundles: () -> List<ProfileBundle>,
        prepare: suspend (ProfileActivationRequest) -> ProfileActivation,
    ) : this(repository, bundles, prepare, ProfileExportReview { null })

    constructor(
        repository: ProfileGenerationRepository,
        bundles: () -> List<ProfileBundle>,
        exportReview: ProfileExportReview,
        prepare: suspend (ProfileActivationRequest) -> ProfileActivation,
    ) : this(repository, bundles, prepare, exportReview)

    suspend fun catalogue(): ProfileCatalogue = repository.catalogue()
    suspend fun draft(id: String): ProfileDefinition? = repository.loadDraft(id)

    suspend fun write(write: ProfileDraftWrite): ProfileCatalogue {
        val detached = Json.decodeFromString(ProfileDefinition.serializer(), Json.encodeToString(ProfileDefinition.serializer(), write.definition))
        detached.validate()
        val base = repository.loadCommitted(detached.id) ?: repository.loadDraftDocument(detached.id)?.base
        val imported = repository.loadDraftDocument(detached.id)?.imported.takeIf { base == null }
        repository.writeDraft(ProfileDraftDocument(detached, base, imported = imported), write.expectedRevision, write.createOnly)
        return catalogue()
    }

    suspend fun clone(request: ProfileCloneRequest): ProfileCatalogue {
        val intent = loadProfileIntent(repository, request.source)
        val definition = intent.definition.copy(id = request.id, displayName = request.displayName, dataScope = request.dataScope)
        repository.writeDraft(ProfileDraftDocument(definition, intent.base, imported = intent.imported), request.expectedRevision, createOnly = true)
        return catalogue()
    }

    /** Creates metadata only; code is verified by ordinary explicit preview/activation preparation. */
    suspend fun importPortable(text: String, id: String, displayName: String, expectedRevision: Long): ProfileCatalogue {
        val imported = ProfilePortableExporter.decode(text)
        val definition = imported.definition.copy(
            id = id, displayName = displayName, dataScope = ProfileDataScope(workspace = "profile"),
        )
        repository.writeDraft(ProfileDraftDocument(definition, imported = imported), expectedRevision, createOnly = true)
        return catalogue()
    }

    suspend fun exportPortable(
        target: ProfileTarget,
        review: ProfileExportReview = exportReview,
        expectedRevision: Long? = null,
    ): String {
        require(target.source != ProfileSource.Draft) { "Activate a draft before exporting its verified package recipe" }
        val revision = repository.state().revision
        require(expectedRevision == null || expectedRevision == revision) { "Profile repository changed; refresh before exporting" }
        val source = requireNotNull(loadProfileIntent(repository, target).base)
        val text = ProfilePortableExporter(review).export(source)
        check(repository.state().revision == revision) { "Profile repository changed; refresh before exporting" }
        return text
    }

    suspend fun remove(id: String, expectedRevision: Long): ProfileCatalogue {
        repository.remove(id, expectedRevision)
        return catalogue()
    }

    suspend fun history(id: String): List<ProfileCompositionState> {
        val before = repository.state().revision
        val history = repository.generations(id).map { generation ->
            val document = checkNotNull(repository.loadGeneration(id, generation)) { "Historical generation disappeared" }
            ProfileCompositionState(document.definition, document.generation)
        }
        check(repository.state().revision == before) { "Profile repository changed; refresh history" }
        return history
    }

    suspend fun preview(target: ProfileTarget): ProfilePreview {
        val revision = repository.state().revision
        val intent = loadProfileIntent(repository, target)
        var result = try {
            ProfileCompiler().compile(intent.definition, profileIntentBundles(intent, bundles()))
        } catch (error: IllegalArgumentException) {
            CompositionResult(emptyList(), listOf(CompositionDiagnostic("composition", -1, target.profileId,
                error.message ?: "Profile compilation failed")), emptyMap())
        }
        var verified = false
        var preparationFailure: ProfileDiagnostic? = null
        if (result.diagnostics.isEmpty()) {
            var activation: ProfileActivation? = null
            try {
                activation = prepare(ProfileActivationRequest(target, revision))
                result = activation.resolved.composition
                verified = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                preparationFailure = ProfileDiagnostic("preparation", -1, null, error.message ?: "Profile preparation failed")
            } finally {
                activation?.session?.discardPreparedSwitch()
            }
        }
        check(repository.state().revision == revision) { "Profile repository changed; refresh preview" }
        return ProfilePreview(revision, intent.definition, result.entries.map(::entry),
            result.diagnostics.map { ProfileDiagnostic(it.layer, it.operation, it.target, it.message) } + listOfNotNull(preparationFailure),
            result.origins.mapValues { (_, fields) -> fields.mapValues { (_, source) -> ProfileOrigin(source.layer, source.operation) } }, verified)
    }

    private fun entry(value: EntryOptions): ProfileEntry = ProfileEntry(
        id = value.id,
        packageId = value.name,
        config = if (value.group == true) null else value.config as? JsonElement,
        enabled = value.disabled != true,
        children = if (value.group == true) (value.config as List<*>).map { entry(it as EntryOptions) } else null,
        configurationKind = value.extra["kcode.configurationKind"] as? String ?: "json",
        inject = value.inject.orEmpty().mapValues { (_, item) -> json(item) },
        intercept = value.intercept.orEmpty().mapValues { (_, item) -> json(item) },
        isolate = value.isolate.orEmpty().mapValues { (_, rule) -> when (rule) {
            IsolationRule.Local -> null
            is IsolationRule.Shared -> rule.realm
        } },
    )

    private fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to json(it.value) })
        is List<*> -> JsonArray(value.map(::json))
        else -> error("Profile context value is not portable data")
    }
}
