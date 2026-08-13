package ai.meteor.kcode

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/** Runs agent commands in an app-private Ubuntu user space without elevating the Android UID. */
class AndroidUbuntuShellExecutor(
    context: Context,
) : AgentShellExecutor {
    private val appContext = context.applicationContext
    private val environment = AndroidUbuntuEnvironment(appContext)

    override suspend fun execute(
        command: String,
        workingDirectory: String?,
    ): AgentShellExecutor.ExecutionResult {
        val request = normalizeUbuntuShellCommandRequest(command, workingDirectory)
        return try {
            val runtime = environment.ensureInstalled()
            val process = startProcess(runtime, request)
            val output = collectProcess(process)
            AgentShellExecutor.ExecutionResult(
                output = buildString {
                    appendLine("environment=ubuntu-proot")
                    appendLine("androidUid=${android.os.Process.myUid()}")
                    appendLine("cwd=${request.workingDirectory}")
                    append(output)
                }.trimEnd(),
                exitCode = process.exitValue(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AgentShellExecutor.ExecutionResult(
                output = "environment=ubuntu-proot\nerror=${error.message ?: error::class.simpleName}",
                exitCode = null,
            )
        }
    }

    private fun startProcess(
        runtime: UbuntuRuntimePaths,
        request: UbuntuShellCommandRequest,
    ): Process {
        val commandLine = buildUbuntuProotCommand(
            runtime = runtime,
            request = request,
            bindMounts = environment.availableBindMounts(),
        )
        return ProcessBuilder(commandLine)
            .directory(runtime.runtimeDirectory.toFile())
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment()["HOME"] = runtime.runtimeDirectory.toString()
                environment()["TMPDIR"] = runtime.temporaryDirectory.toString()
                environment()["PROOT_TMP_DIR"] = runtime.temporaryDirectory.toString()
                environment()["PROOT_LOADER"] = runtime.loaderExecutable.toString()
                environment()["LANG"] = "C.UTF-8"
            }
            .start()
    }

    private suspend fun collectProcess(process: Process): String {
        val output = StringBuilder()
        val reader = withContext(Dispatchers.IO) { process.inputStream.bufferedReader() }
        return try {
            while (true) {
                currentCoroutineContext().ensureActive()
                var consumed = false
                withContext(Dispatchers.IO) {
                    while (reader.ready()) {
                        val value = reader.read()
                        if (value < 0) return@withContext
                        output.append(value.toChar())
                        consumed = true
                    }
                }
                if (!process.isAlive) {
                    withContext(Dispatchers.IO) { output.append(reader.readText()) }
                    break
                }
                if (!consumed) delay(PROCESS_POLL_MILLIS)
            }
            output.toString()
        } finally {
            withContext(Dispatchers.IO) { runCatching { reader.close() } }
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

internal data class UbuntuShellCommandRequest(
    val command: String,
    val workingDirectory: String,
)

internal data class UbuntuRuntimePaths(
    val runtimeDirectory: Path,
    val rootFileSystem: Path,
    val temporaryDirectory: Path,
    val prootExecutable: Path,
    val loaderExecutable: Path,
)

internal data class UbuntuBindMount(
    val source: Path,
    val target: String,
)

internal fun normalizeUbuntuShellCommandRequest(
    command: String,
    workingDirectory: String?,
): UbuntuShellCommandRequest {
    val normalizedCommand = command.trim()
    require(normalizedCommand.isNotEmpty()) { "Command must not be empty" }
    require(normalizedCommand.length <= MAX_UBUNTU_SHELL_COMMAND_CHARS) { "Command is too long" }
    return UbuntuShellCommandRequest(
        command = normalizedCommand,
        workingDirectory = normalizeUbuntuAbsolutePath(workingDirectory ?: DEFAULT_UBUNTU_WORKING_DIRECTORY),
    )
}

internal fun buildUbuntuProotCommand(
    runtime: UbuntuRuntimePaths,
    request: UbuntuShellCommandRequest,
    bindMounts: List<UbuntuBindMount>,
): List<String> = buildList {
    add(runtime.prootExecutable.toString())
    add("-0")
    add("-r")
    add(runtime.rootFileSystem.toString())
    bindMounts.forEach { mount ->
        add("-b")
        add("${mount.source}:${mount.target}")
    }
    add("-w")
    add(request.workingDirectory)
    add("/usr/bin/env")
    add("-i")
    add("HOME=/root")
    add("USER=root")
    add("LOGNAME=root")
    add("SHELL=/bin/bash")
    add("TERM=dumb")
    add("LANG=C.UTF-8")
    add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
    add("/bin/bash")
    add("-lc")
    add(request.command)
}

internal fun validatedRootfsRelativePath(entryName: String): Path? {
    require('\u0000' !in entryName) { "Invalid rootfs archive entry" }
    val normalizedName = entryName.trimEnd('/')
    if (normalizedName == ROOTFS_ARCHIVE_DIRECTORY) return null
    require(normalizedName.startsWith("$ROOTFS_ARCHIVE_DIRECTORY/")) {
        "Rootfs archive entry has an unexpected prefix: $entryName"
    }
    val relativeText = normalizedName.removePrefix("$ROOTFS_ARCHIVE_DIRECTORY/")
    require(relativeText.isNotEmpty()) { "Invalid empty rootfs archive entry" }
    val relative = Path.of(relativeText).normalize()
    require(!relative.isAbsolute && relative.none { it.toString() == ".." }) {
        "Rootfs archive entry escapes the install directory: $entryName"
    }
    return relative
}

private fun normalizeUbuntuAbsolutePath(path: String): String {
    require(path.startsWith('/')) { "Working directory must be an absolute Ubuntu path" }
    require('\\' !in path && '\u0000' !in path) { "Invalid Ubuntu working directory" }
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

private class AndroidUbuntuEnvironment(
    private val context: Context,
) {
    private val runtimeDirectory = context.filesDir.toPath().resolve(RUNTIME_DIRECTORY_NAME)
    private val rootFileSystem = runtimeDirectory.resolve("rootfs")
    private val stagingDirectory = runtimeDirectory.resolve("installing")
    private val temporaryDirectory = runtimeDirectory.resolve("tmp")
    private val workspaceDirectory = context.filesDir.toPath().resolve("agent_workspace")
    private val nativeLibraryDirectory = Path.of(context.applicationInfo.nativeLibraryDir)

    suspend fun ensureInstalled(): UbuntuRuntimePaths = InstallMutex.withLock {
        withContext(Dispatchers.IO) {
            require(Build.SUPPORTED_ABIS.any { it == SUPPORTED_ABI }) {
                "Ubuntu PRoot currently requires an ARM64 Android device; ABIs=${Build.SUPPORTED_ABIS.joinToString()}"
            }
            val proot = requireExecutable(PROOT_LIBRARY_NAME)
            val loader = requireExecutable(PROOT_LOADER_LIBRARY_NAME)
            Files.createDirectories(runtimeDirectory)
            Files.createDirectories(temporaryDirectory)
            Files.createDirectories(workspaceDirectory)

            if (!isCompleteInstallation()) {
                installRootFileSystem()
            }
            UbuntuRuntimePaths(
                runtimeDirectory = runtimeDirectory,
                rootFileSystem = rootFileSystem,
                temporaryDirectory = temporaryDirectory,
                prootExecutable = proot,
                loaderExecutable = loader,
            )
        }
    }

    fun availableBindMounts(): List<UbuntuBindMount> = buildList {
        add(UbuntuBindMount(workspaceDirectory, "/workspace"))
        addIfAccessible(Path.of("/dev"), "/dev")
        addIfAccessible(Path.of("/proc"), "/proc")
        addIfAccessible(Path.of("/sys"), "/sys")
        val emulatedStorage = Path.of("/storage/emulated/${android.os.Process.myUid() / ANDROID_UID_USER_RANGE}")
        addIfAccessible(emulatedStorage, "/sdcard")
    }

    private fun MutableList<UbuntuBindMount>.addIfAccessible(source: Path, target: String) {
        if (Files.exists(source) && Files.isReadable(source)) add(UbuntuBindMount(source, target))
    }

    private fun requireExecutable(fileName: String): Path {
        val executable = nativeLibraryDirectory.resolve(fileName)
        require(Files.isRegularFile(executable) && Files.isExecutable(executable)) {
            "Required Ubuntu runtime executable is unavailable: $fileName"
        }
        return executable
    }

    private fun isCompleteInstallation(): Boolean {
        val marker = rootFileSystem.resolve(INSTALL_MARKER)
        return runCatching { Files.readAllBytes(marker).decodeToString().trim() == RUNTIME_VERSION }.getOrDefault(false) &&
            REQUIRED_ROOTFS_PATHS.all { Files.exists(rootFileSystem.resolve(it), LinkOption.NOFOLLOW_LINKS) }
    }

    private suspend fun installRootFileSystem() {
        require(context.filesDir.usableSpace >= MINIMUM_FREE_SPACE_BYTES) {
            "At least ${MINIMUM_FREE_SPACE_BYTES / (1024 * 1024)} MiB free space is required to install Ubuntu"
        }
        deleteRuntimeTree(stagingDirectory)
        Files.createDirectories(stagingDirectory)
        try {
            verifyRootfsAsset()
            extractRootfsAsset()
            val extractedRoot = stagingDirectory.resolve(ROOTFS_ARCHIVE_DIRECTORY)
            require(REQUIRED_ROOTFS_PATHS.all { Files.exists(extractedRoot.resolve(it), LinkOption.NOFOLLOW_LINKS) }) {
                "Ubuntu rootfs archive is incomplete"
            }
            configureRootFileSystem(extractedRoot)
            Files.write(extractedRoot.resolve(INSTALL_MARKER), "$RUNTIME_VERSION\n".encodeToByteArray())
            deleteRuntimeTree(rootFileSystem)
            moveAtomically(extractedRoot, rootFileSystem)
        } finally {
            deleteRuntimeTree(stagingDirectory)
        }
    }

    private suspend fun verifyRootfsAsset() {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(ROOTFS_ASSET_NAME).buffered().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        require(actual.equals(ROOTFS_SHA256, ignoreCase = true)) {
            "Ubuntu rootfs asset checksum mismatch"
        }
    }

    private suspend fun extractRootfsAsset() {
        context.assets.open(ROOTFS_ASSET_NAME).buffered().use { asset ->
            XZCompressorInputStream(asset).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = tar.nextEntry ?: break
                        extractEntry(tar, entry)
                    }
                }
            }
        }
    }

    private suspend fun extractEntry(tar: TarArchiveInputStream, entry: TarArchiveEntry) {
        val relative = validatedRootfsRelativePath(entry.name) ?: return
        val target = stagingDirectory.resolve(ROOTFS_ARCHIVE_DIRECTORY).resolve(relative).normalize()
        require(target.startsWith(stagingDirectory.resolve(ROOTFS_ARCHIVE_DIRECTORY))) {
            "Rootfs archive entry escapes the install directory: ${entry.name}"
        }
        ensureSafeParent(target.parent)
        when {
            entry.isDirectory -> Files.createDirectories(target)
            entry.isSymbolicLink -> {
                require('\u0000' !in entry.linkName) { "Invalid rootfs symlink" }
                Files.createSymbolicLink(target, Path.of(entry.linkName))
            }
            entry.isFile -> {
                Files.newOutputStream(target).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = tar.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                chmod(target, entry.mode)
            }
            // /dev is bind-mounted at runtime; device nodes cannot and should not be created by an Android app UID.
            entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> Unit
            else -> throw IOException("Unsupported rootfs archive entry: ${entry.name}")
        }
        if (entry.isDirectory) chmod(target, entry.mode)
    }

    private fun ensureSafeParent(parent: Path?) {
        if (parent == null) return
        val root = stagingDirectory.resolve(ROOTFS_ARCHIVE_DIRECTORY)
        var current = root
        Files.createDirectories(root)
        root.relativize(parent).forEach { component ->
            current = current.resolve(component)
            require(!Files.isSymbolicLink(current)) { "Rootfs archive would traverse a symlink" }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current)
        }
    }

    private fun chmod(path: Path, archiveMode: Int) {
        runCatching { Os.chmod(path.toString(), archiveMode and UNIX_PERMISSION_MASK) }
    }

    private fun configureRootFileSystem(root: Path) {
        Files.createDirectories(root.resolve("workspace"))
        Files.createDirectories(root.resolve("root"))
        Files.createDirectories(root.resolve("tmp"))
        Files.write(
            root.resolve("etc/resolv.conf"),
            "nameserver 1.1.1.1\nnameserver 8.8.8.8\n".encodeToByteArray(),
        )
        Files.write(
            root.resolve("etc/apt/sources.list"),
            ("""
                deb http://ports.ubuntu.com/ubuntu-ports noble main restricted universe multiverse
                deb http://ports.ubuntu.com/ubuntu-ports noble-updates main restricted universe multiverse
                deb http://ports.ubuntu.com/ubuntu-ports noble-security main restricted universe multiverse
                deb http://ports.ubuntu.com/ubuntu-ports noble-backports main restricted universe multiverse
            """.trimIndent() + "\n").encodeToByteArray(),
        )
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun deleteRuntimeTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        require(path.normalize().startsWith(runtimeDirectory.normalize()) && path.normalize() != runtimeDirectory.normalize()) {
            "Refusing to delete outside the Ubuntu runtime directory"
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            Files.newDirectoryStream(path).use { children -> children.forEach(::deleteRuntimeTree) }
        }
        Files.deleteIfExists(path)
    }

    private companion object {
        val InstallMutex = Mutex()
    }
}

private const val RUNTIME_DIRECTORY_NAME = "ubuntu_runtime"
private const val RUNTIME_VERSION = "ubuntu-noble-operit-pd-v4.18.0-kcode-1"
private const val ROOTFS_ASSET_NAME = "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"
private const val ROOTFS_ARCHIVE_DIRECTORY = "ubuntu-noble-aarch64"
private const val ROOTFS_SHA256 = "91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa"
private const val PROOT_LIBRARY_NAME = "libkcode_proot.so"
private const val PROOT_LOADER_LIBRARY_NAME = "libkcode_proot_loader.so"
private const val SUPPORTED_ABI = "arm64-v8a"
private const val INSTALL_MARKER = ".kcode_ubuntu_installed"
private const val DEFAULT_UBUNTU_WORKING_DIRECTORY = "/workspace"
private const val MAX_UBUNTU_SHELL_COMMAND_CHARS = 65_536
private const val COPY_BUFFER_BYTES = 64 * 1024
private const val PROCESS_POLL_MILLIS = 25L
private const val MINIMUM_FREE_SPACE_BYTES = 384L * 1024L * 1024L
private const val UNIX_PERMISSION_MASK = 0x1ff
private const val ANDROID_UID_USER_RANGE = 100_000
private val REQUIRED_ROOTFS_PATHS = listOf("bin/bash", "usr/bin/env", "usr/bin/apt", "usr/bin/python3", "etc/os-release")
