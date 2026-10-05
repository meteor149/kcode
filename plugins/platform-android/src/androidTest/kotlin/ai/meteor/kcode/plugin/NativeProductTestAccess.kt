package ai.meteor.kcode.plugin

import ai.meteor.kcode.KcodeAgentRuntime

/** Implementation lifecycle assertions run on an idle host; product handles never enter app APIs. */
internal fun KcodeAgentRuntime.productRuntimeForTest(): KcodePluginRuntime {
    val host = requireNotNull(owner)
    if (host is KcodePluginRuntime) return host
    require(host is KcodeProfileHost)
    val current = host.javaClass.getDeclaredField("current").apply { isAccessible = true }.get(host) as KcodeAgentRuntime
    return current.owner as KcodePluginRuntime
}
