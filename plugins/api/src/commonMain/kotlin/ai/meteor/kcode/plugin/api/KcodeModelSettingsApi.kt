package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.settings.ModelSettingsPolicy
import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

class KcodeModelSettings(ctx: Context, val policy: ModelSettingsPolicy) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeModelSettings>("modelSettings") }
}
