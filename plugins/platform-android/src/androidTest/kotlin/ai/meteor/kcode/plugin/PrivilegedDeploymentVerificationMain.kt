package ai.meteor.kcode.plugin

import org.cordis.packages.packageFileSha256

import ai.meteor.kcode.plugin.nativeexecution.shell.PrivilegedShellUserService
import ai.meteor.kcode.shell.IPrivilegedPluginBridge
import ai.meteor.kcode.shell.IPrivilegedShellService
import ai.meteor.kcode.shell.PrivilegedPluginUserService
import android.content.Context
import android.content.ContextWrapper
import android.os.Binder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Run from adb app_process; this is deliberately not an app-UID instrumentation test. */
object PrivilegedDeploymentVerificationMain {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            verify(args)
            println("KCODE_PRIVILEGED_VERIFICATION_OK uid=${Process.myUid()}")
            kotlin.system.exitProcess(0)
        } catch (error: Throwable) {
            error.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }

    private fun verify(args: Array<String>) {
        check(Process.myUid() == 2_000) { "This verification requires the actual adb shell UID" }
        val directory = File(args[0]).canonicalFile
        require(directory.parent == "/data/local/tmp" && directory.name.startsWith("kcode-privileged-probe-"))
        val artifact = File(args[1]).canonicalFile
        require(artifact.parentFile == directory && artifact.isFile && !artifact.canWrite())
        android.os.Looper.prepareMainLooper()
        val thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null)
        val systemContext = thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
        val context = object : ContextWrapper(systemContext) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName() = directory.name
            override fun getCacheDir() = directory
            override fun getFilesDir() = directory
        }
        val deployments = File(directory, "deployments").apply { mkdirs() }
        val digest = packageFileSha256(artifact)
        fun proxy(bridge: PrivilegedPluginUserService): IPrivilegedPluginBridge =
            IPrivilegedPluginBridge.Stub.asInterface(object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                    bridge.transact(code, data, reply, flags)
            })
        fun load(bridge: PrivilegedPluginUserService): IPrivilegedShellService =
            ParcelFileDescriptor.open(artifact, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                IPrivilegedShellService.Stub.asInterface(proxy(bridge).load(
                    arrayOf(descriptor), arrayOf(digest), PrivilegedShellUserService::class.java.name,
                ))
            }
        fun execute(engine: IPrivilegedShellService, id: String, command: String, ubuntu: Boolean = false): String {
            engine.beginRequest(id)
            try {
                val output = if (ubuntu) engine.executeUbuntu(id, command, "/workspace")
                    else engine.execute(id, command, directory.path)
                return ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().decodeToString() }
            } finally { engine.finishRequest(id) }
        }
        val first = PrivilegedPluginUserService(context, deployments)
        val second = PrivilegedPluginUserService(context, deployments)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val a = load(first)
            val b = load(second)
            check(a.uid() == 2_000 && b.uid() == 2_000)
            check(a.javaClass.classLoader !== PrivilegedShellUserService::class.java.classLoader)
            check(a.javaClass.classLoader !== b.javaClass.classLoader)
            val result = execute(a, "identity", "id -u; printf private-shell")
            check(result.contains("exitCode=0\n2000\nprivate-shell")) { result }
            check(deployments.listFiles()!!.size == 2)
            println("private-engine-and-command uid=2000 verified")
            val ubuntu = args.getOrNull(2) == "ubuntu"
            if (ubuntu) {
                val output = execute(a, "ubuntu", "cat /etc/os-release; python3 -c 'print(6 * 7)'", true)
                check(output.contains("androidUid=2000") && output.contains("exitCode=0")) { output }
                check(output.contains("24.04") && output.trimEnd().endsWith("42")) { output }
                println("private-ubuntu-and-python uid=2000 verified")
            }
            val ready = if (ubuntu) File(directory, "ubuntu/agent_workspace/running.pid") else File(directory, "running.pid")
            if (ready.exists()) check(ready.delete())
            a.beginRequest("running")
            val invocation = pool.submit<Boolean> {
                runCatching {
                    val output = if (ubuntu) a.executeUbuntu("running", "echo $$ > /workspace/running.pid; exec sleep 60", "/workspace")
                        else a.execute("running", "echo $$ > '${ready.path}'; exec sleep 60", directory.path)
                    output.close()
                }.isFailure
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while ((!ready.isFile || ready.readText().trim().isEmpty()) && System.nanoTime() < deadline) Thread.sleep(20)
            check(ready.isFile && ready.readText().trim().isNotEmpty()) { "Command did not start" }
            val pid = ready.readText().trim().toInt()
            Os.kill(pid, 0)
            first.close()
            check(invocation.get(15, TimeUnit.SECONDS)) { "Retirement did not cancel the command" }
            try {
                Os.kill(pid, 0)
                error("Owned command survived retirement")
            } catch (gone: ErrnoException) { check(gone.errno == OsConstants.ESRCH) }
            check(deployments.listFiles()!!.size == 1)
            check(runCatching { a.beginRequest("stale") }.isFailure)
            check(execute(b, "independent", "printf other-engine").endsWith("other-engine"))
            second.close()
            check(deployments.listFiles()!!.isEmpty())
            if (ubuntu) {
                val runtime = File(directory, "ubuntu/ubuntu_runtime")
                check(File(runtime, "rootfs/usr/bin/python3").exists())
                check(runtime.listFiles()!!.none { it.isDirectory && it.name.startsWith("tmp-") })
            }
            println("retirement-process-exit-stale-rejection-and-sibling-isolation verified")
        } finally {
            try { first.close() } finally { try { second.close() } finally { pool.shutdownNow() } }
        }
    }
}
