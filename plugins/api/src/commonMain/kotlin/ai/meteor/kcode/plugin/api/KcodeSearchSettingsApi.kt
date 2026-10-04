package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.tools.search.SearchSettingsPolicy
import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

class KcodeSearchSettings(ctx: Context, val policy: SearchSettingsPolicy) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeSearchSettings>("searchSettings") }
}
