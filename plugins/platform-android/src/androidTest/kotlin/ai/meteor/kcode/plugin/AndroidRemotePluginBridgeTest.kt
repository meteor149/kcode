package ai.meteor.kcode.plugin

import org.cordis.packages.packageFileSha256

import ai.meteor.kcode.plugin.nativeexecution.shell.PrivilegedShellUserService
import ai.meteor.kcode.shell.IPrivilegedPluginBridge
import ai.meteor.kcode.shell.IPrivilegedShellService
import ai.meteor.kcode.shell.PrivilegedPluginEngine
import ai.meteor.kcode.shell.PrivilegedPluginUserService
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidRemotePluginBridgeTest {
    @Test(timeout = 90_000)
    fun binderDeploymentLoadsPrivateGenerationsAndReleasesOwnedFiles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "remote-bridge-${System.nanoTime()}").apply { mkdirs() }
        val apk = File(directory, "plugin.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(apk)
        check(apk.setReadOnly())
        val deployments = File(directory, "deployments").apply { mkdirs() }
        val digest = packageFileSha256(apk)
        fun proxy(bridge: PrivilegedPluginUserService): IPrivilegedPluginBridge {
            val forwarding = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                    bridge.transact(code, data, reply, flags)
            }
            return IPrivilegedPluginBridge.Stub.asInterface(forwarding)
        }
        fun load(service: IPrivilegedPluginBridge, entry: String, hash: String = digest): IPrivilegedShellService =
            ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                IPrivilegedShellService.Stub.asInterface(service.load(arrayOf(fd), arrayOf(hash), entry))
            }
        fun output(engine: IPrivilegedShellService): String =
            engine.execute("id", "command", "").use { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes().decodeToString() } }
        val first = PrivilegedPluginUserService(context, deployments)
        val second = PrivilegedPluginUserService(context, deployments)
        val rejected = PrivilegedPluginUserService(context, deployments)
        try {
            val a = load(proxy(first), AndroidRemoteEngineV1::class.java.name)
            val b = load(proxy(second), AndroidRemoteEngineV2::class.java.name)
            assertEquals("v1:${Process.myUid()}", output(a))
            assertEquals("v2:${Process.myUid()}", output(b))
            assertTrue(a.javaClass.classLoader !== IPrivilegedShellService::class.java.classLoader)
            assertTrue(b.javaClass.classLoader !== a.javaClass.classLoader)
            assertEquals(2, deployments.listFiles()!!.size)
            assertFailsWith<IllegalStateException> { load(proxy(first), AndroidRemoteEngineV2::class.java.name) }
            assertFailsWith<IllegalArgumentException> { load(proxy(rejected), AndroidRemoteEngineV1::class.java.name, "0".repeat(64)) }
            assertEquals(2, deployments.listFiles()!!.size)
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                assertFailsWith<IllegalArgumentException> {
                    proxy(rejected).load(arrayOf(pipe[0]), arrayOf(digest), AndroidRemoteEngineV1::class.java.name)
                }
            } finally { pipe.forEach { it.close() } }
            assertEquals(2, deployments.listFiles()!!.size)
            assertFailsWith<IllegalArgumentException> { load(proxy(rejected), PrivilegedPluginUserService::class.java.name) }
            first.close()
            assertEquals(1, deployments.listFiles()!!.size)
            assertFailsWith<IllegalStateException> { output(a) }
            assertEquals("v2:${Process.myUid()}", output(b))
            assertFailsWith<IllegalStateException> { load(proxy(first), AndroidRemoteEngineV1::class.java.name) }
            first.close()
            second.close()
            assertTrue(deployments.listFiles()!!.isEmpty())
            assertFalse(File(context.cacheDir, "remote-close-v1").readText().isEmpty())
            assertFalse(File(context.cacheDir, "remote-close-v2").readText().isEmpty())
            // Actual product engine also loads privately and executes a real system process.
            val real = load(proxy(rejected), PrivilegedShellUserService::class.java.name)
            assertTrue(real.javaClass.classLoader !== PrivilegedShellUserService::class.java.classLoader)
            real.beginRequest("actual")
            val actualOutput = real.execute("actual", "printf remote-native", directory.path).use {
                ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes().decodeToString() }
            }
            assertTrue(actualOutput.contains("uid=${Process.myUid()}"))
            assertTrue(actualOutput.contains("exitCode=0"))
            assertTrue(actualOutput.endsWith("remote-native"))
            real.finishRequest("actual")
            val ready = File(directory, "process-ready")
            val executor = Executors.newSingleThreadExecutor()
            try {
                real.beginRequest("running")
                val invocation = executor.submit<Boolean> {
                    runCatching {
                        real.execute("running", "echo ready > '${ready.path}'; exec sleep 60", directory.path).close()
                    }.isFailure
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!ready.exists() && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue(ready.exists())
                rejected.close()
                assertTrue(invocation.get(5, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
            assertFailsWith<IllegalStateException> { real.beginRequest("after-close") }
            assertTrue(deployments.listFiles()!!.isEmpty())
        } finally {
            first.close(); second.close(); rejected.close()
            listOf("remote-close-v1", "remote-close-v2").forEach { File(context.cacheDir, it).delete() }
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }
}

open class AndroidRemoteFixtureEngine(
    private val context: Context,
    artifacts: List<File>,
    private val version: String,
) : IPrivilegedShellService.Stub(), PrivilegedPluginEngine {
    init {
        check(javaClass.classLoader !== PrivilegedPluginEngine::class.java.classLoader)
        check(artifacts.all { it.isFile && !it.canWrite() })
    }
    private var closed = false
    override val binder: IBinder get() = this
    override fun uid() = Process.myUid()
    override fun beginRequest(requestId: String) { check(!closed) }
    override fun cancel(requestId: String) = Unit
    override fun finishRequest(requestId: String) = Unit
    override fun execute(requestId: String, command: String, workingDirectory: String): ParcelFileDescriptor {
        check(!closed)
        val file = File.createTempFile("remote-result", ".out", context.cacheDir)
        return try {
            file.writeText("$version:${uid()}")
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally { file.delete() }
    }
    override fun executeUbuntu(requestId: String, command: String, workingDirectory: String) = execute(requestId, command, workingDirectory)
    override fun close() {
        closed = true
        File(context.cacheDir, "remote-close-$version").writeText("closed")
    }
    override fun destroy() = close()
}

class AndroidRemoteEngineV1(context: Context, artifacts: List<File>) : AndroidRemoteFixtureEngine(context, artifacts, "v1")
class AndroidRemoteEngineV2(context: Context, artifacts: List<File>) : AndroidRemoteFixtureEngine(context, artifacts, "v2")
