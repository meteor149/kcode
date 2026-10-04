package ai.meteor.kcode.plugin

import ai.meteor.kcode.export.ConversationImageSaver
import ai.meteor.kcode.export.ImageSaveResult
import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.export.AndroidNativeImageSavingPlugin
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeConversationImageSaving
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.tools.permission.ToolCallApprover
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidNativeImageSavingTest {
    @Test(timeout = 60_000)
    fun privateApkSaverPublishesImageAndRebindsExportOnReload(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "native-saving-${System.nanoTime()}").apply { mkdirs() }
        val apk = File(directory, "native-saving.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(apk)
        check(apk.setReadOnly())
        val name = "kcode-native-saving-${System.nanoTime()}.png"
        lateinit var current: ConversationImageSaver
        val capture = kcodePlugin(PluginDescriptor("test.capture-saving", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-native-saving", inject = dependencies(KcodeConversationImageSaving.Key),
        ) { ctx, _ -> current = ctx.require(KcodeConversationImageSaving.Key).saver }, Unit)
        val handoffs = mutableListOf<android.content.Intent>()
        var rejectHandoff = false
        val activity = withContext(Dispatchers.Main.immediate) {
            object : android.app.Activity() {
                override fun getApplicationContext(): android.content.Context = context
                override fun startActivity(intent: android.content.Intent) {
                    check(android.os.Looper.myLooper() === android.os.Looper.getMainLooper())
                    if (rejectHandoff) error("test handoff rejected")
                    handoffs += intent
                }
            }
        }
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            hostInputs = AndroidPluginHostInputs(activity),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val image = bitmap.asImageBitmap()
        try {
            runtime.pluginManager.replace(DynamicPluginSpec(
                id = "provider.export.image-saving", version = "test",
                entryClass = AndroidNativeImageSavingPlugin::class.java.name,
                artifactPath = apk.path,
                sha256 = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) },
                packageName = instrumentation.context.packageName,
            ))
            assertTrue(current.javaClass.classLoader !== ConversationImageSaver::class.java.classLoader)
            val previous = current
            assertEquals(ImageSaveResult.Saved("Pictures/kcode/$name"), previous.save(image, name))
            val resolver = context.contentResolver
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.IS_PENDING),
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(name), null).use { cursor ->
                checkNotNull(cursor)
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(1))
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                resolver.openInputStream(uri).use { input ->
                    val decoded = android.graphics.BitmapFactory.decodeStream(checkNotNull(input))
                    assertEquals(2, decoded.width)
                    assertEquals(3, decoded.height)
                    assertEquals(android.graphics.Color.RED, decoded.getPixel(0, 0))
                    decoded.recycle()
                }
            }
            runtime.pluginManager.setEnabled("provider.export.image-saving", false)
            assertFailsWith<IllegalStateException> { previous.save(image, name) }
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "provider.export.conversation" }.state)
            runtime.pluginManager.setEnabled("provider.export.image-saving", true)
            assertNotSame(previous, current)
            assertEquals(PluginState.Active, runtime.diagnostics().plugins.first { it.id == "provider.export.conversation" }.state)
            assertEquals(ImageSaveResult.Shared, current.share(image, name))
            val firstSend = handoffs.last().getParcelableExtra(android.content.Intent.EXTRA_INTENT, android.content.Intent::class.java)!!
            val firstUri = firstSend.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)!!
            assertEquals(firstUri, firstSend.clipData!!.getItemAt(0).uri)
            assertTrue(firstSend.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(ImageSaveResult.Shared, current.share(image, name))
            val secondSend = handoffs.last().getParcelableExtra(android.content.Intent.EXTRA_INTENT, android.content.Intent::class.java)!!
            val secondUri = secondSend.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)!!
            assertTrue(firstUri != secondUri)
            for (uri in listOf(firstUri, secondUri)) {
                context.contentResolver.openInputStream(uri).use { input ->
                    val decoded = android.graphics.BitmapFactory.decodeStream(checkNotNull(input))
                    assertEquals(android.graphics.Color.RED, decoded.getPixel(0, 0))
                    decoded.recycle()
                }
            }
            val shareRoot = File(context.cacheDir, "shared_images")
            val beforeFailure = shareRoot.listFiles()!!.map { it.name }.toSet()
            rejectHandoff = true
            assertTrue(current.share(image, name) is ImageSaveResult.Failed)
            assertEquals(beforeFailure, shareRoot.listFiles()!!.map { it.name }.toSet())
            assertTrue(current.share(image, "../escape.png") is ImageSaveResult.Failed)
            assertEquals(beforeFailure, shareRoot.listFiles()!!.map { it.name }.toSet())
            val beforeUninstall = current
            runtime.pluginManager.uninstall("provider.export.image-saving")
            assertFailsWith<IllegalStateException> { beforeUninstall.save(image, name) }
            assertFailsWith<IllegalStateException> { beforeUninstall.share(image, name) }
            // Committed handoff files remain readable after the provider is removed.
            context.contentResolver.openInputStream(firstUri).use { assertTrue(checkNotNull(it).read() >= 0) }
        } finally {
            runtime.close()
            context.contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(name))
            File(context.cacheDir, "shared_images").listFiles()?.filter { File(it, name).exists() }?.forEach { it.deleteRecursively() }
            bitmap.recycle()
            apk.setWritable(true)
            directory.deleteRecursively()
        }
    }
}
