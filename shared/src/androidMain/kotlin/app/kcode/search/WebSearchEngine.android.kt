package app.kcode.search

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun createWebSearchEngine(): HttpClientEngine = OkHttp.create()
