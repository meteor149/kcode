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
import java.nio.file.Files
import java.nio.file.Path
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AndroidShellExecutors(
    private val activity: Activity,
    private val modeProvider: suspend () -> ShellExecutionMode,
) : AgentShellExecutor {
    override suspend fun execute(
        command: String,
        workingDirectory: String?,
    ): AgentShellExecutor.ExecutionResult {
        val request = normalizeAndroidShellCommandRequest(command, workingDirectory)
        val mode = modeProvider()
        return execute(mode, request)
    }

    private suspend fun execute(
        mode: ShellExecutionMode,
        request: AndroidShellCommandRequest,
    ): AgentShellExecutor.ExecutionResult = when (mode) {
        ShellExecutionMode.App -> executeLocal(
            commandLine = listOf("/system/bin/sh", "-c", request.command),
            workingDirectory = resolveAppWorkingDirectory(request.workingDirectory),
            reportedWorkingDirectory = request.workingDirectory ?: activity.applicationContext.filesDir.absolutePath,
            requestedMode = mode,
            identityLine = "uid=${Process.myUid()}",
        )
        ShellExecutionMode.Root -> executeLocal(
            commandLine = listOf(
                "su",
                "-c",
                rootVerifiedCommand(request.command, request.workingDirectory ?: ROOT_DEFAULT_DIRECTORY),
            ),
            workingDirectory = activity.applicationContext.filesDir.toPath(),
            reportedWorkingDirectory = request.workingDirectory ?: ROOT_DEFAULT_DIRECTORY,
            requestedMode = mode,
            identityLine = "requiredUid=0",
        )
        ShellExecutionMode.Adb -> executeWithShizuku(request)
    }

    private suspend fun executeWithShizuku(request: AndroidShellCommandRequest): AgentShellExecutor.ExecutionResult {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return failure("mode=adb\nerror=Shizuku is not running. Start Shizuku with adb, then try again.")
        }
        val providerUid = runCatching { Shizuku.getUid() }.getOrElse {
            return failure("mode=adb\nerror=Unable to query the Shizuku service UID: ${it.message}")
        }
        if (providerUid != ADB_SHELL_UID) {
            return failure("mode=adb\nerror=Shizuku is running as UID $providerUid, not adb shell UID 2000. Start Shizuku through adb.")
        }
        if (!ensureShizukuPermission()) {
            return failure("mode=adb\nerror=Shizuku permission was not granted.")
        }

        val args = Shizuku.UserServiceArgs(
            ComponentName(activity.packageName, PrivilegedShellUserService::class.java.name),
        ).daemon(false).processNameSuffix("adb_shell").version(5)

        var connection: ServiceConnection? = null
        return try {
            val service = suspendCancellableCoroutine<IPrivilegedShellService> { continuation ->
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

            val actualUid = withContext(Dispatchers.IO) { service.uid() }
            if (actualUid != ADB_SHELL_UID) {
                failure("mode=adb\nerror=The UserService has UID $actualUid instead of adb shell UID 2000.")
            } else {
                val result = withContext(Dispatchers.IO) {
                    service.execute(
                        request.command,
                        request.workingDirectory.orEmpty(),
                    ).use { descriptor ->
                        FileInputStream(descriptor.fileDescriptor).use { input ->
                            input.readBytes().decodeToString()
                        }
                    }
                }
                parsePrivilegedResult("mode=adb\n$result")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            failure("mode=adb\nerror=${error.message ?: error::class.simpleName}")
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
        workingDirectory: Path,
        reportedWorkingDirectory: String,
        requestedMode: ShellExecutionMode,
        identityLine: String,
    ): AgentShellExecutor.ExecutionResult = try {
        coroutineScope {
            val process = withContext(Dispatchers.IO) {
                ProcessBuilder(commandLine)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .apply {
                        environment().clear()
                        environment()["PATH"] = "/system/bin:/system/xbin:/vendor/bin"
                        environment()["HOME"] = workingDirectory.toString()
                        environment()["TMPDIR"] = workingDirectory.toString()
                        environment()["LANG"] = "C.UTF-8"
                    }
                    .start()
            }
            val output = async(Dispatchers.IO) {
                process.inputStream.use { input ->
                    val retained = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4_096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        retained.write(buffer, 0, count)
                    }
                    retained.toByteArray().decodeToString()
                }
            }
            try {
                while (process.isAlive) delay(50)
                val text = output.await()
                AgentShellExecutor.ExecutionResult(
                    output = buildString {
                        append("mode=").append(requestedMode.code).append('\n')
                        append(identityLine).append('\n')
                        append("cwd=").append(reportedWorkingDirectory).append('\n')
                        append(text)
                    }.trimEnd(),
                    exitCode = process.exitValue(),
                )
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        failure(
            "mode=${requestedMode.code}\nerror=" +
                if (requestedMode == ShellExecutionMode.Root) {
                    "Root shell unavailable or denied: ${error.message ?: error::class.simpleName}"
                } else {
                    "Shell unavailable: ${error.message ?: error::class.simpleName}"
                },
        )
    }

    private fun resolveAppWorkingDirectory(requestedPath: String?): Path {
        val candidate = when {
            requestedPath == null -> activity.applicationContext.filesDir.toPath()
            requestedPath == "/workspace" || requestedPath.startsWith("/workspace/") -> {
                val root = activity.applicationContext.filesDir.toPath().resolve("agent_workspace")
                val relative = requestedPath.removePrefix("/workspace").trimStart('/')
                relative.split('/').filter(String::isNotEmpty).fold(root, Path::resolve)
            }
            else -> Path.of(requestedPath)
        }
        val directory = candidate.toRealPath()
        require(Files.isDirectory(directory)) { "Working directory does not exist: $directory" }
        return directory
    }

    private fun parsePrivilegedResult(output: String): AgentShellExecutor.ExecutionResult {
        val lines = output.lines().toMutableList()
        val exitCodeIndex = lines.indexOfFirst { it.startsWith("exitCode=") }
        val exitCode = if (exitCodeIndex >= 0) {
            lines.removeAt(exitCodeIndex).substringAfter('=').toIntOrNull()
        } else {
            null
        }
        return AgentShellExecutor.ExecutionResult(lines.joinToString("\n").trimEnd(), exitCode)
    }

    private fun failure(output: String): AgentShellExecutor.ExecutionResult =
        AgentShellExecutor.ExecutionResult(output, null)

    private fun rootVerifiedCommand(command: String, workingDirectory: String): String =
        "actual_uid=\$(id -u); " +
            "if [ \"\$actual_uid\" != \"0\" ]; then " +
            "echo \"kcode: su returned UID \$actual_uid, expected 0\" >&2; exit 126; fi; " +
            "cd ${shellQuote(workingDirectory)} || exit 1; " +
            command

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private companion object {
        const val ADB_SHELL_UID = 2_000
        const val ROOT_DEFAULT_DIRECTORY = "/"
    }
}

internal data class AndroidShellCommandRequest(
    val command: String,
    val workingDirectory: String?,
)

internal fun normalizeAndroidShellCommandRequest(
    command: String,
    workingDirectory: String?,
): AndroidShellCommandRequest {
    val normalizedCommand = command.trim()
    require(normalizedCommand.isNotEmpty()) { "Command must not be empty" }
    require(normalizedCommand.length <= MAX_ANDROID_SHELL_COMMAND_CHARS) { "Command is too long" }

    return AndroidShellCommandRequest(
        command = normalizedCommand,
        workingDirectory = workingDirectory?.let(::normalizeAndroidAbsolutePath),
    )
}

private fun normalizeAndroidAbsolutePath(path: String): String {
    require(path.startsWith('/')) { "Working directory must be an absolute Android path" }
    require('\\' !in path && '\u0000' !in path) { "Invalid Android working directory" }
    val components = mutableListOf<String>()
    path.split('/').forEach { component ->
        when (component) {
            "", "." -> Unit
            ".." -> if (components.isNotEmpty()) components.removeAt(components.lastIndex)
            else -> components += component
        }
    }
    return if (components.isEmpty()) "/" else "/${components.joinToString("/")}"
}

private const val MAX_ANDROID_SHELL_COMMAND_CHARS = 8_192
