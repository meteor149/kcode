package ai.meteor.kcode.plugin.managementui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class ManagerBundlePickerState {
    var pending: CompletableDeferred<List<Uri>>? = null
}

@Composable
internal actual fun rememberProfileManagerBundleFiles(): ProfileManagerBundleFiles {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val state = remember { ManagerBundlePickerState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        state.pending?.complete(uris)
        state.pending = null
    }
    DisposableEffect(state) {
        onDispose {
            state.pending?.cancel()
            state.pending = null
        }
    }
    return remember(resolver, picker) {
        object : ProfileManagerBundleFiles {
            override suspend fun withBundles(
                title: String,
                consume: suspend (List<ProfileManagerBundleFile>) -> Unit,
            ): Boolean {
                val uris = withContext(Dispatchers.Main.immediate) {
                    check(state.pending == null) { "Bundle picker is busy" }
                    val pending = CompletableDeferred<List<Uri>>()
                    state.pending = pending
                    try {
                        picker.launch(arrayOf("*/*"))
                    } catch (failure: Exception) {
                        if (state.pending === pending) state.pending = null
                        throw failure
                    }
                    try {
                        pending.await()
                    } finally {
                        pending.cancel()
                    }
                }
                if (uris.isEmpty()) return false
                require(uris.size <= 16) { "Select between one and sixteen Bundle archives" }
                val names = withContext(Dispatchers.IO) {
                    uris.mapIndexed { index, uri ->
                        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0)?.take(256) else null
                        }?.takeIf(String::isNotBlank) ?: "Bundle ${index + 1}"
                    }
                }
                stageManagerBundles(uris.mapIndexed { index, uri ->
                    names[index] to { requireNotNull(resolver.openInputStream(uri)) }
                }, consume)
                return true
            }
        }
    }
}
