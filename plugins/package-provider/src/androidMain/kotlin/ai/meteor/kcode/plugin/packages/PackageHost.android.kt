package ai.meteor.kcode.plugin.packages

import android.os.Build
import android.os.Process
import android.content.Context
import java.io.File
import org.cordis.packages.PackageVariant
import org.cordis.packages.PackageHost

fun androidPackageHost(): PackageHost {
    val abi = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS.first() else Build.SUPPORTED_32_BIT_ABIS.first()
    val version = Regex("^[0-9]+(?:\\.[0-9]+){0,3}").find(Build.VERSION.RELEASE)?.value
    return PackageHost("android", androidPackageArch(abi), systemVersion = version, runtimes = mapOf("android-dex" to Build.VERSION.SDK_INT.toString()))
}

fun androidPackageVerifier(context: Context): (File, PackageVariant) -> Unit {
    val packageManager = context.applicationContext.packageManager
    return { file, variant ->
        val info = requireNotNull(packageManager.getPackageArchiveInfo(file.absolutePath, 0)) { "Invalid plugin APK package metadata" }
        require(info.packageName == variant.nativePackageName()) { "Plugin APK package name does not match the variant" }
        val minSdk = requireNotNull(info.applicationInfo).minSdkVersion
        require(minSdk <= Build.VERSION.SDK_INT && minSdk <= requireNotNull(variant.runtime.minVersion).toInt()) { "Plugin APK minSdk exceeds its declared compatibility" }
    }
}
