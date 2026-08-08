package app.kcode.search

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

internal actual fun createWebSearchEngine(): HttpClientEngine = Darwin.create()
