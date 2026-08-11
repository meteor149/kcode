@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ai.meteor.kcode

import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.StoredConversation
import ai.meteor.kcode.history.StoredMessage
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.history.ThreadGoalStatus
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.rename
import kotlin.concurrent.Volatile
import kotlin.experimental.ExperimentalNativeApi

@Volatile
private var storageDirectory: String? = null

@OptIn(ExperimentalNativeApi::class)
@CName("KcodeInitializeStorage")
fun KcodeInitializeStorage(path: CPointer<ByteVar>?) {
    storageDirectory = path?.toKString()?.trimEnd('/', '\\')?.takeIf(String::isNotBlank)
}

internal object OhosAppSettingsStore : AppSettingsStore {
    override val protection = SettingsProtection.HarmonySandbox
    private val mutex = Mutex()

    override suspend fun load(): StoredAppSettings = mutex.withLock {
        val root = NativeFileStore.read(SettingsFile)?.let(::parseObject) ?: return StoredAppSettings()
        StoredAppSettings(
            provider = root.string("provider", "OpenAI"),
            modelId = root.string("modelId", "gpt-4o-mini"),
            modelApiKeys = root["modelApiKeys"]?.jsonObject.orEmpty()
                .mapValues { (_, value) -> value.jsonPrimitive.contentOrNull.orEmpty() },
            modelEndpoint = root.string("modelEndpoint"),
            modelRegion = root.string("modelRegion"),
            modelDeployment = root.string("modelDeployment"),
            modelApiVersion = root.string("modelApiVersion"),
            dashscopeRegion = root.string("dashscopeRegion", "china_mainland"),
            webSearchApiKey = root.string("webSearchApiKey"),
            exaSearchApiKey = root.string("exaSearchApiKey"),
            webSearchProvider = root.string("webSearchProvider", "google"),
            temperature = root["temperature"]?.jsonPrimitive?.doubleOrNull ?: 0.7,
            language = root.string("language", "zh"),
            shellExecutionMode = root.string("shellExecutionMode", "app"),
            toolPermissionMode = root.string("toolPermissionMode", "ask"),
        )
    }

    override suspend fun save(settings: StoredAppSettings) = mutex.withLock {
        NativeFileStore.write(SettingsFile, buildJsonObject {
            put("provider", settings.provider)
            put("modelId", settings.modelId)
            putJsonObject("modelApiKeys") {
                settings.modelApiKeys.forEach { (provider, key) -> put(provider, key) }
            }
            put("modelEndpoint", settings.modelEndpoint)
            put("modelRegion", settings.modelRegion)
            put("modelDeployment", settings.modelDeployment)
            put("modelApiVersion", settings.modelApiVersion)
            put("dashscopeRegion", settings.dashscopeRegion)
            put("webSearchApiKey", settings.webSearchApiKey)
            put("exaSearchApiKey", settings.exaSearchApiKey)
            put("webSearchProvider", settings.webSearchProvider)
            put("temperature", settings.temperature)
            put("language", settings.language)
            put("shellExecutionMode", settings.shellExecutionMode)
            put("toolPermissionMode", settings.toolPermissionMode)
        }.toString())
    }
}

internal object OhosConversationHistoryRepository : ConversationHistoryRepository {
    private val mutex = Mutex()

    override suspend fun loadAll(): List<StoredConversation> = mutex.withLock {
        readConversations().sortedWith(
            compareByDescending<StoredConversation> { it.isPinned }.thenByDescending { it.updatedAt },
        )
    }

    override suspend fun appendMessage(
        conversationId: Long,
        title: String,
        messageId: Long,
        role: String,
        content: String,
        isError: Boolean,
    ) = mutate { conversations ->
        val index = conversations.indexOfFirst { it.id == conversationId }
        val existing = conversations.getOrNull(index)
        val timestamp = maxOf(messageId, (existing?.updatedAt ?: 0L) + 1L)
        val messages = existing?.messages.orEmpty().filterNot { it.id == messageId } + StoredMessage(
            id = messageId,
            conversationId = conversationId,
            role = role,
            content = content,
            isError = isError,
            createdAt = timestamp,
        )
        val updated = StoredConversation(
            id = conversationId,
            title = title,
            createdAt = existing?.createdAt ?: timestamp,
            updatedAt = timestamp,
            isPinned = existing?.isPinned ?: false,
            messages = messages.sortedWith(compareBy(StoredMessage::createdAt, StoredMessage::id)),
        )
        if (index >= 0) conversations[index] = updated else conversations += updated
    }

    override suspend fun deleteMessagesFrom(conversationId: Long, messageIdInclusive: Long) = mutate { conversations ->
        val index = conversations.indexOfFirst { it.id == conversationId }
        if (index < 0) return@mutate
        val conversation = conversations[index]
        val messages = conversation.messages.filter { it.id < messageIdInclusive }
        conversations[index] = conversation.copy(
            messages = messages,
            updatedAt = messages.maxOfOrNull(StoredMessage::createdAt) ?: conversation.createdAt,
        )
    }

    override suspend fun setPinned(conversationId: Long, pinned: Boolean) = mutate { conversations ->
        val index = conversations.indexOfFirst { it.id == conversationId }
        if (index >= 0) {
            val conversation = conversations[index]
            conversations[index] = conversation.copy(isPinned = pinned, updatedAt = conversation.updatedAt + 1L)
        }
    }

    override suspend fun setGoal(conversationId: Long, title: String, goal: ThreadGoal) = mutate { conversations ->
        val index = conversations.indexOfFirst { it.id == conversationId }
        val existing = conversations.getOrNull(index)
        val updated = StoredConversation(
            id = conversationId,
            title = title,
            createdAt = existing?.createdAt ?: goal.createdAt,
            updatedAt = maxOf(existing?.updatedAt ?: 0L, goal.updatedAt),
            isPinned = existing?.isPinned ?: false,
            goal = goal,
            messages = existing?.messages.orEmpty(),
        )
        if (index >= 0) conversations[index] = updated else conversations += updated
    }

    override suspend fun clearGoal(conversationId: Long) = mutate { conversations ->
        val index = conversations.indexOfFirst { it.id == conversationId }
        if (index >= 0) conversations[index] = conversations[index].copy(goal = null)
    }

    override suspend fun deleteConversation(conversationId: Long) = mutate { conversations ->
        conversations.removeAll { it.id == conversationId }
    }

    private suspend fun mutate(block: (MutableList<StoredConversation>) -> Unit) = mutex.withLock {
        val conversations = readConversations().toMutableList()
        block(conversations)
        NativeFileStore.write(HistoryFile, conversations.toJson().toString())
    }

    private fun readConversations(): List<StoredConversation> = runCatching {
        NativeFileStore.read(HistoryFile)?.let { text ->
            Json.parseToJsonElement(text).jsonArray.mapNotNull(::storedConversation)
        }.orEmpty()
    }.getOrDefault(emptyList())
}

private object NativeFileStore {
    fun read(name: String): String? {
        val file = fopen(path(name), "rb") ?: return null
        return try {
            if (fseek(file, 0, SEEK_END) != 0) return null
            val size = ftell(file)
            if (size < 0 || fseek(file, 0, SEEK_SET) != 0) return null
            if (size == 0L) return ""
            val bytes = ByteArray(size.toInt())
            val count = bytes.usePinned { pinned ->
                fread(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file).toInt()
            }
            bytes.copyOf(count).decodeToString()
        } finally {
            fclose(file)
        }
    }

    fun write(name: String, text: String) {
        val target = path(name)
        val temporary = "$target.tmp"
        val file = fopen(temporary, "wb") ?: error("无法写入鸿蒙应用沙箱。")
        val bytes = text.encodeToByteArray()
        try {
            val count = bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file).toInt()
            }
            check(count == bytes.size && fflush(file) == 0) { "写入鸿蒙应用数据失败。" }
        } finally {
            fclose(file)
        }
        check(rename(temporary, target) == 0) { "无法提交鸿蒙应用数据文件。" }
    }

    private fun path(name: String): String {
        val directory = checkNotNull(storageDirectory) { "鸿蒙应用存储尚未初始化。" }
        return "$directory/$name"
    }
}

private fun parseObject(text: String): JsonObject? = runCatching {
    Json.parseToJsonElement(text).jsonObject
}.getOrNull()

private fun JsonObject.string(name: String, default: String = ""): String =
    get(name)?.jsonPrimitive?.contentOrNull ?: default

private fun List<StoredConversation>.toJson(): JsonArray = buildJsonArray {
    forEach { conversation ->
        add(buildJsonObject {
            put("id", conversation.id)
            put("title", conversation.title)
            put("createdAt", conversation.createdAt)
            put("updatedAt", conversation.updatedAt)
            put("isPinned", conversation.isPinned)
            conversation.goal?.let { goal ->
                putJsonObject("goal") {
                    put("goalId", goal.goalId)
                    put("objective", goal.objective)
                    put("status", goal.status.name)
                    goal.tokenBudget?.let { put("tokenBudget", it) }
                    put("tokensUsed", goal.tokensUsed)
                    put("timeUsedSeconds", goal.timeUsedSeconds)
                    put("createdAt", goal.createdAt)
                    put("updatedAt", goal.updatedAt)
                }
            }
            putJsonArray("messages") {
                conversation.messages.forEach { message ->
                    add(buildJsonObject {
                        put("id", message.id)
                        put("conversationId", message.conversationId)
                        put("role", message.role)
                        put("content", message.content)
                        put("isError", message.isError)
                        put("createdAt", message.createdAt)
                    })
                }
            }
        })
    }
}

private fun storedConversation(element: JsonElement): StoredConversation? = runCatching {
    val objectValue = element.jsonObject
    StoredConversation(
        id = objectValue.long("id"),
        title = objectValue.string("title"),
        createdAt = objectValue.long("createdAt"),
        updatedAt = objectValue.long("updatedAt"),
        isPinned = objectValue["isPinned"]?.jsonPrimitive?.booleanOrNull ?: false,
        goal = objectValue["goal"]?.jsonObject?.let(::storedGoal),
        messages = objectValue["messages"]?.jsonArray.orEmpty().mapNotNull(::storedMessage),
    )
}.getOrNull()

private fun storedGoal(objectValue: JsonObject): ThreadGoal? = runCatching {
    ThreadGoal(
        goalId = objectValue.string("goalId"),
        objective = objectValue.string("objective"),
        status = ThreadGoalStatus.valueOf(objectValue.string("status")),
        tokenBudget = objectValue["tokenBudget"]?.jsonPrimitive?.longOrNull,
        tokensUsed = objectValue.long("tokensUsed"),
        timeUsedSeconds = objectValue.long("timeUsedSeconds"),
        createdAt = objectValue.long("createdAt"),
        updatedAt = objectValue.long("updatedAt"),
    )
}.getOrNull()

private fun storedMessage(element: JsonElement): StoredMessage? = runCatching {
    val objectValue = element.jsonObject
    StoredMessage(
        id = objectValue.long("id"),
        conversationId = objectValue.long("conversationId"),
        role = objectValue.string("role"),
        content = objectValue.string("content"),
        isError = objectValue["isError"]?.jsonPrimitive?.booleanOrNull ?: false,
        createdAt = objectValue.long("createdAt"),
    )
}.getOrNull()

private fun JsonObject.long(name: String): Long = get(name)?.jsonPrimitive?.longOrNull ?: 0L

private const val SettingsFile = "settings.json"
private const val HistoryFile = "conversations.json"
