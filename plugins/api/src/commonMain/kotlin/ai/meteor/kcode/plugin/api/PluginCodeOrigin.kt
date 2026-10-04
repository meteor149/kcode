package ai.meteor.kcode.plugin.api

import org.cordis.Context
import org.cordis.InterceptKey

/** Loader-verified immutable deployment metadata; this is not an authorization token. */
data class PluginCodeArtifact(
    val id: String,
    val version: String,
    val artifactPath: String,
    val sha256: String,
    val entryClass: String,
    val packageName: String? = null,
)

/** Captured for an imported generation, including its ordered transitive code dependencies. */
class PluginCodeOrigin(
    val artifact: PluginCodeArtifact,
    dependencies: List<PluginCodeArtifact> = emptyList(),
) {
    val dependencies = dependencies.toList()

    fun bind(context: Context): Context = context.intercept(Key, this)

    companion object {
        private val Key = InterceptKey<PluginCodeOrigin>("kcode.plugin.code-origin")

        fun current(context: Context): PluginCodeOrigin? = context.interceptValues(Key).lastOrNull()
    }
}
