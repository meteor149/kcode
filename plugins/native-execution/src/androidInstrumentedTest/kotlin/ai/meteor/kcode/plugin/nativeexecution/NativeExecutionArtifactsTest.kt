package ai.meteor.kcode.plugin.nativeexecution

import ai.meteor.kcode.plugin.api.PluginCodeArtifact
import ai.meteor.kcode.plugin.api.PluginCodeOrigin
import ai.meteor.kcode.settings.ShellExecutionMode
import android.content.ContextWrapper
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeExecutionArtifactsTest {
    @Test(timeout = 180_000)
    fun importedApkUbuntuRunsCompleteCommandsAndClosesItsResources(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val apk = File(instrumentation.context.applicationInfo.sourceDir)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val origin = PluginCodeOrigin(PluginCodeArtifact("test-native", "1", apk.path,
            digest.digest().joinToString("") { "%02x".format(it) }, AndroidNativeSettingsUbuntuShellPlugin::class.java.name))
        val host = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getApplicationInfo(): android.content.pm.ApplicationInfo = instrumentation.context.applicationInfo
        }
        AndroidUbuntuShellExecutor(host, { ShellExecutionMode.App }, origin).use { executor ->
            val result = withTimeout(120_000) { executor.execute(
                "set -eu; . /etc/os-release; printf 'ubuntu=%s\\n' \"\$PRETTY_NAME\"; " +
                    "apt --version; python3 -c 'print(\"imported-python-ok\")'; id -u",
                "/workspace",
            ) }
            assertEquals(0, result.exitCode, result.output)
            assertTrue(result.output.contains("Ubuntu 24.04"), result.output)
            assertTrue(result.output.contains("imported-python-ok"), result.output)
        }
    }

    @Test(timeout = 30_000)
    fun importedLoaderRunsThroughTheGenericNativeBootstrap(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val files = NativeExecutionArtifacts(context, listOf(File(instrumentation.context.applicationInfo.sourceDir)))
        try {
            val loader = files.executable("libkcode_proot_loader.so")
            val bootstrap = File(instrumentation.context.applicationInfo.nativeLibraryDir, "libkcode_native_image.so").toPath()
            val result = withTimeout(10_000) { executeAndroidProcess {
                startAndroidProcessGroup(ProcessBuilder("/system/bin/linker64", files.executable("libkcode_proot.so").toString(),
                    "-r", "/", "-b", "$loader:/.__kcode_native_image/image.elf", "-w", "/", "/system/bin/sh", "-c", "printf 'artifact-uid='; id -u")
                    .redirectErrorStream(true).apply {
                        environment()["PROOT_LOADER"] = bootstrap.toString()
                        environment()["PROOT_TMP_DIR"] = files.directory.toString()
                    })
            } }
            assertEquals(0, result.exitCode, result.output)
            assertTrue(result.output.contains("artifact-uid=${Process.myUid()}"), result.output)
        } finally {
            files.close()
        }
    }

    @Test(timeout = 30_000)
    fun mountsWaitForThePersistentInstallLockAndOwnTheirTemporaryDirectories(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val parent = Files.createTempDirectory(instrumentation.targetContext.cacheDir.toPath(), "ubuntu-mount-test-")
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir(): File = parent.toFile()
            override fun getApplicationInfo(): android.content.pm.ApplicationInfo =
                error("Imported Ubuntu must not inspect the host native library directory")
        }
        val apk = listOf(File(instrumentation.context.applicationInfo.sourceDir))
        val a = NativeExecutionArtifacts(context, apk)
        val b = NativeExecutionArtifacts(context, apk)
        val first = AndroidUbuntuEnvironment(context, a)
        val second = AndroidUbuntuEnvironment(context, b)
        val runtime = parent.resolve("ubuntu_runtime")
        val rootfs = runtime.resolve("rootfs")
        // A completed persisted installation must survive both mounts' retirement.
        listOf("bin/bash", "usr/bin/env", "usr/bin/apt", "usr/bin/python3", "etc/os-release").forEach { name ->
            val file = rootfs.resolve(name)
            Files.createDirectories(file.parent)
            Files.write(file, "persisted-test-fixture".encodeToByteArray())
        }
        Files.write(rootfs.resolve(".kcode_ubuntu_installed"), "ubuntu-noble-operit-pd-v4.18.0-kcode-1\n".encodeToByteArray())
        try {
            val firstPaths = withTimeout(10_000) {
                FileChannel.open(runtime.resolve(".install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                    val lock = channel.lock()
                    try {
                        val pending = async { first.ensureInstalled() }
                        delay(150)
                        assertFalse(pending.isCompleted, "An independent installer still owns the file lock")
                        lock.release()
                        pending.await()
                    } finally {
                        if (lock.isValid) lock.release()
                    }
                }
            }
            val secondPaths = second.ensureInstalled()
            assertEquals(firstPaths.rootFileSystem, secondPaths.rootFileSystem)
            assertTrue(firstPaths.temporaryDirectory != secondPaths.temporaryDirectory)
            first.close()
            assertFalse(Files.exists(firstPaths.temporaryDirectory))
            assertTrue(Files.exists(secondPaths.temporaryDirectory))
            assertTrue(Files.exists(rootfs.resolve("bin/bash")))
            assertFailsWith<IllegalStateException> { first.ensureInstalled() }
            second.close()
            assertFalse(Files.exists(secondPaths.temporaryDirectory))
            assertTrue(Files.exists(rootfs.resolve("bin/bash")))
        } finally {
            try { first.close() } finally {
                try { second.close() } finally {
                    try { a.close() } finally { b.close(); parent.toFile().deleteRecursively() }
                }
            }
        }
    }

    @Test
    fun deploymentGraphHasIndependentStreamsAndRejectsHostFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = File.createTempFile("native-source-a", ".apk", context.cacheDir)
        val dependency = File.createTempFile("native-source-b", ".apk", context.cacheDir)
        fun asset(file: File, name: String, contents: String) {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("assets/$name")); zip.write(contents.toByteArray()); zip.closeEntry()
            }
        }
        asset(first, "private-source", "generation-a")
        asset(dependency, "dependency-source", "generation-b")
        val a = NativeExecutionArtifacts(context, listOf(first, dependency))
        val b = NativeExecutionArtifacts(context, listOf(first, dependency))
        try {
            assertEquals("generation-a", a.openAsset("private-source").bufferedReader().use { it.readText() })
            assertEquals("generation-b", b.openAsset("dependency-source").bufferedReader().use { it.readText() })
            assertFailsWith<IllegalStateException> { a.openAsset("ubuntu-noble-aarch64-pd-v4.18.0.tar.xz") }
            assertFailsWith<IllegalStateException> { a.executable("libkcode_proot.so") }
            val pending = a.openAsset("private-source")
            a.close()
            assertFalse(a.directory.toFile().exists())
            assertFailsWith<IOException> { pending.read() }
            assertFailsWith<IllegalStateException> { a.openAsset("private-source") }
            assertEquals("generation-a", b.openAsset("private-source").bufferedReader().use { it.readText() })
            assertTrue(b.directory.toFile().isDirectory)
        } finally {
            try { a.close() } finally { b.close(); first.delete(); dependency.delete() }
        }
    }

    @Test(timeout = 30_000)
    fun actualApkProotStartsThroughThePlatformLinker(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val files = NativeExecutionArtifacts(context, listOf(File(instrumentation.context.applicationInfo.sourceDir)))
        try {
            val proot = files.executable("libkcode_proot.so")
            val result = withTimeout(10_000) { executeAndroidProcess {
                startAndroidProcessGroup(ProcessBuilder("/system/bin/linker64", proot.toString(), "--version")
                    .redirectErrorStream(true).apply {
                        environment()["PROOT_TMP_DIR"] = files.directory.toString()
                    })
            } }
            assertEquals(0, result.exitCode, result.output)
            assertTrue(result.output.contains("proot", ignoreCase = true), result.output)
        } finally { files.close() }
        assertFalse(files.directory.toFile().exists())
    }
}
