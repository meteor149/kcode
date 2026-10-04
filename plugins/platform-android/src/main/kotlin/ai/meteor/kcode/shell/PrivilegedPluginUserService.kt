package ai.meteor.kcode.shell

import ai.meteor.kcode.platform.PluginHostApiPackages
import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import dalvik.system.DexClassLoader
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Host substrate only: no shell policy, PRoot installer or other product implementation. */
class PrivilegedPluginUserService private constructor(
    private val context: Context?,
    private val storageRoot: File?,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : IPrivilegedPluginBridge.Stub(), AutoCloseable {
    constructor() : this(null, null, Unit)

    @Keep
    constructor(context: Context) : this(context.applicationContext, null, Unit)

    /** Explicit storage root for embedded/instrumented substrate verification. */
    constructor(context: Context, storageRoot: File) : this(context.applicationContext, storageRoot, Unit)

    private val lock = Any()
    private var closed = false
    private var engine: PrivilegedPluginEngine? = null
    private var deployment: File? = null

    override fun uid(): Int = Process.myUid()

    override fun load(
        artifacts: Array<ParcelFileDescriptor>,
        sha256: Array<String>,
        entryClass: String,
    ): IBinder = synchronized(lock) {
        try {
            check(!closed) { "Remote plugin bridge is closed" }
            check(engine == null) { "A remote plugin generation is already deployed" }
            require(artifacts.isNotEmpty() && artifacts.size <= MAX_ARTIFACTS && artifacts.size == sha256.size) {
                "Invalid remote plugin artifact graph"
            }
            require(sha256.all { SHA256.matches(it) }) { "Invalid plugin artifact digest" }
            require(artifacts.all { OsConstants.S_ISREG(Os.fstat(it.fileDescriptor).st_mode) }) {
                "Remote plugin transport requires regular artifact files"
            }
            require(entryClass.isNotBlank() && !isHostClass(entryClass)) { "Engine must be a private plugin class" }
            val hostContext = requireNotNull(context) { "UserService did not provide a host Context" }
            val base = storageRoot ?: if (uid() == 0 || uid() == 2_000) File("/data/local/tmp") else hostContext.cacheDir
            val directory = Files.createTempDirectory(base.toPath(), "kcode-remote-").toFile()
            deployment = directory
            try {
                val files = artifacts.mapIndexed { index, descriptor ->
                    val file = File(directory, "$index.apk")
                    val digest = MessageDigest.getInstance("SHA-256")
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var size = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                size += count
                                require(size <= MAX_ARTIFACT_BYTES) { "Plugin artifact exceeds transport limit" }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    require(actual.equals(sha256[index], ignoreCase = true)) { "Plugin artifact SHA-256 mismatch" }
                    check(file.setReadOnly()) { "Unable to seal remote plugin artifact" }
                    file
                }
                val loader = RemotePluginClassLoader(files, hostContext.classLoader)
                val type = loader.loadClass(entryClass)
                check(type.classLoader === loader) { "Remote engine resolved outside its plugin deployment" }
                check(PrivilegedPluginEngine::class.java.isAssignableFrom(type)) { "Invalid remote plugin engine ABI" }
                val created = type.getConstructor(Context::class.java, List::class.java)
                    .newInstance(hostContext, files.toList()) as PrivilegedPluginEngine
                try {
                    val binder = created.binder
                    engine = created
                    binder
                } catch (error: Throwable) {
                    try { created.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                    throw error
                }
            } catch (error: Throwable) {
                try { releaseDeployment() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        } finally {
            artifacts.forEach { runCatching { it.close() } }
        }
    }

    override fun close(): Unit = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        // Keep deployment files until the engine has joined its operations.
        var failure: Throwable? = null
        try { engine?.close() } catch (error: Throwable) { failure = error }
        engine = null
        try { releaseDeployment() } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
        Unit
    }

    override fun destroy() {
        try { close() } finally { System.exit(0) }
    }

    private fun releaseDeployment() {
        deployment?.let { directory ->
            // All entries were created by this bridge; never follow symlinks from an engine.
            Files.walkFileTree(directory.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    Files.delete(file)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: java.nio.file.Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
            deployment = null
        }
    }

    companion object {
        private const val MAX_ARTIFACTS = 64
        private const val MAX_ARTIFACT_BYTES = 1024L * 1024 * 1024
        private val SHA256 = Regex("[a-fA-F0-9]{64}")
        private val platformPackages = setOf("java", "javax", "android", "kotlin", "kotlinx.coroutines", "org.cordis")
        private fun isHostClass(name: String) = (platformPackages + PluginHostApiPackages).any {
            name == it || name.startsWith("$it.") || name.startsWith("$it$")
        }
    }

    private class RemotePluginClassLoader(files: List<File>, private val host: ClassLoader) : DexClassLoader(
        files.joinToString(File.pathSeparator) { it.path }, null, null, host,
    ) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            findLoadedClass(name)?.let { return@synchronized it }
            if (isHostClass(name)) return@synchronized host.loadClass(name)
            try {
                findClass(name)
            } catch (missing: ClassNotFoundException) {
                // Platform fallback only: missing private code must never resolve from the host APK.
                try { host.parent?.loadClass(name) ?: throw missing } catch (error: ClassNotFoundException) {
                    if (error !== missing) missing.addSuppressed(error)
                    throw missing
                }
            }
        }
    }
}
