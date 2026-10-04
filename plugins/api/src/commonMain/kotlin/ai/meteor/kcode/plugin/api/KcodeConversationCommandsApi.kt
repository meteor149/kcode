package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.chat.ConversationCommand
import ai.meteor.kcode.chat.ConversationCommandContribution
import ai.meteor.kcode.chat.ConversationCommandRequest
import ai.meteor.kcode.chat.ConversationCommandSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.Service
import org.cordis.ServiceKey

class KcodeConversationCommands(ctx: Context) : Service<Unit>(ctx, Key) {
    private val mutex = Mutex()
    private val contributions = linkedMapOf<String, ConversationCommandContribution>()
    private val committed = MutableStateFlow(ConversationCommandSnapshot())
    val committedSnapshot: ConversationCommandSnapshot get() = committed.value

    suspend fun register(contribution: ConversationCommandContribution): Disposable {
        require(contribution.id.isNotBlank()) { "command id must not be blank" }
        val live = MutableStateFlow(true)
        val jobs = mutableSetOf<Job>()
        val executing = mutableMapOf<Job, Job?>()
        val ownerLock = Mutex()
        val registered = contribution.copy(match = { prompt ->
            if (!live.value) null else contribution.match(prompt)?.let { command ->
                object : ConversationCommand {
                    override val allowedDuringGeneration = command.allowedDuringGeneration
                    override suspend fun execute(request: ConversationCommandRequest) {
                        val parent = currentCoroutineContext()[Job]
                        val operation = ownerLock.withLock {
                            check(live.value) { "command '${contribution.id}' is disposed" }
                            Job(parent).also { jobs += it }
                        }
                        try {
                            withContext(operation) {
                                ownerLock.withLock { executing[operation] = currentCoroutineContext()[Job] }
                                command.execute(request)
                            }
                        } finally {
                            operation.complete()
                            withContext(NonCancellable) {
                                ownerLock.withLock { jobs -= operation; executing.remove(operation) }
                            }
                        }
                    }
                }
            }
        })
        mutex.withLock {
            require(contribution.id !in contributions) { "command '${contribution.id}' is already registered" }
            contributions[contribution.id] = registered
        }
        return Disposable {
            val caller = currentCoroutineContext()[Job]
            withContext(NonCancellable) {
                val running = ownerLock.withLock {
                    check(caller == null || caller !in executing.values) { "a command cannot dispose its own registration" }
                    live.value = false
                    jobs.toList().also { owned -> owned.forEach { it.cancel() } }
                }
                mutex.withLock {
                    if (contributions[contribution.id] === registered) contributions.remove(contribution.id)
                }
                running.forEach { it.join() }
            }
        }
    }

    suspend fun snapshot(): ConversationCommandSnapshot = mutex.withLock {
        ConversationCommandSnapshot(contributions.values.sortedWith(
            compareBy(ConversationCommandContribution::order, ConversationCommandContribution::id),
        ))
    }

    /** Called at the runtime composition commit, after installation persistence succeeds. */
    suspend fun commitSnapshot(): ConversationCommandSnapshot = snapshot().also { committed.value = it }

    companion object { val Key = ServiceKey<KcodeConversationCommands>("conversationCommands") }
}
