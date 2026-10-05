package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.api.profiles.KcodeProfiles
import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileCatalogue
import ai.meteor.kcode.plugin.api.profiles.ProfileCloneRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileCommand
import ai.meteor.kcode.plugin.api.profiles.ProfileCommandHandle
import ai.meteor.kcode.plugin.api.profiles.ProfileCommandPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileCommandStatus
import ai.meteor.kcode.plugin.api.profiles.ProfileCompositionState
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileDraftWrite
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementClient
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementState
import ai.meteor.kcode.plugin.api.profiles.ProfileModuleSummary
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.cordis.plugin

/** The scope belongs to the native host, independently of every product tree. */
class ProfileCommandGateway : ProfileManagementClient {
    private val lock = SynchronizedObject()
    private val root = SupervisorJob()
    private val scope = CoroutineScope(root + Dispatchers.Default)
    private val queue = Channel<Pending>(32)
    private val notifications = Channel<Unit>(Channel.CONFLATED)
    private val pending = linkedMapOf<Long, Pending>()
    private val statuses = linkedMapOf<Long, ProfileCommandStatus>()
    private val mutableState = MutableStateFlow(ProfileManagementState(ProfileManagementPhase.Starting))
    override val state = mutableState.asStateFlow()
    private val mutableCommands = MutableStateFlow<List<ProfileCommandStatus>>(emptyList())
    override val commands = mutableCommands.asStateFlow()
    private var host: KcodeProfileHost? = null
    private var closed = false
    private var sequence = 0L
    private var worker: Job? = null

    private inner class Pending(override val id: Long, val command: ProfileCommand) : ProfileCommandHandle {
        val job = SupervisorJob(root)
        val completion = CompletableDeferred<ProfileCommandStatus>()
        val status = MutableStateFlow(ProfileCommandStatus(id, ProfileCommandPhase.Queued))
        override val state = status.asStateFlow()
        override suspend fun await(): ProfileCommandStatus = completion.await()
        override fun cancel() { job.cancel() }
    }

    fun bind(value: KcodeProfileHost) = synchronized(lock) {
        check(!closed && host == null) { "Profile command gateway is already bound or closed" }
        host = value
        scope.launch { value.state.collect { mutableState.value = managementState(it) } }
        scope.launch {
            for (signal in notifications) {
                val snapshot = synchronized(lock) { statuses.values.toList() }
                mutableCommands.value = snapshot
            }
        }
        worker = scope.launch {
            for (item in queue) {
                val execution = ProfileCommandExecution()
                var result: ProfileCompositionState? = null
                var failure: Throwable? = null
                try {
                    withContext(item.job + execution) {
                        update(item, ProfileCommandStatus(item.id, ProfileCommandPhase.Running))
                        result = when (val command = item.command) {
                            is ProfileCommand.Activate -> value.pluginManager.activateProfile(command.request, command.cancelActive)
                            is ProfileCommand.Edit -> value.pluginManager.editProfile(command.edit)
                            is ProfileCommand.SelectModule -> value.selectProfileModule(command.packageId, command.moduleId, command.expected)
                        }
                    }
                } catch (error: Throwable) { failure = error }
                finally {
                    withContext(NonCancellable) {
                        if (result == null && failure is CancellationException) result = execution.committed
                        val phase = when {
                            result != null -> ProfileCommandPhase.Succeeded
                            failure is CancellationException -> ProfileCommandPhase.Cancelled
                            else -> ProfileCommandPhase.Failed
                        }
                        finish(item, ProfileCommandStatus(item.id, phase, result,
                            if (phase == ProfileCommandPhase.Failed) failure?.message ?: "Profile command failed" else null))
                        item.job.complete()
                    }
                }
            }
        }
    }

    private fun managementState(value: ProfileHostState) = ProfileManagementState(
        phase = when (value.phase) {
            ProfileHostPhase.Ready -> ProfileManagementPhase.Ready
            ProfileHostPhase.Preparing, ProfileHostPhase.Switching -> ProfileManagementPhase.Transitioning
            ProfileHostPhase.RecoveryRequired -> ProfileManagementPhase.RecoveryRequired
            ProfileHostPhase.Closed -> ProfileManagementPhase.Closed
        },
        activeProfileId = value.profileId.takeIf { value.phase == ProfileHostPhase.Ready },
        failure = value.failure?.message,
    )

    private fun backend(): KcodeProfileHost = synchronized(lock) {
        check(!closed) { "Profile command gateway is closed" }
        checkNotNull(host) { "Profile host is starting" }
    }

    override suspend fun catalogue() = backend().pluginManager.profileCatalogue()
    override suspend fun draft(id: String) = backend().pluginManager.profileDraft(id)
    override suspend fun writeDraft(write: ProfileDraftWrite) = backend().pluginManager.writeProfileDraft(write)
    override suspend fun clone(request: ProfileCloneRequest) = backend().pluginManager.cloneProfile(request)
    override suspend fun delete(id: String, expectedRevision: Long) = backend().pluginManager.deleteProfile(id, expectedRevision)
    override suspend fun preview(target: ProfileTarget) = backend().pluginManager.previewProfile(target)
    override suspend fun history(id: String) = backend().pluginManager.profileHistory(id)
    override suspend fun modules() = backend().profileModules()

    override fun submit(command: ProfileCommand): ProfileCommandHandle {
        val detached = detach(command)
        return synchronized(lock) {
            val backend = backend()
            check(backend.state.value.phase != ProfileHostPhase.Closed) {
                "Profile host is not accepting commands"
            }
            check(sequence < Long.MAX_VALUE && pending.size < 32) { "Profile command queue is full" }
            val item = Pending(++sequence, detached)
            pending[item.id] = item
            statuses[item.id] = item.status.value
            notifications.trySend(Unit)
            check(queue.trySend(item).isSuccess) { "Profile command queue was closed" }
            item
        }
    }

    private fun detach(command: ProfileCommand): ProfileCommand = when (command) {
        is ProfileCommand.Activate -> command.copy(request = ProfileActivationRequest(command.request.target.copy(), command.request.expectedRevision))
        is ProfileCommand.Edit -> command.copy(edit = command.edit.copy(operations = Json.decodeFromString(
            ListSerializer(ProfileOperation.serializer()), Json.encodeToString(ListSerializer(ProfileOperation.serializer()), command.edit.operations))))
        is ProfileCommand.SelectModule -> command.copy(expected = command.expected.copy(definition = Json.decodeFromString(
            ProfileDefinition.serializer(), Json.encodeToString(ProfileDefinition.serializer(), command.expected.definition))))
    }

    private fun update(item: Pending, status: ProfileCommandStatus) {
        synchronized(lock) {
            statuses[item.id] = status
            while (statuses.size > 64) {
                val retired = statuses.keys.firstOrNull { it !in pending } ?: break
                statuses.remove(retired)
            }
        }
        item.status.value = status
        notifications.trySend(Unit)
    }

    private fun finish(item: Pending, status: ProfileCommandStatus) {
        synchronized(lock) { pending.remove(item.id) }
        update(item, status)
        item.completion.complete(status)
    }

    suspend fun close() {
        synchronized(lock) { closed = true; queue.close() }
        root.cancel()
        withContext(NonCancellable) {
            worker?.join()
            val remaining = synchronized(lock) { pending.values.toList() }
            remaining.forEach { finish(it, ProfileCommandStatus(it.id, ProfileCommandPhase.Cancelled)) }
            root.join()
            mutableCommands.value = synchronized(lock) { statuses.values.toList() }
            mutableState.value = ProfileManagementState(ProfileManagementPhase.Closed)
        }
    }

    fun pluginMount(): KcodePluginMount = kcodePlugin(
        PluginDescriptor("core.profile-management", "builtin", "host", setOf("profiles")),
        plugin<Unit> { context, _ ->
            val operations = PluginOperationOwner("Profile management bridge")
            val liveLock = SynchronizedObject()
            var live = true
            fun requireLive() = synchronized(liveLock) { check(live) { "Profile management bridge was withdrawn" } }
            collect {
                withContext(NonCancellable) {
                    synchronized(liveLock) { live = false }
                    operations.close()
                }
            }
            val client = object : ProfileManagementClient {
                override val state = this@ProfileCommandGateway.state
                override val commands = this@ProfileCommandGateway.commands
                override suspend fun catalogue(): ProfileCatalogue { requireLive(); return operations.run { this@ProfileCommandGateway.catalogue() } }
                override suspend fun draft(id: String): ProfileDefinition? { requireLive(); return operations.run { this@ProfileCommandGateway.draft(id) } }
                override suspend fun writeDraft(write: ProfileDraftWrite): ProfileCatalogue { requireLive(); return operations.run { this@ProfileCommandGateway.writeDraft(write) } }
                override suspend fun clone(request: ProfileCloneRequest): ProfileCatalogue { requireLive(); return operations.run { this@ProfileCommandGateway.clone(request) } }
                override suspend fun delete(id: String, expectedRevision: Long): ProfileCatalogue { requireLive(); return operations.run { this@ProfileCommandGateway.delete(id, expectedRevision) } }
                override suspend fun preview(target: ProfileTarget): ai.meteor.kcode.plugin.api.profiles.ProfilePreview { requireLive(); return operations.run { this@ProfileCommandGateway.preview(target) } }
                override suspend fun history(id: String): List<ProfileCompositionState> { requireLive(); return operations.run { this@ProfileCommandGateway.history(id) } }
                override suspend fun modules(): List<ProfileModuleSummary> { requireLive(); return operations.run { this@ProfileCommandGateway.modules() } }
                override fun submit(command: ProfileCommand): ProfileCommandHandle = synchronized(liveLock) {
                    check(live) { "Profile management bridge was withdrawn" }
                    this@ProfileCommandGateway.submit(command)
                }
            }
            KcodeProfiles(context, client)
        }, Unit,
    )
}
