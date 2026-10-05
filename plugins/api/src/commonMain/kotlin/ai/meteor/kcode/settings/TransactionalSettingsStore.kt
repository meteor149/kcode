package ai.meteor.kcode.settings

import ai.meteor.kcode.plugin.api.PluginOperationOwner
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns the whole transaction, including feature validation and durable commit. */
fun AppSettingsStore.withTransactions(owner: PluginOperationOwner): AppSettingsStore =
    TransactionalSettingsStore(this, owner)

private class TransactionalSettingsStore(
    private val delegate: AppSettingsStore,
    private val owner: PluginOperationOwner,
) : AppSettingsStore {
    private class ActiveTransaction(val stores: Set<TransactionalSettingsStore>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ActiveTransaction>
    }

    private val writes = Mutex()

    override val protection get() = delegate.protection.also { owner.requireOpen() }
    override suspend fun load(): StoredAppSettings = owner.run { delegate.load() }
    override suspend fun save(settings: StoredAppSettings) = transaction { commit(settings) }

    override suspend fun <T> transaction(block: suspend SettingsTransaction.() -> T): T {
        val active = currentCoroutineContext()[ActiveTransaction]?.stores.orEmpty()
        check(this !in active) { "Settings transactions cannot reenter their store" }
        return owner.run {
            writes.withLock {
                withContext(ActiveTransaction(active + this@TransactionalSettingsStore)) {
                    val live = MutableStateFlow(true)
                    val commits = Mutex()
                    var committed = false
                    val initial = delegate.load()
                    val transaction = object : SettingsTransaction {
                        override val current = initial
                        override suspend fun commit(settings: StoredAppSettings) {
                            check(this@TransactionalSettingsStore in currentCoroutineContext()[ActiveTransaction]?.stores.orEmpty()) {
                                "Settings commit must execute in its transaction"
                            }
                            commits.withLock {
                                check(live.value && !committed) { "Settings transaction is closed or already committed" }
                                owner.requireOpen()
                                currentCoroutineContext().ensureActive()
                                committed = true
                                delegate.save(settings)
                                currentCoroutineContext().ensureActive()
                            }
                        }
                    }
                    try { transaction.block() } finally { live.value = false }
                }
            }
        }
    }
}
