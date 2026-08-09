package ai.meteor.kcode.shell

import android.content.Context
import android.os.Process
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
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
        relativeWorkingDirectory: String,
        timeoutSeconds: Int,
        maxOutputBytes: Int,
    ): String {
        val actualUid = uid()
        val workspace = File("/data/local/tmp/kcode-$actualUid").apply { mkdirs() }.canonicalFile
        val workDir = resolveWorkingDirectory(workspace, relativeWorkingDirectory)
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
        var truncated = false
        val reader = Thread {
            process.inputStream.use { input ->
                val buffer = ByteArray(4_096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    synchronized(retained) {
                        val remaining = maxOutputBytes - retained.size()
                        if (remaining > 0) retained.write(buffer, 0, minOf(count, remaining))
                        if (count > remaining) truncated = true
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        return try {
            val completed = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                if (!process.waitFor(150, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
            reader.join(500)
            buildString {
                append("uid=").append(actualUid).append('\n')
                append("cwd=").append(virtualWorkspacePath(relativeWorkingDirectory)).append('\n')
                append("exitCode=").append(if (completed) process.exitValue() else "timeout").append('\n')
                synchronized(retained) { append(retained.toByteArray().decodeToString()) }
                if (truncated) append("\n[output truncated at $maxOutputBytes bytes]")
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

    private fun resolveWorkingDirectory(workspace: File, relativePath: String): File {
        require(relativePath.split('/').all { it.isNotEmpty() && it != "." && it != ".." } || relativePath.isEmpty()) {
            "Invalid workspace path"
        }
        val directory = if (relativePath.isEmpty()) workspace else File(workspace, relativePath).canonicalFile
        require(directory.toPath().startsWith(workspace.toPath())) { "Working directory escapes /workspace" }
        require(directory.isDirectory) { "Working directory does not exist: ${virtualWorkspacePath(relativePath)}" }
        return directory
    }

    private fun virtualWorkspacePath(relativePath: String): String =
        if (relativePath.isEmpty()) "/workspace" else "/workspace/$relativePath"
}
