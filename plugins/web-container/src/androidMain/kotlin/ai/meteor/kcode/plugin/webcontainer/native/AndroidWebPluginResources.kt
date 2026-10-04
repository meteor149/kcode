package ai.meteor.kcode.plugin.webcontainer.native

import ai.meteor.kcode.plugin.api.PluginCodeOrigin
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** External generations read their verified APK; built-ins borrow the host resource table. */
internal class AndroidWebPluginResources(host: Context, origin: PluginCodeOrigin?) : AutoCloseable {
    private val directory: File? = if (origin == null) null else
        Files.createTempDirectory(host.cacheDir.toPath(), "kcode-web-resources-").toFile()
    private val resources: Resources = try {
        if (origin == null) host.resources else {
            // ResourcesManager caches AssetManagers by APK path. A unique path establishes ownership.
            val copy = File(requireNotNull(directory), "resources.apk")
            File(origin.artifact.artifactPath).copyTo(copy)
            val digest = MessageDigest.getInstance("SHA-256")
            copy.inputStream().use { input ->
                val buffer = ByteArray(65_536)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
            val checksum = digest.digest().joinToString("") { "%02x".format(it) }
            require(checksum.equals(origin.artifact.sha256, ignoreCase = true)) { "Web resource APK checksum mismatch" }
            check(copy.setReadOnly()) { "Could not protect Web resource APK" }
            val archive = requireNotNull(host.packageManager.getPackageArchiveInfo(
                copy.path, PackageManager.PackageInfoFlags.of(0),
            )?.applicationInfo) { "Web plugin resources require a verified APK" }
            host.packageManager.getResourcesForApplication(ApplicationInfo(archive).apply {
                sourceDir = copy.path
                publicSourceDir = copy.path
            })
        }
    } catch (error: Throwable) {
        try { releaseDirectory() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
        throw error
    }

    fun forActivity(activity: Activity): Context = object : ContextWrapper(activity) {
        override fun getResources(): Resources = this@AndroidWebPluginResources.resources
        override fun getAssets(): AssetManager = this@AndroidWebPluginResources.resources.assets
        override fun getClassLoader(): ClassLoader = requireNotNull(AndroidWebPluginResources::class.java.classLoader)
    }

    override fun close() {
        if (directory == null) return
        try { resources.assets.close() } finally { releaseDirectory() }
    }

    private fun releaseDirectory() {
        directory?.listFiles()?.forEach { file ->
            file.setWritable(true)
            check(file.delete() || !file.exists()) { "Could not remove Web resource APK" }
        }
        directory?.let { check(it.delete() || !it.exists()) { "Could not remove Web resource directory" } }
    }
}
