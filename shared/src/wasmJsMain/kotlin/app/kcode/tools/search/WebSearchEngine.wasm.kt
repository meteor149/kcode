package app.kcode.tools.search

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.js.Js

internal actual fun createWebSearchEngine(): HttpClientEngine = Js.create()
