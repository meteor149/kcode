package ai.meteor.kcode.webcontainer

import android.content.Context
import android.net.Uri
import java.io.File

internal object WebWorkspace {
    private const val DIRECTORY_NAME = "agent_workspace"

    fun resolveEntry(context: Context, virtualPath: String): File {
        val relative = WebVirtualPath.relativeEntry(virtualPath)
        val file = resolveRelative(context, relative)
            ?: throw IllegalArgumentException("Web entry is outside /workspace")
        require(file.isFile) { "Web entry does not exist: $virtualPath" }
        require(file.extension.equals("html", ignoreCase = true) || file.extension.equals("htm", ignoreCase = true)) {
            "Web entry must be an .html or .htm file"
        }
        return file
    }

    fun resolveAsset(context: Context, encodedRelativePath: String): File? {
        val decoded = Uri.decode(encodedRelativePath)
        val normalized = normalizeRelativePath(decoded) ?: return null
        return resolveRelative(context, normalized)?.takeIf { it.isFile }
    }

    fun relativePath(context: Context, file: File): String =
        workspaceRoot(context).toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')

    fun previewUrl(relativePath: String): String =
        "https://$PREVIEW_DOMAIN/workspace/" + relativePath.split('/').joinToString("/") { Uri.encode(it) }

    private fun normalizeRelativePath(path: String): String? {
        if (path.isBlank() || path.startsWith('/') || '\\' in path || '\u0000' in path) return null
        val segments = path.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
        return segments.joinToString("/")
    }

    private fun resolveRelative(context: Context, relativePath: String): File? {
        val root = workspaceRoot(context)
        val candidate = File(root, relativePath).canonicalFile
        val rootPath = root.toPath()
        return candidate.takeIf { it.toPath().startsWith(rootPath) }
    }

    private fun workspaceRoot(context: Context): File =
        File(context.filesDir, DIRECTORY_NAME).canonicalFile

    const val PREVIEW_DOMAIN = "kcode-preview.local"
}
