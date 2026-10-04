package ai.meteor.kcode.plugin.api

import android.content.Context
import java.nio.file.Files
import java.nio.file.Path

/** Host OS substrate for a caller-owned, fixed-address ARM64 static image. */
object AndroidStaticImageBootstrap {
    /** The caller must expose its private image in its process filesystem namespace. */
    const val ImagePath = "/.__kcode_native_image/image.elf"

    fun executable(context: Context): Path {
        val executable = Path.of(context.applicationInfo.nativeLibraryDir).resolve("libkcode_native_image.so")
        require(Files.isRegularFile(executable) && Files.isExecutable(executable)) {
            "The Android host does not provide the static-image bootstrap"
        }
        return executable
    }
}
