package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import kotlinx.serialization.Serializable

@Serializable
data class LockedProfilePackage(
    val id: String,
    val version: String,
    val archiveSha256: String,
    val variantId: String,
    val runtimeAbi: String,
    val dependencies: Map<String, String> = emptyMap(),
)

@Serializable
data class ProfileLock(
    val formatVersion: Int = 1,
    val packages: List<LockedProfilePackage> = emptyList(),
) {
    fun validate() {
        require(formatVersion == 1) { "Unsupported profile lock format" }
        require(packages.map { it.id }.distinct().size == packages.size) { "Duplicate locked package" }
        val byId = packages.associateBy { it.id }
        packages.forEach { item ->
            require(item.id.isNotBlank() && item.version.isNotBlank() && item.variantId.isNotBlank()) { "Incomplete package lock" }
            require(item.archiveSha256.matches(Regex("[a-f0-9]{64}")) && item.runtimeAbi.matches(Regex("[a-f0-9]{64}"))) { "Invalid locked package digest" }
            require(item.id !in item.dependencies) { "Self-dependent locked package" }
            item.dependencies.forEach { (id, version) ->
                require(byId[id]?.version == version) { "Missing or conflicting locked dependency '$id@$version'" }
            }
        }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in visited) return
            require(visiting.add(id)) { "Cyclic locked dependencies" }
            byId.getValue(id).dependencies.keys.forEach(::visit)
            visiting.remove(id)
            visited += id
        }
        byId.keys.forEach(::visit)
    }
}

/** One atomic durable publication, separate from the editable draft. Paths stay local. */
@Serializable
data class CommittedProfileGeneration(
    val formatVersion: Int = 1,
    val generation: Long,
    val definition: ProfileDefinition,
    val lock: ProfileLock,
    val composition: PluginCompositionSnapshot,
) {
    fun validate(restoring: Boolean = false) {
        require(formatVersion == 1 && generation > 0) { "Invalid profile generation" }
        definition.validate()
        lock.validate()
        if (restoring) composition.validateForRestore() else composition.validate()
        val locked = lock.packages.associateBy { it.id }
        val packaged = composition.external.filter { it.packageInstallation != null }
        require(packaged.map { it.id }.toSet() == locked.keys) { "Package lock does not match composition" }
        packaged.forEach { item ->
            val installation = checkNotNull(item.packageInstallation)
            val release = locked.getValue(item.id)
            require(item.version == release.version && installation.archiveSha256 == release.archiveSha256 &&
                installation.variantId == release.variantId && installation.runtimeAbi == release.runtimeAbi &&
                installation.dependencies == release.dependencies) { "Package lock identity mismatch for '${item.id}'" }
        }
    }
}

interface ProfileRepository {
    suspend fun list(): List<String>
    suspend fun loadDraft(id: String): ProfileDefinition?
    suspend fun saveDraft(definition: ProfileDefinition)
    suspend fun loadCommitted(id: String): CommittedProfileGeneration?
    /** Compare-and-set publication. Failure must leave the previous generation readable. */
    suspend fun commit(value: CommittedProfileGeneration, expectedGeneration: Long?)
    suspend fun selected(): String?
    /** Selection references a successfully committed profile, never an unactivated draft. */
    suspend fun select(id: String?)
    suspend fun remove(id: String)
}
