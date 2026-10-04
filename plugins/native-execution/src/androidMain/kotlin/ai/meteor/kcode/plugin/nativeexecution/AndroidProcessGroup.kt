package ai.meteor.kcode.plugin.nativeexecution

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** Foreground commands own their session's process group, including children retaining stdout. */
internal fun startAndroidProcessGroup(builder: ProcessBuilder): Process {
    val command = builder.command().toList()
    builder.command(listOf(
        "/system/bin/setsid", "/system/bin/sh", "-c",
        "printf 'KCODE_PROCESS_GROUP=%s\\n' \"${'$'}${'$'}\"; exec \"${'$'}@\"",
        "kcode-process-group",
    ) + command)
    return readAndroidProcessGroup(builder.start())
}

/** The elevated command must emit the registration after setsid, before running user code. */
internal fun startAndroidPrivilegedProcessGroup(
    builder: ProcessBuilder,
    signalGroup: (Int, Int) -> Unit = ::signalAndroidRootProcessGroup,
): Process = readAndroidProcessGroup(builder.start(), signalGroup)

private fun readAndroidProcessGroup(
    process: Process,
    signalGroup: ((Int, Int) -> Unit)? = null,
): Process {
    try {
        val header = StringBuilder()
        while (header.length < 64) {
            if (process.inputStream.available() == 0) {
                check(process.isAlive) { "Android process group exited before registration: $header" }
                Thread.sleep(10)
                continue
            }
            val next = process.inputStream.read()
            check(next >= 0) { "Android process group exited before registration: $header" }
            if (next == 10) break
            header.append(next.toChar())
        }
        check(header.startsWith("KCODE_PROCESS_GROUP=")) { "Missing Android process group registration: $header" }
        val groupId = header.toString().removePrefix("KCODE_PROCESS_GROUP=").toIntOrNull()
        check(groupId != null && groupId > 1) { "Invalid Android process group registration: $header" }
        val group = AndroidProcessGroup(process, groupId, signalGroup)
        if (signalGroup != null) {
            process.outputStream.write("KCODE_RUN\n".toByteArray())
            process.outputStream.flush()
        }
        return group
    } catch (error: Throwable) {
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
        process.inputStream.close()
        process.outputStream.close()
        process.errorStream.close()
        throw error
    }
}

private class AndroidProcessGroup(
    private val process: Process,
    private val groupId: Int,
    private val signalGroup: ((Int, Int) -> Unit)?,
) : Process() {
    override fun getInputStream(): InputStream = process.inputStream
    override fun getOutputStream(): OutputStream = process.outputStream
    override fun getErrorStream(): InputStream = process.errorStream
    override fun exitValue(): Int = process.exitValue()
    override fun waitFor(): Int = process.waitFor()
    override fun isAlive(): Boolean = process.isAlive || groupAlive()

    override fun destroy() {
        try {
            signal(OsConstants.SIGTERM)
        } finally {
            process.destroy()
        }
    }

    override fun destroyForcibly(): Process {
        try {
            signal(OsConstants.SIGKILL)
        } finally {
            process.destroyForcibly()
        }
        return this
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        val budget = unit.toNanos(timeout)
        val started = System.nanoTime()
        if (!process.waitFor(timeout, unit)) return false
        while (groupAlive()) {
            if (System.nanoTime() - started >= budget) return false
            Thread.sleep(10)
        }
        return true
    }

    private fun groupAlive(): Boolean = androidProcessGroupAlive(groupId)

    private fun signal(value: Int) {
        if (signalGroup != null) {
            signalGroup.invoke(groupId, value)
        } else {
            try {
                Os.kill(-groupId, value)
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.ESRCH) throw error
            }
        }
    }
}

/** EPERM means the group exists under another UID; it must never be treated as exited. */
internal fun androidProcessGroupAlive(groupId: Int): Boolean = try {
    Os.kill(-groupId, 0)
    true
} catch (error: ErrnoException) {
    when (error.errno) {
        OsConstants.ESRCH -> false
        OsConstants.EPERM -> true
        else -> throw error
    }
}

private fun signalAndroidRootProcessGroup(groupId: Int, signal: Int) {
    signalAndroidPrivilegedProcessGroup(groupId, signal) { command ->
        ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
    }
}

/** Bounded elevated signalling; failed authorization cannot masquerade as successful cleanup. */
internal fun signalAndroidPrivilegedProcessGroup(
    groupId: Int,
    signal: Int,
    start: (String) -> Process,
) {
    require(groupId > 1)
    require(signal == OsConstants.SIGTERM || signal == OsConstants.SIGKILL)
    val process = start("/system/bin/kill -$signal -- -$groupId")
    try {
        check(process.waitFor(5, TimeUnit.SECONDS)) { "Privileged process-group signal did not complete" }
        check(process.exitValue() == 0 || !androidProcessGroupAlive(groupId)) {
            "Privileged process-group signal failed with exit code ${process.exitValue()}"
        }
    } finally {
        if (process.isAlive) process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
        process.inputStream.close()
        process.outputStream.close()
        process.errorStream.close()
    }
}

internal fun registeredAndroidProcessGroupCommand(command: String): String =
    "exec /system/bin/setsid /system/bin/sh -c " + androidProcessShellQuote(
        "printf 'KCODE_PROCESS_GROUP=%s\\n' \"${'$'}${'$'}\"; " +
            "IFS= read -r activation; [ \"${'$'}activation\" = KCODE_RUN ] || exit 126; " +
            "exec /system/bin/sh -c " +
            androidProcessShellQuote(command),
    )

private fun androidProcessShellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
