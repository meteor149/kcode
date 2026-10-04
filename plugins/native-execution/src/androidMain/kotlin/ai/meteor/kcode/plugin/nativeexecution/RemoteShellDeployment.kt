package ai.meteor.kcode.plugin.nativeexecution

import ai.meteor.kcode.AgentShellExecutor
import ai.meteor.kcode.plugin.api.PluginCodeOrigin
import ai.meteor.kcode.plugin.nativeexecution.shell.PrivilegedShellUserService
import ai.meteor.kcode.shell.IPrivilegedPluginBridge
import ai.meteor.kcode.shell.IPrivilegedShellService
import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun deployRemoteShell(
    bridge: IPrivilegedPluginBridge,
    context: Context,
    origin: PluginCodeOrigin?,
    implementationLoader: ClassLoader?,
): IPrivilegedShellService = withContext(Dispatchers.IO) {
    val artifacts: List<File>
    val digests: List<String>
    if (origin != null) {
        val graph = listOf(origin.artifact) + origin.dependencies
        artifacts = graph.map { File(it.artifactPath) }
        digests = graph.map { it.sha256 }
    } else {
        check(implementationLoader === AgentShellExecutor::class.java.classLoader) {
            "External native execution requires its captured PluginCodeOrigin"
        }
        artifacts = (listOf(context.applicationInfo.sourceDir) + context.applicationInfo.splitSourceDirs.orEmpty()).map(::File)
        digests = artifacts.map { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
    val descriptors = mutableListOf<ParcelFileDescriptor>()
    try {
        artifacts.forEach { descriptors += ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        IPrivilegedShellService.Stub.asInterface(bridge.load(
            descriptors.toTypedArray(), digests.toTypedArray(), PrivilegedShellUserService::class.java.name,
        )) ?: error("Remote plugin engine returned no shell binder")
    } finally {
        descriptors.forEach { runCatching { it.close() } }
    }
}
