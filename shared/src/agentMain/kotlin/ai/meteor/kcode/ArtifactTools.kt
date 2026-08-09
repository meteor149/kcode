package ai.meteor.kcode

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistryBuilder
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import ai.meteor.kcode.artifact.MutableArtifactRepository
import ai.meteor.kcode.artifact.SaveWebArtifactRequest
import kotlinx.serialization.Serializable

class SaveWebArtifactTool(
    private val repository: MutableArtifactRepository,
) : SimpleTool<SaveWebArtifactTool.Args>(
    argsType = typeToken<Args>(),
    name = "save_web_artifact",
    description = "Copies a completed /workspace Web app into managed artifact storage and updates its manifest. Use only after explicit user confirmation.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Stable lowercase artifact id using letters, digits, dots, underscores, or hyphens")
        val id: String,
        @property:LLMDescription("User-facing artifact name")
        val name: String,
        @property:LLMDescription("Absolute source project directory inside /workspace")
        val sourceDirectory: String,
        @property:LLMDescription("HTML entry path relative to sourceDirectory")
        val entryPoint: String = "index.html",
        @property:LLMDescription("Short user-facing description")
        val description: String = "",
        @property:LLMDescription("Must be true only when the user explicitly agreed to save this artifact")
        val userConfirmed: Boolean,
    )

    override suspend fun execute(args: Args): String {
        require(args.userConfirmed) { "Explicit user confirmation is required before saving an artifact" }
        val artifact = repository.saveWebApp(
            SaveWebArtifactRequest(
                id = args.id,
                name = args.name,
                sourceDirectory = args.sourceDirectory,
                entryPoint = args.entryPoint,
                description = args.description,
            ),
        )
        return "Saved artifact ${artifact.id}: ${artifact.name} (${artifact.entryPath})"
    }
}

fun ToolRegistryBuilder.artifactTools(repository: MutableArtifactRepository) {
    tool(SaveWebArtifactTool(repository))
}
