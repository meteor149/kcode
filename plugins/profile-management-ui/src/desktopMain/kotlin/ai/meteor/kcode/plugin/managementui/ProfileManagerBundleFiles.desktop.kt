package ai.meteor.kcode.plugin.managementui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.nio.file.Path
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
internal actual fun rememberProfileManagerBundleFiles(): ProfileManagerBundleFiles = remember {
    object : ProfileManagerBundleFiles {
        override suspend fun withBundles(
            title: String,
            singlePlugin: Boolean,
            consume: suspend (List<ProfileManagerBundleFile>) -> Unit,
        ): Boolean {
            val paths = chooseBundleFiles(title, singlePlugin)
            if (paths.isEmpty()) return false
            require(!singlePlugin || paths.size == 1 && paths.single().fileName.toString().endsWith(".kplugin", ignoreCase = true)) {
                "Select one .kplugin file"
            }
            stageManagerBundles(paths.map(::selectedPath), consume)
            return true
        }
    }
}

private suspend fun chooseBundleFiles(title: String, singlePlugin: Boolean): List<Path> = suspendCancellableCoroutine { continuation ->
    var dialog: FileDialog? = null
    continuation.invokeOnCancellation { EventQueue.invokeLater { dialog?.dispose() } }
    EventQueue.invokeLater {
        if (!continuation.isActive) return@invokeLater
        try {
            val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow as? Frame
            val picker = FileDialog(owner, title, FileDialog.LOAD)
            dialog = picker
            picker.isMultipleMode = !singlePlugin
            if (singlePlugin) picker.setFilenameFilter { _, name -> name.endsWith(".kplugin", ignoreCase = true) }
            try {
                picker.isVisible = true
                val result = picker.files.map { it.toPath().toAbsolutePath() }
                if (continuation.isActive) continuation.resume(result)
            } finally {
                picker.dispose()
            }
        } catch (failure: Exception) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }
}
