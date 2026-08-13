package ai.meteor.kcode.shell

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep
import ai.meteor.kcode.AndroidUbuntuEnvironment
import ai.meteor.kcode.buildUbuntuProotCommand
import ai.meteor.kcode.normalizeUbuntuShellCommandRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

/** Runs inside Shizuku's shell/root UserService process, never inside the app UID process. */
class PrivilegedShellUserService private constructor(
    private val serviceContext: Context?,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : IPrivilegedShellService.Stub() {
    constructor() : this(null, Unit)

    @Keep
    constructor(context: Context) : this(context.applicationContext, Unit)

    private val running = AtomicReference<java.lang.Process?>(null)

    override fun uid(): Int = Process.myUid()

    override fun execute(
        command: String,
        workingDirectory: String,
    ): ParcelFileDescriptor {
        val actualUid = uid()
        val workDir = resolveWorkingDirectory(workingDirectory)
        return executeProcess(
            commandLine = listOf("/system/bin/sh", "-c", command),
            workDir = workDir,
            header = "uid=$actualUid\ncwd=${workDir.absolutePath}",
        )
    }

    override fun executeUbuntu(
        command: String,
        workingDirectory: String,
    ): ParcelFileDescriptor {
        val context = requireNotNull(serviceContext) {
            "Shizuku did not provide the application context required to install Ubuntu"
        }
        val actualUid = uid()
        require(actualUid == ADB_SHELL_UID) {
            "Ubuntu adb mode requires UID $ADB_SHELL_UID, actual UID is $actualUid"
        }
        val request = normalizeUbuntuShellCommandRequest(command, workingDirectory)
        val environment = AndroidUbuntuEnvironment.forAdb(context)
        val runtime = runBlocking { environment.ensureInstalled() }
        val commandLine = buildUbuntuProotCommand(
            runtime = runtime,
            request = request,
            bindMounts = environment.availableBindMounts(),
        )
        return executeProcess(
            commandLine = commandLine,
            workDir = runtime.runtimeDirectory.toFile(),
            header = buildString {
                appendLine("environment=ubuntu-proot")
                appendLine("mode=adb")
                appendLine("androidUid=$actualUid")
                append("cwd=${request.workingDirectory}")
            },
            environment = mapOf(
                "HOME" to runtime.runtimeDirectory.toString(),
                "TMPDIR" to runtime.temporaryDirectory.toString(),
                "PROOT_TMP_DIR" to runtime.temporaryDirectory.toString(),
                "PROOT_LOADER" to runtime.loaderExecutable.toString(),
                "LANG" to "C.UTF-8",
            ),
        )
    }

    private fun executeProcess(
        commandLine: List<String>,
        workDir: File,
        header: String,
        environment: Map<String, String> = mapOf(
            "PATH" to "/system/bin:/system/xbin:/vendor/bin",
            "HOME" to workDir.absolutePath,
            "TMPDIR" to workDir.absolutePath,
            "LANG" to "C.UTF-8",
        ),
    ): ParcelFileDescriptor {
        val process = ProcessBuilder(commandLine)
            .directory(workDir)
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment().putAll(environment)
            }
            .start()
        running.set(process)

        val retained = ByteArrayOutputStream()
        val reader = Thread {
            process.inputStream.use { input ->
                val buffer = ByteArray(4_096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    synchronized(retained) {
                        retained.write(buffer, 0, count)
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        return try {
            val exitCode = process.waitFor()
            reader.join()
            val result = buildString {
                appendLine(header)
                append("exitCode=").append(exitCode).append('\n')
                synchronized(retained) { append(retained.toByteArray().decodeToString()) }
            }
            val outputFile = File.createTempFile("kcode-shell-", ".out")
            outputFile.writeText(result)
            ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_ONLY).also {
                outputFile.delete()
            }
        } finally {
            running.compareAndSet(process, null)
            if (process.isAlive) process.destroyForcibly()
        }
    }

    override fun cancel() {
        running.getAndSet(null)?.destroyForcibly()
    }

    override fun destroy() {
        cancel()
        System.exit(0)
    }

    private fun resolveWorkingDirectory(path: String): File {
        val requestedPath = path.ifEmpty { DEFAULT_WORKING_DIRECTORY }
        val requestedDirectory = File(requestedPath)
        require(requestedDirectory.isAbsolute) { "Working directory must be absolute: $requestedPath" }
        val directory = requestedDirectory.canonicalFile
        require(directory.isDirectory) { "Working directory does not exist: ${directory.absolutePath}" }
        return directory
    }

    private companion object {
        const val DEFAULT_WORKING_DIRECTORY = "/data/local/tmp"
        const val ADB_SHELL_UID = 2_000
    }
}
