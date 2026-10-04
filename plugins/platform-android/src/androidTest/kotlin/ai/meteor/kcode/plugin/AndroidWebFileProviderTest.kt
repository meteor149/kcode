package ai.meteor.kcode.plugin

import ai.meteor.kcode.webcontainer.WebFileProvider
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidWebFileProviderTest {
    @Test
    fun installedHostSharesWorkspaceFilesAndRejectsOutsidePaths() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = "${context.packageName}.web.fileprovider"
        @Suppress("DEPRECATION")
        val provider = requireNotNull(context.packageManager.resolveContentProvider(authority, 0))
        assertEquals(WebFileProvider::class.java.name, provider.name)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)

        val workspace = File(context.filesDir, "agent_workspace").apply { mkdirs() }
        val shared = File.createTempFile("web-share-", ".txt", workspace)
        val outside = File.createTempFile("web-outside-", ".txt", context.cacheDir)
        try {
            shared.writeText("workspace sharing")
            val uri = FileProvider.getUriForFile(context, authority, shared)
            assertEquals(authority, uri.authority)
            val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "r"))
            val text = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
                it.readBytes().decodeToString()
            }
            assertEquals("workspace sharing", text)
            assertFailsWith<IllegalArgumentException> {
                FileProvider.getUriForFile(context, authority, outside)
            }
        } finally {
            shared.delete()
            outside.delete()
        }
    }
}
