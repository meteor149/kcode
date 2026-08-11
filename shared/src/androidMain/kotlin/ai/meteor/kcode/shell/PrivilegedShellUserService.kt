package ai.meteor.kcode.shell

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Runs inside Shizuku's shell/root UserService process, never inside the app UID process. */
class PrivilegedShellUserService() : IPrivilegedShellService.Stub() {
    @Suppress("UNUSED_PARAMETER")
    @Keep
    constructor(context: Context) : this()

    private val running = AtomicReference<java.lang.Process?>(null)

    override fun uid(): Int = Process.myUid()

    override fun execute(
        command: String,
        workingDirectory: String,
    ): ParcelFileDescriptor {
        val actualUid = uid()
        val workDir = resolveWorkingDirectory(workingDirectory)
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(workDir)
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment()["PATH"] = "/system/bin:/system/xbin:/vendor/bin"
                environment()["HOME"] = workDir.absolutePath
                environment()["TMPDIR"] = workDir.absolutePath
                environment()["LANG"] = "C.UTF-8"
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
                append("uid=").append(actualUid).append('\n')
                append("cwd=").append(workDir.absolutePath).append('\n')
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
    }
}
