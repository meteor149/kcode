package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.nativeexecution.shell.PrivilegedShellUserService
import ai.meteor.kcode.shell.IPrivilegedPluginBridge
import ai.meteor.kcode.shell.IPrivilegedShellService
import ai.meteor.kcode.shell.PrivilegedPluginEngine
import ai.meteor.kcode.shell.PrivilegedPluginUserService
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A real BinderProxy and FD transport; this does not simulate privileged authorization. */
@RunWith(AndroidJUnit4::class)
class AndroidCrossProcessPluginBridgeTest {
    @Test(timeout = 90_000)
    fun remoteProcessOwnsPrivateDeploymentsAndCopiedFileDescriptors() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val apk = File(instrumentation.context.applicationInfo.sourceDir)
        val digest = apk.inputStream().use { input ->
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        val connections = mutableListOf<ServiceConnection>()
        fun connect(action: String): IPrivilegedPluginBridge {
            val ready = CompletableFuture<IBinder>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) { ready.complete(service) }
                override fun onServiceDisconnected(name: ComponentName) = Unit
                override fun onNullBinding(name: ComponentName) { ready.completeExceptionally(IllegalStateException("Null bridge")) }
            }
            val intent = Intent(action).setComponent(ComponentName(
                instrumentation.context.packageName, CrossProcessPluginBridgeService::class.java.name,
            ))
            check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
            connections += connection
            val binder = ready.get(10, TimeUnit.SECONDS)
            assertNull(binder.queryLocalInterface("ai.meteor.kcode.shell.IPrivilegedPluginBridge"))
            return IPrivilegedPluginBridge.Stub.asInterface(binder)
        }
        fun load(
            bridge: IPrivilegedPluginBridge,
            hash: String = digest,
            entry: String = CrossProcessFixtureEngine::class.java.name,
        ): IPrivilegedShellService =
            ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val engine = bridge.load(arrayOf(descriptor), arrayOf(hash), entry)
                // Receiver consumes only the Binder-owned copy; sender retains its open FD.
                assertTrue(descriptor.fileDescriptor.valid())
                assertNull(engine.queryLocalInterface("ai.meteor.kcode.shell.IPrivilegedShellService"))
                IPrivilegedShellService.Stub.asInterface(engine)
            }
        fun snapshot(engine: IPrivilegedShellService): Map<String, String> =
            ParcelFileDescriptor.AutoCloseInputStream(engine.execute("snapshot", "", "")).use { input ->
                input.readBytes().decodeToString().lines().associate { it.substringBefore('=') to it.substringAfter('=') }
            }
        var first: IPrivilegedPluginBridge? = null
        var second: IPrivilegedPluginBridge? = null
        var native: IPrivilegedPluginBridge? = null
        try {
            val firstBridge = connect("first").also { first = it }
            val secondBridge = connect("second").also { second = it }
            assertFailsWith<IllegalArgumentException> { load(firstBridge, "0".repeat(64)) }
            val a = load(firstBridge)
            val b = load(secondBridge)
            val before = snapshot(b)
            assertNotEquals(Process.myPid().toString(), before["pid"])
            assertEquals(firstBridge.uid().toString(), before["uid"])
            assertEquals("true", before["private"])
            assertEquals("2", before["deployments"])
            assertFailsWith<IllegalStateException> { load(firstBridge) }
            firstBridge.close()
            assertFailsWith<IllegalStateException> { snapshot(a) }
            val after = snapshot(b)
            assertEquals("1", after["deployments"])
            assertEquals("1", after["closed"])
            assertEquals(before["pid"], after["pid"])
            firstBridge.close()
            assertFailsWith<IllegalStateException> { load(firstBridge) }
            // The production private engine also crosses Binder, executes and joins a real process.
            val nativeBridge = connect("native").also { native = it }
            val engine = load(nativeBridge, entry = PrivilegedShellUserService::class.java.name)
            engine.beginRequest("identity")
            try {
                val output = ParcelFileDescriptor.AutoCloseInputStream(
                    engine.execute("identity", "printf cross-process-native", before.getValue("root")),
                ).use { it.readBytes().decodeToString() }
                assertTrue(output.contains("exitCode=0"))
                assertTrue(output.endsWith("cross-process-native"))
            } finally { engine.finishRequest("identity") }
            engine.beginRequest("running")
            val executor = Executors.newSingleThreadExecutor()
            try {
                val running = executor.submit<Boolean> {
                    runCatching {
                        engine.execute("running", "echo $$ > running.pid; exec sleep 60", before.getValue("root")).close()
                    }.isFailure
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var observed = snapshot(b)
                while (observed["running"] == "" && System.nanoTime() < deadline) {
                    Thread.sleep(10)
                    observed = snapshot(b)
                }
                assertTrue(observed.getValue("running").isNotEmpty())
                assertEquals("true", observed["alive"])
                nativeBridge.close()
                assertTrue(running.get(5, TimeUnit.SECONDS))
                assertEquals("false", snapshot(b)["alive"])
                assertEquals("1", snapshot(b)["deployments"])
                assertFailsWith<IllegalStateException> { engine.beginRequest("stale") }
            } finally {
                try { nativeBridge.close() } finally { executor.shutdownNow() }
            }
            secondBridge.close()
            assertFailsWith<IllegalStateException> { snapshot(b) }
        } finally {
            try { first?.close() } finally {
                try { second?.close() } finally {
                    try { native?.close() } finally { connections.forEach(context::unbindService) }
                }
            }
        }
    }
}

/** Test APK service only; its process has no instrumentation instance or production privileges. */
class CrossProcessPluginBridgeService : Service() {
    private val bridges = mutableListOf<PrivilegedPluginUserService>()
    private lateinit var directory: File
    private lateinit var engineContext: Context

    override fun onCreate() {
        super.onCreate()
        directory = File(cacheDir, "cross-process-bridge-${System.nanoTime()}").apply { check(mkdirs()) }
        engineContext = object : ContextWrapper(this) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = directory
        }
        File(directory, "deployments").apply { check(mkdirs()) }
    }

    override fun onBind(intent: Intent): IBinder = PrivilegedPluginUserService(
        engineContext, File(directory, "deployments"),
    ).also { bridges += it }

    override fun onDestroy() {
        try { bridges.forEach { it.close() } } finally {
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
            super.onDestroy()
        }
    }
}

/** Loaded only from transported APK bytes inside the service process. */
class CrossProcessFixtureEngine(
    private val context: Context,
    artifacts: List<File>,
) : IPrivilegedShellService.Stub(), PrivilegedPluginEngine {
    private val deployment = artifacts.first().parentFile!!
    private var closed = false
    private val privateImplementation = javaClass.classLoader !== PrivilegedPluginEngine::class.java.classLoader
    init {
        check(privateImplementation)
        check(artifacts.all { it.isFile && !it.canWrite() })
    }
    override val binder: IBinder get() = this
    override fun uid(): Int = Process.myUid()
    override fun beginRequest(requestId: String) { check(!closed) }
    override fun cancel(requestId: String) = Unit
    override fun finishRequest(requestId: String) = Unit
    override fun execute(requestId: String, command: String, workingDirectory: String): ParcelFileDescriptor {
        check(!closed)
        val output = File.createTempFile("snapshot", ".out", context.cacheDir)
        return try {
            val runningPid = File(context.cacheDir, "running.pid").takeIf { it.exists() }?.readText()?.trim().orEmpty()
            val alive = if (runningPid.isEmpty()) false else try {
                Os.kill(runningPid.toInt(), 0)
                true
            } catch (error: ErrnoException) {
                check(error.errno == OsConstants.ESRCH)
                false
            }
            output.writeText(listOf(
                "pid=${Process.myPid()}",
                "uid=${uid()}",
                "private=$privateImplementation",
                "root=${context.cacheDir.path}",
                "running=$runningPid",
                "alive=$alive",
                "deployments=${deployment.parentFile!!.listFiles()!!.size}",
                "closed=${context.cacheDir.listFiles()!!.count { it.name.startsWith("closed-") }}",
            ).joinToString("\n"))
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally { output.delete() }
    }
    override fun executeUbuntu(requestId: String, command: String, workingDirectory: String): ParcelFileDescriptor =
        execute(requestId, command, workingDirectory)
    override fun close() {
        if (closed) return
        closed = true
        File(context.cacheDir, "closed-${deployment.name}").writeText("closed")
    }
    override fun destroy() = close()
}
