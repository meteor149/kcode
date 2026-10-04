package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.localization.TranslationCatalog
import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

class KcodeLocalization(ctx: Context, val catalog: TranslationCatalog) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeLocalization>("localization") }
}
