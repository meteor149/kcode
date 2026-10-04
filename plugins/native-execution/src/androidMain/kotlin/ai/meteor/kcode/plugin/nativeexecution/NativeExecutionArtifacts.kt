package ai.meteor.kcode.plugin.nativeexecution

import ai.meteor.kcode.plugin.api.PluginCleanupException
import android.content.Context
import android.system.Os
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** Reads only the imported deployment graph; Android's shared resource cache is not involved. */
internal class NativeExecutionArtifacts(
    artifacts: List<File>,
    parent: Path,
) : AutoCloseable {
    constructor(context: Context, artifacts: List<File>) : this(artifacts.toList(), context.cacheDir.toPath())

    private val artifacts = artifacts.toList()
    private val lock = Any()
    private val streams = mutableSetOf<InputStream>()
    internal val directory: Path = run {
        require(artifacts.isNotEmpty()) { "Native execution requires its captured APK graph" }
        Files.createTempDirectory(parent, "kcode-native-artifacts-")
    }
    private var closed = false
    fun openAsset(name: String): InputStream = synchronized(lock) {
        check(!closed) { "Native execution artifacts are closed" }
        require(name.isNotBlank() && !name.startsWith('/') && '\\' !in name && name.split('/').none { it == ".." })
        for (artifact in artifacts) {
            val zip = ZipFile(artifact)
            val entry = zip.getEntry("assets/$name")
            if (entry == null) {
                zip.close()
                continue
            }
            try {
                val stream = object : FilterInputStream(zip.getInputStream(entry)) {
                    override fun close(): Unit = synchronized(lock) {
                        try {
                            super.close()
                        } finally {
                            try {
                                zip.close()
                            } finally {
                                streams.remove(this)
                            }
                        }
                    }
                }
                streams += stream
                return stream
            } catch (error: Throwable) {
                try {
                    zip.close()
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
                throw error
            }
        }
        error("Native plugin asset is absent from its deployment: $name")
    }

    fun executable(name: String): Path = synchronized(lock) {
        check(!closed) { "Native execution artifacts are closed" }
        require(name in setOf("libkcode_proot.so", "libkcode_proot_loader.so"))
        val destination = directory.resolve(name)
        if (Files.exists(destination)) return destination
        for (artifact in artifacts) {
            val copied = ZipFile(artifact).use { zip ->
                val entry = zip.getEntry("lib/arm64-v8a/$name") ?: return@use false
                try {
                    zip.getInputStream(entry).use { input -> Files.newOutputStream(destination).use { input.copyTo(it) } }
                    Os.chmod(destination.toString(), 0b101000000)
                } catch (error: Throwable) {
                    runCatching { Files.deleteIfExists(destination) }.exceptionOrNull()?.let(error::addSuppressed)
                    throw error
                }
                true
            }
            if (copied) {
                return destination
            }
        }
        error("Native plugin executable is absent from its deployment: $name")
    }

    override fun close(): Unit = synchronized(lock) {
        if (closed) return
        closed = true
        val failures = mutableListOf<Throwable>()
        streams.toList().forEach { stream -> runCatching { stream.close() }.exceptionOrNull()?.let(failures::add) }
        streams.clear()
        runCatching {
            Files.newDirectoryStream(directory).use { children ->
                children.forEach { path -> runCatching { Files.delete(path) }.exceptionOrNull()?.let(failures::add) }
            }
        }.exceptionOrNull()?.let(failures::add)
        runCatching { Files.delete(directory) }.exceptionOrNull()?.let(failures::add)
        if (failures.isNotEmpty()) throw PluginCleanupException("Native execution artifacts", failures)
    }
}
