package app.kcode.history.worker

import androidx.sqlite.driver.web.WebWorkerSQLiteDriver

expect fun createSQLiteWasmWorker(): WebWorkerSQLiteDriver
