package app.kcode

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import app.kcode.h5.H5ContainerLauncher
import app.kcode.h5.H5PreviewRequest
import kotlinx.serialization.Serializable

class H5PreviewTool(
    private val launcher: H5ContainerLauncher,
) : SimpleTool<H5PreviewTool.Args>(
    argsType = typeToken<Args>(),
    name = "preview_h5_app",
    description = """
        Opens and runs a browser-ready H5 application created in the platform's /workspace directory.
        Write the HTML, CSS, JavaScript, images, and other assets first, then call this tool with the HTML entry path.
        Relative resources, JavaScript, ES modules, and browser APIs are supported. The preview cannot access files outside /workspace.
        Use this tool instead of starting a local HTTP server yourself.
    """.trimIndent(),
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Absolute HTML entry path inside /workspace, for example /workspace/my-app/index.html")
        val entryPath: String = "/workspace/index.html",
        @property:LLMDescription("Short title shown by the preview container")
        val title: String = "H5 Preview",
    )

    override suspend fun execute(args: Args): String {
        val result = launcher.launch(
            H5PreviewRequest(
                entryPath = args.entryPath,
                title = args.title.trim().take(MAX_TITLE_LENGTH).ifBlank { "H5 Preview" },
            ),
        )
        return buildString {
            append("H5 preview opened: ").append(result.entryPath)
            append("\nentrySize=").append(result.entrySize).append(" bytes")
            append("\npresentation=").append(result.presentation)
        }
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 80
    }
}
