package ai.meteor.kcode

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.shell.IPrivilegedShellService
import ai.meteor.kcode.shell.PrivilegedShellUserService
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AndroidShellExecutors(
    private val activity: Activity,
    private val appWorkspace: Path,
) {
    suspend fun execute(mode: ShellExecutionMode, command: String, timeoutSeconds: Int): String = when (mode) {
        ShellExecutionMode.App -> executeLocal(
            commandLine = listOf("/system/bin/sh", "-c", command),
            workspace = appWorkspace,
            timeoutSeconds = timeoutSeconds,
            requestedMode = mode,
            identityLine = "uid=${Process.myUid()}",
        )
        ShellExecutionMode.Root -> executeLocal(
            commandLine = listOf("su", "-c", rootVerifiedCommand(command)),
            workspace = appWorkspace,
            timeoutSeconds = timeoutSeconds,
            requestedMode = mode,
            identityLine = "requiredUid=0",
        )
        ShellExecutionMode.Adb -> executeWithShizuku(command, timeoutSeconds)
    }

    private suspend fun executeWithShizuku(command: String, timeoutSeconds: Int): String {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return "mode=adb\nerror=Shizuku is not running. Start Shizuku with adb, then try again."
        }
        val providerUid = runCatching { Shizuku.getUid() }.getOrElse {
            return "mode=adb\nerror=Unable to query the Shizuku service UID: ${it.message}"
        }
        if (providerUid != ADB_SHELL_UID) {
            return "mode=adb\nerror=Shizuku is running as UID $providerUid, not adb shell UID 2000. Start Shizuku through adb."
        }
        if (!ensureShizukuPermission()) {
            return "mode=adb\nerror=Shizuku permission was not granted."
        }

        val args = Shizuku.UserServiceArgs(
            ComponentName(activity.packageName, PrivilegedShellUserService::class.java.name),
        ).daemon(false).processNameSuffix("adb_shell").version(1)

        var connection: ServiceConnection? = null
        return try {
            val service = withTimeoutOrNull(SHIZUKU_BIND_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine<IPrivilegedShellService> { continuation ->
                    val candidate = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            val value = IPrivilegedShellService.Stub.asInterface(binder)
                            if (value == null) {
                                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Empty Shizuku service binder"))
                            } else if (continuation.isActive) {
                                continuation.resume(value)
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Shizuku service disconnected"))
                        }
                    }
                    connection = candidate
                    Shizuku.bindUserService(args, candidate)
                    continuation.invokeOnCancellation {
                        runCatching { Shizuku.unbindUserService(args, candidate, true) }
                    }
                }
            } ?: return "mode=adb\nerror=Timed out while connecting to the Shizuku UserService."

            val actualUid = withContext(Dispatchers.IO) { service.uid() }
            if (actualUid != ADB_SHELL_UID) {
                "mode=adb\nerror=The UserService has UID $actualUid instead of adb shell UID 2000."
            } else {
                val result = withContext(Dispatchers.IO) {
                    service.execute(command, timeoutSeconds, MAX_OUTPUT_BYTES)
                }
                "mode=adb\n$result"
            }
        } catch (error: Throwable) {
            "mode=adb\nerror=${error.message ?: error::class.simpleName}"
        } finally {
            connection?.let { runCatching { Shizuku.unbindUserService(args, it, true) } }
        }
    }

    private suspend fun ensureShizukuPermission(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return@withContext true
        if (Shizuku.shouldShowRequestPermissionRationale()) return@withContext false
        suspendCancellableCoroutine { continuation ->
            val requestCode = (System.nanoTime() and 0x7fff).toInt()
            lateinit var listener: Shizuku.OnRequestPermissionResultListener
            listener = Shizuku.OnRequestPermissionResultListener { code, grantResult ->
                if (code == requestCode) {
                    Shizuku.removeRequestPermissionResultListener(listener)
                    if (continuation.isActive) continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            continuation.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
            Shizuku.requestPermission(requestCode)
        }
    }

    private suspend fun executeLocal(
        commandLine: List<String>,
        workspace: Path,
        timeoutSeconds: Int,
        requestedMode: ShellExecutionMode,
        identityLine: String,
    ): String = try {
        coroutineScope {
            val process = withContext(Dispatchers.IO) {
                ProcessBuilder(commandLine)
                    .directory(workspace.toFile())
                    .redirectErrorStream(true)
                    .apply {
                        environment().clear()
                        environment()["PATH"] = "/system/bin:/system/xbin:/vendor/bin"
                        environment()["HOME"] = workspace.toString()
                        environment()["TMPDIR"] = workspace.toString()
                        environment()["LANG"] = "C.UTF-8"
                    }
                    .start()
            }
            val output = async(Dispatchers.IO) {
                process.inputStream.use { input ->
                    val retained = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4_096)
                    var truncated = false
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        val remaining = MAX_OUTPUT_BYTES - retained.size()
                        if (remaining > 0) retained.write(buffer, 0, minOf(count, remaining))
                        if (count > remaining) truncated = true
                    }
                    retained.toByteArray().decodeToString() to truncated
                }
            }
            try {
                val completed = withTimeoutOrNull(timeoutSeconds * 1_000L) {
                    while (process.isAlive) delay(50)
                    true
                } == true
                if (!completed) {
                    process.destroy()
                    delay(150)
                    if (process.isAlive) process.destroyForcibly()
                }
                val (text, truncated) = output.await()
                buildString {
                    append("mode=").append(requestedMode.code).append('\n')
                    append(identityLine).append('\n')
                    append("cwd=").append(workspace).append('\n')
                    append("exitCode=").append(if (completed) process.exitValue() else "timeout").append('\n')
                    append(text)
                    if (truncated) append("\n[output truncated at $MAX_OUTPUT_BYTES bytes]")
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
    } catch (error: Throwable) {
        "mode=${requestedMode.code}\nerror=${if (requestedMode == ShellExecutionMode.Root) "Root shell unavailable or denied: " else "Shell unavailable: "}${error.message ?: error::class.simpleName}"
    }

    private fun rootVerifiedCommand(command: String): String =
        "actual_uid=\$(id -u); " +
            "if [ \"\$actual_uid\" != \"0\" ]; then " +
            "echo \"kcode: su returned UID \$actual_uid, expected 0\" >&2; exit 126; fi; " +
            command

    private companion object {
        const val ADB_SHELL_UID = 2_000
        const val MAX_OUTPUT_BYTES = 65_536
        const val SHIZUKU_BIND_TIMEOUT_MILLIS = 10_000L
    }
}
