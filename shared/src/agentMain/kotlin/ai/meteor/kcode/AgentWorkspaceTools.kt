package ai.meteor.kcode

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

interface AgentWorkspace {
    suspend fun readText(path: String): String
    suspend fun writeText(path: String, content: String)
    suspend fun list(path: String): List<AgentWorkspaceEntry>

    suspend fun canonicalize(path: String): String = path

}

data class AgentWorkspaceEntry(
    val path: String,
    val directory: Boolean,
    val size: Long,
)

class AgentReadFileTool(
    private val workspace: AgentWorkspace,
) : SimpleTool<AgentReadFileTool.Args>(
    argsType = typeToken<Args>(),
    name = "read_file",
    description = "Reads a UTF-8 text file inside the app-private /workspace directory.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Absolute file path inside /workspace")
        val path: String,
    )

    override suspend fun execute(args: Args): String = workspace.readText(args.path)
}

class AgentWriteFileTool(
    private val workspace: AgentWorkspace,
) : SimpleTool<AgentWriteFileTool.Args>(
    argsType = typeToken<Args>(),
    name = "write_file",
    description = "Creates or replaces a UTF-8 text file inside /workspace, including missing parent directories.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Absolute file path inside /workspace")
        val path: String,
        @property:LLMDescription("Complete UTF-8 text content to write")
        val content: String,
    )

    override suspend fun execute(args: Args): String {
        workspace.writeText(args.path, args.content)
        return "Written ${args.path} (${args.content.encodeToByteArray().size} bytes)"
    }
}

class AgentEditFileTool(
    private val workspace: AgentWorkspace,
) : SimpleTool<AgentEditFileTool.Args>(
    argsType = typeToken<Args>(),
    name = "edit_file",
    description = "Replaces an exact text fragment in a UTF-8 file inside /workspace.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Absolute file path inside /workspace")
        val path: String,
        @property:LLMDescription("Exact existing text to replace")
        val oldText: String,
        @property:LLMDescription("Replacement text")
        val newText: String,
        @property:LLMDescription("Replace every occurrence instead of requiring exactly one")
        val replaceAll: Boolean = false,
    )

    override suspend fun execute(args: Args): String {
        require(args.oldText.isNotEmpty()) { "oldText must not be empty" }
        val original = workspace.readText(args.path)
        val occurrences = original.countOccurrences(args.oldText)
        require(occurrences > 0) { "oldText was not found in ${args.path}" }
        require(args.replaceAll || occurrences == 1) {
            "oldText occurs $occurrences times; provide more context or enable replaceAll"
        }
        val updated = if (args.replaceAll) {
            original.replace(args.oldText, args.newText)
        } else {
            original.replaceFirst(args.oldText, args.newText)
        }
        workspace.writeText(args.path, updated)
        return "Edited ${args.path}"
    }
}

class AgentListDirectoryTool(
    private val workspace: AgentWorkspace,
) : SimpleTool<AgentListDirectoryTool.Args>(
    argsType = typeToken<Args>(),
    name = "list_directory",
    description = "Lists immediate files and directories inside an app-private /workspace path.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Absolute directory path inside /workspace")
        val path: String = "/workspace",
    )

    override suspend fun execute(args: Args): String {
        val entries = workspace.list(args.path)
        return if (entries.isEmpty()) {
            "${args.path} is empty"
        } else {
            entries.joinToString("\n") { entry ->
                if (entry.directory) "[dir]  ${entry.path}" else "[file] ${entry.path} (${entry.size} bytes)"
            }
        }
    }
}

private fun String.countOccurrences(fragment: String): Int {
    var count = 0
    var start = 0
    while (true) {
        val index = indexOf(fragment, start)
        if (index < 0) return count
        count++
        start = index + fragment.length
    }
}
