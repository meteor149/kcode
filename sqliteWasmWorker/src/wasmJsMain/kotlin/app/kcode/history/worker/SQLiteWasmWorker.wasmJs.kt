package app.kcode.history.worker

import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import org.w3c.dom.Worker

actual fun createSQLiteWasmWorker() = WebWorkerSQLiteDriver(createWorker())

@OptIn(ExperimentalWasmJsInterop::class)
private fun createWorker(): Worker =
    js("""new Worker(new URL("kcode-sqlite-wasm-worker/worker.js", import.meta.url))""")
