package ai.meteor.kcode.plugin.nativeexecution.shell

import ai.meteor.kcode.shell.IPrivilegedShellService
import ai.meteor.kcode.shell.PrivilegedPluginEngine
import android.os.IBinder
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep
import ai.meteor.kcode.plugin.nativeexecution.NativeExecutionArtifacts
import ai.meteor.kcode.plugin.nativeexecution.AndroidUbuntuEnvironment
import ai.meteor.kcode.plugin.nativeexecution.executeAndroidProcess
import ai.meteor.kcode.plugin.nativeexecution.startAndroidProcessGroup
import ai.meteor.kcode.plugin.nativeexecution.buildUbuntuProotCommand
import ai.meteor.kcode.plugin.nativeexecution.normalizeUbuntuShellCommandRequest
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking

/** Runs inside Shizuku's shell/root UserService process, never inside the app UID process. */
class PrivilegedShellUserService private constructor(
    private val serviceContext: Context?,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : IPrivilegedShellService.Stub(), PrivilegedPluginEngine {
    constructor() : this(null, Unit)

    @Keep
    constructor(context: Context) : this(context.applicationContext, Unit)

    @Keep
    constructor(context: Context, artifacts: List<File>) : this(
        context.applicationContext, Unit,
    ) {
        outputDirectory = artifacts.first().parentFile
        nativeArtifacts = NativeExecutionArtifacts(artifacts, requireNotNull(outputDirectory).toPath())
    }

    override val binder: IBinder get() = this

    override fun close() = synchronized(requestLock) {
        if (!closed) {
            closed = true
            try {
                running.get()?.let { finishRequest(it.id) }
            } finally {
                try { ubuntuEnvironment?.close() } finally { nativeArtifacts?.close() }
            }
        }
    }

    private data class Request(
        val id: String,
        val job: Job = Job(),
        val claimed: AtomicBoolean = AtomicBoolean(false),
    )

    private var ubuntuEnvironment: AndroidUbuntuEnvironment? = null
    private var nativeArtifacts: NativeExecutionArtifacts? = null
    private var outputDirectory: File? = null
    private val running = AtomicReference<Request?>(null)
    private val requestLock = Any()
    private var closed = false

    override fun uid(): Int = Process.myUid()

    override fun execute(
        requestId: String,
        command: String,
        workingDirectory: String,
    ): ParcelFileDescriptor = executeOwned(requestId) {
        val actualUid = uid()
        val workDir = resolveWorkingDirectory(workingDirectory)
        executeProcess(
            commandLine = listOf("/system/bin/sh", "-c", command),
            workDir = workDir,
            header = "uid=$actualUid\ncwd=${workDir.absolutePath}",
        )
    }

    override fun executeUbuntu(
        requestId: String,
        command: String,
        workingDirectory: String,
    ): ParcelFileDescriptor = executeOwned(requestId) {
        val context = requireNotNull(serviceContext) {
            "Shizuku did not provide the application context required to install Ubuntu"
        }
        val actualUid = uid()
        require(actualUid == ADB_SHELL_UID) {
            "Ubuntu adb mode requires UID $ADB_SHELL_UID, actual UID is $actualUid"
        }
        val request = normalizeUbuntuShellCommandRequest(command, workingDirectory)
        val environment = ubuntuEnvironment ?: AndroidUbuntuEnvironment.forAdb(context, nativeArtifacts).also {
            ubuntuEnvironment = it
        }
        val runtime = environment.ensureInstalled()
        val commandLine = buildUbuntuProotCommand(
            runtime = runtime,
            request = request,
            bindMounts = environment.availableBindMounts(),
        )
        executeProcess(
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

    private suspend fun executeProcess(
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
        val result = executeAndroidProcess {
            startAndroidProcessGroup(
                ProcessBuilder(commandLine)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .apply {
                        environment().clear()
                        environment().putAll(environment)
                    },
            )
        }
        val outputFile = File.createTempFile("kcode-shell-", ".out", outputDirectory)
        return try {
            outputFile.writeText("$header\nexitCode=${result.exitCode}\n${result.output}")
            ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            outputFile.delete()
        }
    }

    override fun beginRequest(requestId: String) = synchronized(requestLock) {
        check(!closed) { "Privileged plugin engine is closed" }
        require(requestId.isNotBlank()) { "A request ID is required" }
        val request = Request(requestId)
        if (!running.compareAndSet(null, request)) {
            request.job.cancel()
            error("A privileged shell operation is already registered")
        }
    }

    private fun <T> executeOwned(requestId: String, operation: suspend () -> T): T {
        val request = checkNotNull(running.get()?.takeIf { it.id == requestId }) {
            "The privileged shell request is not registered"
        }
        check(request.claimed.compareAndSet(false, true)) { "The privileged shell request was already executed" }
        return runBlocking(request.job) {
            currentCoroutineContext().ensureActive()
            operation()
        }
    }

    override fun cancel(requestId: String) {
        running.get()?.takeIf { it.id == requestId }?.job?.cancel()
    }

    override fun finishRequest(requestId: String) {
        val request = running.get()?.takeIf { it.id == requestId } ?: return
        runBlocking { request.job.cancelAndJoin() }
        running.compareAndSet(request, null)
    }

    override fun destroy() {
        close()
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
