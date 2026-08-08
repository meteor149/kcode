package app.kcode.search

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

internal actual fun createWebSearchEngine(): HttpClientEngine = CIO.create()
