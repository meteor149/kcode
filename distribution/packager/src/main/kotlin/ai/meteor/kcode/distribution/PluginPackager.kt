package ai.meteor.kcode.distribution

import ai.meteor.kcode.plugin.api.StoredPluginConfiguration
import ai.meteor.kcode.plugin.packages.kcodeConfigurationExtension
import ai.meteor.kcode.plugin.packages.kcodeVariantExtension
import ai.meteor.kcode.plugin.packages.nativePackageName
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import org.cordis.packages.PackageFile
import org.cordis.packages.PackageRuntime
import org.cordis.packages.PackageVariant
import org.cordis.packages.PackageTarget
import org.cordis.packages.PluginPackageCodec
import org.cordis.packages.PluginPackageArchive
import org.cordis.packages.PluginPackageManifest
import org.cordis.packages.packageFileSha256

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--catalog") {
        require(args.size == 2)
        val directory = File(args[1])
        val records = directory.listFiles().orEmpty().filter { it.extension == "kplugin" }.sortedBy { it.name }.map { file ->
            val manifest = ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry("plugin.json")).use { PluginPackageCodec.decode(it.readBytes()) } }
            buildJsonObject {
                put("id", manifest.id)
                put("file", file.name)
                put("sha256", packageFileSha256(file))
                put("variants", JsonArray(manifest.variants.map { Json.encodeToJsonElement(it) }))
            }
        }
        require(records.isNotEmpty())
        File(directory, "index.json").writeText(Json.encodeToString(records) + "\n")
        return
    }
    require(args.size in 10..13 && args.take(3).none { it == "-" } && args[8] != "-") { "Expected package arguments and optional target JSON file" }
    val declaredTargets = args.getOrNull(12)?.takeUnless { it == "-" }?.let {
        Json.decodeFromString<List<PackageTarget>>(File(it).readText()).also { values ->
            require(values.isNotEmpty())
            values.forEach { target -> target.validate() }
            require(values.all { target -> target.system in setOf("windows", "macos", "linux", "android") }) { "Use the generic Cordis packer for other loaders or iOS artifacts" }
        }
    }
    val androidEntry = args.getOrNull(11)?.takeUnless { it == "-" } ?: args[2]
    val capabilities = args.getOrNull(10)?.takeUnless { it == "-" }?.split(',')?.map { it.trim() }?.toSet().orEmpty()
    require(capabilities.none { it.isBlank() }) { "Capabilities must be nonempty identities" }
    val output = File(args[8]).absoluteFile
    output.parentFile.mkdirs()
    val payload = Files.createTempDirectory(output.parentFile.toPath(), ".payload-").toFile()
    try {
        val variants = mutableListOf<PackageVariant>()
        val records = mutableListOf<PackageFile>()
        fun copyArtifact(path: String, source: String): File {
            val target = File(payload, path)
            target.parentFile.mkdirs()
            File(source).copyTo(target)
            records += PackageFile(path, target.length(), packageFileSha256(target))
            return target
        }
        if (args[3] != "-") {
            require(args[4] != "-") { "Desktop SDK ABI descriptor is required" }
            val targets = declaredTargets?.filter { it.system != "android" } ?: listOf("windows", "macos", "linux").map { PackageTarget(it, listOf("arm", "x86")) }
            if (targets.isNotEmpty()) {
                copyArtifact("targets/desktop/plugin.jar", args[3])
                variants += PackageVariant(id = "desktop", artifact = "targets/desktop/plugin.jar", runtime = PackageRuntime("jvm", args[2], "17"),
                    extensions = kcodeVariantExtension(packageFileSha256(File(args[4])), capabilities), targets = targets)
            }
        }
        if (args[5] != "-") {
            require(args[6] != "-" && args[7] != "-") { "Android package name and SDK ABI descriptor are required" }
            val nativeAbis = ZipFile(File(args[5])).use { zip ->
                zip.entries().asSequence().mapNotNull {
                    Regex("lib/([^/]+)/[^/]+\\.so").matchEntire(it.name)?.groupValues?.get(1)
                }.toSet()
            }
            require(nativeAbis.isEmpty() || declaredTargets != null) { "APK contains native libraries; declare exact system/family/bitness targets" }
            val targets = declaredTargets?.filter { it.system == "android" } ?: listOf(PackageTarget("android", listOf("arm", "x86"), minSystemVersion = "15"))
            if (nativeAbis.isNotEmpty()) {
                val abiNames = mapOf("arm" to mapOf(32 to "armeabi-v7a", 64 to "arm64-v8a"), "x86" to mapOf(32 to "x86", 64 to "x86_64"))
                require(targets.all { target -> target.arch.all { family -> target.bits.all { bits -> abiNames.getValue(family).getValue(bits) in nativeAbis } } }) { "Target architecture/bitness is missing from APK native libraries" }
            }
            if (targets.isNotEmpty()) {
                copyArtifact("targets/android/plugin.apk", args[5])
                targets.forEachIndexed { index, target ->
                    variants += PackageVariant(id = if (targets.size == 1) "android" else "android-$index",
                        artifact = "targets/android/plugin.apk", runtime = PackageRuntime("android-dex", androidEntry, "35", buildJsonObject { put("packageName", args[6]) }),
                        extensions = kcodeVariantExtension(packageFileSha256(File(args[7])), capabilities), targets = listOf(target))
                }
            }
        }
        val configuration = if (args[9] == "-") StoredPluginConfiguration("unit") else
            Json.decodeFromString<StoredPluginConfiguration>(File(args[9]).readText()).also { it.decode() }
        val version = if (args[1].startsWith("@content:")) {
            val identity = listOf(args[0], args[2], records.toString(), variants.toString(), configuration.toString()).joinToString("\n")
            val hash = MessageDigest.getInstance("SHA-256").digest(identity.encodeToByteArray()).joinToString("") { "%02x".format(it) }
            "${args[1].removePrefix("@content:")}+content.$hash"
        } else args[1]
        require(declaredTargets == null || declaredTargets.all { target -> variants.any { target in it.targets } }) { "Declared target has no artifact" }
        val manifest = PluginPackageManifest(formatVersion = 1, id = args[0], version = version, variants = variants, files = records, extensions = kcodeConfigurationExtension(configuration))
        manifest.variants.forEach { it.nativePackageName() }
        val digest = PluginPackageArchive().pack(manifest, payload, output)
        File(output.parentFile, output.name + ".sha256").writeText("$digest\n")
        println("${output.absolutePath}\nSHA-256: $digest")
    } finally { payload.deleteRecursively() }
}
