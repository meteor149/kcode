package ai.meteor.kcode.plugin.searchhttp

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun createWebSearchEngine(): HttpClientEngine = OkHttp.create()
