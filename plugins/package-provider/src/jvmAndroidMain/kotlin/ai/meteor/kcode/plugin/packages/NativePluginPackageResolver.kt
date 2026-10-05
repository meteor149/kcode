package ai.meteor.kcode.plugin.packages

import ai.meteor.kcode.plugin.CurrentPluginApiVersion
import ai.meteor.kcode.plugin.DynamicPluginSpec
import ai.meteor.kcode.plugin.KcodePluginPackages
import ai.meteor.kcode.plugin.PluginPackageImport
import ai.meteor.kcode.plugin.PluginPackageInstallation
import ai.meteor.kcode.plugin.PluginPackageResolver
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.api.StoredPluginConfiguration
import ai.meteor.kcode.plugin.api.validatePackageDependencies
import ai.meteor.kcode.platform.PluginHostApiPackages
import java.io.File
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.ConfigValidator
import org.cordis.Plugin
import org.cordis.packages.DeployedPluginPackage
import org.cordis.packages.PackageHost
import org.cordis.packages.PackageVariant
import org.cordis.packages.PluginPackageArchive
import org.cordis.packages.resolvePackageGraph

class NativePluginPackagesPlugin(
    private val directory: File,
    private val host: PackageHost,
    private val runtimeAbi: String = NativePackageRuntimeAbi,
    private val artifactVerifier: ((File, PackageVariant) -> Unit)? = null,
) : Plugin<Unit> {
    override val name = "native-plugin-packages"
    override val config = ConfigValidator<Unit> { it }

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val owner = PluginOperationOwner("Plugin package imports")
        val resolver = NativePluginPackageResolver(directory, host, runtimeAbi, artifactVerifier)
        KcodePluginPackages(ctx, object : PluginPackageResolver {
            override suspend fun resolve(imports: List<PluginPackageImport>, installed: List<DynamicPluginSpec>) = owner.run {
                resolver.resolve(imports, installed)
            }
            override suspend fun verify(spec: DynamicPluginSpec) = owner.run { resolver.verify(spec) }
        })
        effect.collect { owner.close() }
    }
}

/** Stateless deployment adapter. Published generations are retained for loader leases/recovery. */
class NativePluginPackageResolver(
    directory: File,
    private val host: PackageHost,
    val runtimeAbi: String = NativePackageRuntimeAbi,
    private val artifactVerifier: ((File, PackageVariant) -> Unit)? = null,
) : PluginPackageResolver {
    private val root = directory.toPath().toAbsolutePath().normalize().resolve("packages")
    private val archives = PluginPackageArchive()

    init {
        host.validate()
        require(host.system in setOf("windows", "macos", "linux", "android")) { "Unsupported native package host" }
        require(host.runtimes.keys == setOf(if (host.system == "android") "android-dex" else "jvm")) { "Native package provider advertises only its actual loader" }
        require(runtimeAbi.matches(Regex("[a-f0-9]{64}"))) { "Invalid host SDK ABI" }
    }

    override suspend fun resolve(imports: List<PluginPackageImport>, installed: List<DynamicPluginSpec>): List<DynamicPluginSpec> = withContext(Dispatchers.IO) {
        require(imports.isNotEmpty()) { "No plugin packages supplied" }
        // Inspect every input and dependency graph before creating deployment generations.
        val inspected = imports.map { interruptiblePackageOperation { archives.inspect(File(it.archivePath), it.sha256) } }
        require(inspected.map { it.manifest.id }.distinct().size == inspected.size) { "Duplicate imported package identity" }
        val importedIds = inspected.map { it.manifest.id }.toSet()
        val retained = installed.filter { it.packageInstallation != null && it.id !in importedIds }.map { spec ->
            verify(spec)
            val release = requireNotNull(spec.packageInstallation)
            interruptiblePackageOperation { archives.inspect(File(release.archivePath), release.archiveSha256).manifest }
        }
        val ordered = resolvePackageGraph(retained + inspected.map { it.manifest })
        inspected.forEach { release ->
            release.manifest.kcodeConfiguration()
            release.manifest.variants.forEach { it.kcodeMetadata() }
            release.manifest.select(host, ::compatible)
            installed.firstOrNull { it.id == release.manifest.id && it.version == release.manifest.version }?.packageInstallation?.let { previous ->
                require(previous.archiveSha256 == release.archiveSha256) { "A package release cannot be republished with different bytes" }
            }
        }
        val candidates = ordered.filter { it.id in importedIds }.map { manifest ->
            val index = inspected.indexOfFirst { it.manifest.id == manifest.id }
            val request = imports[index]
            val previous = installed.firstOrNull { it.id == manifest.id }
            val variant = manifest.select(host, ::compatible)
            val deployed = interruptiblePackageOperation { archives.deploy(File(request.archivePath), request.sha256, root.toFile()) }
            if (variant.runtime.id == "android-dex") {
                require(deployed.artifact(variant).setReadOnly()) { "Cannot make plugin DEX container read-only" }
            }
            verifyArtifact(deployed, variant)
            val metadata = variant.kcodeMetadata()
            DynamicPluginSpec(
                id = manifest.id,
                version = manifest.version,
                entryClass = variant.runtime.entryPoint,
                artifactPath = deployed.artifact(variant).absolutePath,
                sha256 = manifest.files.single { it.path == variant.artifact }.sha256,
                config = (request.configuration ?: previous?.let { StoredPluginConfiguration.encode(it.config) } ?: manifest.kcodeConfiguration()).decode(),
                packageName = variant.nativePackageName(),
                capabilities = metadata.capabilities,
                apiVersion = metadata.pluginApi,
                enabled = request.enabled ?: previous?.enabled ?: true,
                packageInstallation = PluginPackageInstallation(
                    archivePath = deployed.archive.absolutePath,
                    archiveSha256 = request.sha256,
                    variantId = variant.id,
                    runtimeAbi = metadata.runtimeAbi,
                    dependencies = manifest.dependencies.associate { it.id to it.version },
                ),
            )
        }
        validatePackageDependencies(installed.filterNot { it.id in importedIds } + candidates)
        candidates
    }

    override suspend fun verify(spec: DynamicPluginSpec) = withContext(Dispatchers.IO) {
        val lock = requireNotNull(spec.packageInstallation) { "Missing package installation" }
        require(!Files.isSymbolicLink(root)) { "Invalid package root" }
        val expected = root.toRealPath().resolve(lock.archiveSha256).resolve("release.kplugin")
        require(!Files.isSymbolicLink(expected.parent) && !Files.isSymbolicLink(expected)) { "Invalid package generation" }
        require(File(lock.archivePath).toPath().toRealPath() == expected.toRealPath()) { "Package archive is outside its immutable generation" }
        val deployed = interruptiblePackageOperation { archives.verifyDeployment(expected.parent.toFile(), lock.archiveSha256) }
        val manifest = deployed.manifest
        val variant = manifest.select(host, ::compatible)
        val metadata = variant.kcodeMetadata()
        manifest.kcodeConfiguration()
        require(spec.id == manifest.id && spec.version == manifest.version && lock.variantId == variant.id) { "Package release lock mismatch" }
        require(lock.runtimeAbi == metadata.runtimeAbi && spec.apiVersion == metadata.pluginApi && spec.capabilities == metadata.capabilities) { "Package SDK metadata mismatch" }
        require(spec.entryClass == variant.runtime.entryPoint && spec.packageName == variant.nativePackageName() && spec.dependencies.isEmpty()) { "Package native descriptor mismatch" }
        require(File(spec.artifactPath).toPath().toRealPath() == deployed.artifact(variant).toPath().toRealPath()) { "Package artifact path mismatch" }
        require(spec.sha256 == manifest.files.single { it.path == variant.artifact }.sha256) { "Package artifact digest mismatch" }
        require(lock.dependencies == manifest.dependencies.associate { it.id to it.version }) { "Package dependency lock mismatch" }
        verifyArtifact(deployed, variant)
    }

    private fun compatible(variant: PackageVariant): Boolean = variant.kcodeMetadata().let {
        variant.nativePackageName()
        it.pluginApi == CurrentPluginApiVersion && it.runtimeAbi == runtimeAbi
    }

    private fun verifyArtifact(deployed: DeployedPluginPackage, variant: PackageVariant) {
        ZipFile(deployed.artifact(variant)).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val names = entries.map { it.name }
            require(names.distinct().size == names.size) { "Duplicate native artifact entries" }
            require(names.none { name ->
                name.endsWith(".class") && PluginHostApiPackages.any { prefix ->
                    val path = prefix.replace('.', '/')
                    name.startsWith("$path/") || name == "$path.class" || name.startsWith("$path$")
                }
            }) { "Plugin bundles duplicate host-shared classes" }
            if (variant.runtime.id == "jvm") {
                require(zip.getEntry(variant.runtime.entryPoint.replace('.', '/') + ".class") != null) { "Plugin entry class is missing" }
                require(names.none { it.endsWith(".so") || it.endsWith(".dll") || it.endsWith(".dylib") }) { "Loose JVM native libraries require a future package extension" }
                entries.filter { it.name.endsWith(".class") }.forEach { entry ->
                    zip.getInputStream(entry).use { stream ->
                        val header = java.io.DataInputStream(stream)
                        require(header.readInt() == 0xcafebabe.toInt()) { "Invalid JVM class" }
                        val minor = header.readUnsignedShort()
                        val major = header.readUnsignedShort()
                        require(minor != 65535 && major in 45..requireNotNull(variant.runtime.minVersion).toInt() + 44 && major <= host.runtimes.getValue("jvm").toInt() + 44) { "Incompatible JVM bytecode: ${entry.name}" }
                    }
                }
            } else {
                require(!deployed.artifact(variant).canWrite()) { "Plugin DEX container must be read-only" }
                require("AndroidManifest.xml" in names && names.any { it.matches(Regex("classes[0-9]*\\.dex")) }) { "Plugin APK must contain manifest and DEX" }
                val nativeAbis = names.filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.toSet()
                val mappedAbis = nativeAbis.map { androidPackageArch(it) }.toSet()
                require(nativeAbis.isEmpty() || host.arch in mappedAbis) { "APK native code is incompatible with the process ABI" }
                val defined = entries.filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }.flatMap { entry ->
                    zip.getInputStream(entry).use { dexDefinedClasses(it.readBytes()) }
                }.toSet()
                require(variant.runtime.entryPoint.replace('.', '/') in defined) { "Plugin entry class is missing from DEX" }
                require(defined.none { name -> PluginHostApiPackages.any { prefix ->
                    val path = prefix.replace('.', '/')
                    name.startsWith("$path/") || name == path || name.startsWith("$path$")
                } }) { "Plugin APK bundles duplicate host-shared classes" }
                requireNotNull(artifactVerifier) { "Android package verification requires a platform verifier" }
            }
        }
        artifactVerifier?.invoke(deployed.artifact(variant), variant)
    }
}

fun androidPackageArch(abi: String): String = when (abi) {
    "arm64-v8a" -> "arm64"
    "armeabi-v7a" -> "armv7"
    "x86_64" -> "x86_64"
    "x86" -> "x86"
    else -> error("Unsupported Android process ABI: $abi")
}

/** Java IO reports thread interruption as IO failure; a cancelled owner must stay cancelled. */
internal suspend fun <T> interruptiblePackageOperation(block: () -> T): T = try {
    runInterruptible(block = block)
} catch (error: InterruptedIOException) {
    currentCoroutineContext().ensureActive()
    throw error
}
