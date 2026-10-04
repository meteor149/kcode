package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.tools.search.WebSearchBackend
import ai.meteor.kcode.plugin.api.harness.HarnessFileSystem
import ai.meteor.kcode.plugin.api.harness.HarnessFileObservationEvents
import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

/** File capability with host-independent path identity; consumers never receive java.nio.Path. */
class KcodeFileSystem(
    ctx: Context,
    val backend: FileSystemBackend,
    /** Reserved advanced seam; null means versioned/observation primitives are unavailable. */
    val harness: HarnessFileSystem? = null,
    val observations: HarnessFileObservationEvents? = null,
) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeFileSystem>("fs") }
}

/** Foreground execution seam. The provider owns identity, workdir resolution and cancellation. */
class KcodeShell(ctx: Context, val executor: ShellBackend) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeShell>("shell") }
}

/** Optional Ubuntu execution world, independent of the Android system shell provider. */
class KcodeUbuntuShell(ctx: Context, val executor: ShellBackend) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeUbuntuShell>("ubuntuShell") }
}

/** Search providers are independent from model-facing tools and settings UI. */
class KcodeWebSearch(ctx: Context, val backend: WebSearchBackend) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeWebSearch>("web") }
}
