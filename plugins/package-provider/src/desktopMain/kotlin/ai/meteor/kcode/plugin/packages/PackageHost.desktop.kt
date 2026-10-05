package ai.meteor.kcode.plugin.packages

import org.cordis.packages.PackageHost
import org.cordis.packages.validateSystemVersion
import java.util.concurrent.TimeUnit

fun desktopPackageHost(): PackageHost {
    val osName = System.getProperty("os.name").lowercase()
    val os = when {
        "windows" in osName -> "windows"
        "mac" in osName || "darwin" in osName -> "macos"
        "linux" in osName -> "linux"
        else -> error("Unsupported desktop OS: $osName")
    }
    val arch = when (val raw = System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "arm64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        "arm", "armv7", "armv7l" -> "armv7"
        else -> error("Unsupported desktop process architecture: $raw")
    }
    val reported = Regex("^[0-9]+(?:\\.[0-9]+){0,3}").find(System.getProperty("os.version"))?.value
    val version = if (os == "windows" && reported != null) windowsSystemVersion(reported) else reported
    return PackageHost(
        system = os,
        arch = arch,
        systemVersion = version,
        distribution = if (os == "linux") readLinuxDistribution() else null,
        runtimes = mapOf("jvm" to Runtime.version().feature().toString()),
    )
}

/** Windows 10/11 share NT 10.0; both build and update revision affect bounded targets. */
private fun windowsSystemVersion(reported: String): String? {
    if (reported.split('.').size == 4) return reported
    val build = Regex("CurrentBuildNumber\\s+REG_SZ\\s+([0-9]{1,9})").find(readWindowsVersionValue("CurrentBuildNumber").orEmpty())?.groupValues?.get(1)
    val rawRevision = Regex("UBR\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)").find(readWindowsVersionValue("UBR").orEmpty())?.groupValues?.get(1)
    val revision = rawRevision?.toLongOrNull(16)?.takeIf { it <= 999_999_999 }?.toString()
    return completeWindowsSystemVersion(reported, build, revision)
}

internal fun completeWindowsSystemVersion(reported: String, build: String?, revision: String?): String? {
    val parts = reported.split('.')
    if (parts.size < 2 || revision == null) return null
    val actualBuild = parts.getOrNull(2) ?: build ?: return null
    val version = "${parts[0]}.${parts[1]}.$actualBuild.$revision"
    return version.takeIf { runCatching { validateSystemVersion(it) }.isSuccess }
}

private fun readWindowsVersionValue(name: String): String? = runCatching {
    val process = ProcessBuilder("reg.exe", "query", "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", "/v", name)
        .redirectErrorStream(true).start()
    try {
        if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) return@runCatching null
        process.inputStream.use { it.readNBytes(4096).decodeToString() }
    } finally {
        if (process.isAlive) { process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS) }
        listOf(process.inputStream, process.outputStream, process.errorStream).forEach { stream -> runCatching { stream.close() } }
    }
}.getOrNull()
