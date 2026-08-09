package app.kcode.tools.search

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

internal actual fun createWebSearchEngine(): HttpClientEngine = CIO.create()
