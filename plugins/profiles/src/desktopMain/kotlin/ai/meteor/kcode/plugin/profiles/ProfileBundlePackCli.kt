package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.cordis.packages.PackageTarget

@Serializable
internal data class ProfileBundlePackRequest(
    val bundle: String,
    val code: List<ProfileBundlePackCode> = emptyList(),
    val targets: List<PackageTarget>,
    val output: String,
)

@Serializable
internal data class ProfileBundlePackCode(val archive: String, val sha256: String)

/** All relative paths are resolved against the request file, independent of the launch directory. */
fun main(args: Array<String>): Unit = runBlocking {
    require(args.size == 1) { "Usage: packProfileBundle -PprofileBundleRequest=<request.json>" }
    val requestFile = File(args.single()).absoluteFile
    val request = Json.decodeFromString(ProfileBundlePackRequest.serializer(), readBundleArchiveJson(requestFile))
    fun resolve(path: String): File {
        require(path.isNotBlank()) { "Empty Bundle pack path" }
        val file = File(path)
        return if (file.isAbsolute) file else File(requestFile.parentFile, path)
    }
    val bundleFile = resolve(request.bundle)
    val bundle = Json.decodeFromString(ProfileBundle.serializer(), readBundleArchiveJson(bundleFile))
    val output = resolve(request.output)
    val outputPath = output.toPath().toAbsolutePath().normalize()
    require(listOf(requestFile, bundleFile).none { it.toPath().toAbsolutePath().normalize() == outputPath }) {
        "Output would replace a pack request or Bundle definition"
    }
    val digest = ProfileBundleArchiveWriter().pack(bundle,
        request.code.map { ProfileBundleArchiveInput(resolve(it.archive), it.sha256) }, request.targets, output)
    println("${output.absolutePath} sha256=$digest")
}
