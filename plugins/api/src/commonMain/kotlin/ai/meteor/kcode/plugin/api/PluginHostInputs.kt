package ai.meteor.kcode.plugin.api

import org.cordis.Context
import org.cordis.Disposable
import org.cordis.EffectScope
import org.cordis.InterceptKey

/** Native deployment inputs, not product services or an authorization credential. */
abstract class PluginHostInputs {
    protected abstract fun createLease(): PluginHostInputs
    abstract suspend fun close()
    open fun confirmationDialogs(): ConfirmationDialogHost? = null

    fun bind(context: Context): Context = context.intercept(Key, this)

    private fun lease(effect: EffectScope): PluginHostInputs = createLease().also { leased ->
        effect.collect(Disposable { leased.close() })
    }

    companion object {
        private val Key = InterceptKey<PluginHostInputs>("kcode.plugin.host-inputs")

        /** Each mount gets an independent lease revoked by its effect, including failed apply. */
        fun current(context: Context, effect: EffectScope): PluginHostInputs? =
            context.interceptValues(Key).lastOrNull()?.lease(effect)
    }
}
