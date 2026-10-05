package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.KcodeShellMode
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.api.ShellModePolicy
import ai.meteor.kcode.settings.ShellExecutionMode
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** Borrows a caller policy; native executors remain independently loaded consumers. */
class HostShellModeInputPlugin : Plugin<suspend () -> ShellExecutionMode> {
    override val name = "host-shell-mode-input"

    override suspend fun apply(ctx: Context, config: suspend () -> ShellExecutionMode, effect: EffectScope) {
        val owner = PluginOperationOwner(name)
        effect.collect { owner.close() }
        KcodeShellMode(ctx, ShellModePolicy { owner.run { config() } })
    }
}
