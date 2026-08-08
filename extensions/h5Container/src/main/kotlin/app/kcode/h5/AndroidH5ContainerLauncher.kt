package app.kcode.h5

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidH5ContainerLauncher(context: Context) : H5ContainerLauncher {
    private val appContext = context.applicationContext

    override suspend fun launch(request: H5PreviewRequest): H5PreviewResult {
        val entry = H5Workspace.resolveEntry(appContext, request.entryPath)
        withContext(Dispatchers.Main.immediate) {
            appContext.startActivity(
                Intent(appContext, H5ContainerActivity::class.java)
                    .putExtra(H5ContainerActivity.EXTRA_ENTRY_PATH, request.entryPath)
                    .putExtra(H5ContainerActivity.EXTRA_TITLE, request.title)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        return H5PreviewResult(request.entryPath, entry.length(), "android-webview")
    }
}
