@file:OptIn(kotlin.time.ExperimentalTime::class)

package ai.meteor.kcode.history

import androidx.room3.ConstructedBy
import androidx.room3.AutoMigration
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.DeleteColumn
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert
import androidx.room3.migration.AutoMigrationSpec
import kotlin.time.Clock

@Entity(indices = [Index(value = ["updatedAt"])])
internal data class ConversationEntity(
    @androidx.room3.PrimaryKey val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val isPinned: Boolean = false,
    @ColumnInfo(defaultValue = "'Recent'") val presentation: String = ConversationPresentation.Recent.name,
    val standaloneResult: String? = null,
    @ColumnInfo(defaultValue = "0") val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
)

@Entity(
    primaryKeys = ["conversationId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversationId", "createdAt"])],
)
internal data class MessageEntity(
    val conversationId: Long,
    val id: Long,
    val role: String,
    val content: String,
    val isError: Boolean,
    val createdAt: Long,
)

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ThreadGoalEntity(
    @androidx.room3.PrimaryKey val conversationId: Long,
    val goalId: String,
    val objective: String,
    val status: String,
    val tokenBudget: Long?,
    val tokensUsed: Long,
    val timeUsedSeconds: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversationId"]), Index(value = ["status", "nextRunAt"])],
)
internal data class ScheduledTaskEntity(
    @androidx.room3.PrimaryKey val taskId: String,
    val conversationId: Long,
    val name: String,
    val prompt: String,
    val status: String,
    val nextRunAt: Long,
    val repeatIntervalMillis: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long?,
)

internal data class HistoryRows(
    val conversations: List<ConversationEntity>,
    val messages: List<MessageEntity>,
    val goals: List<ThreadGoalEntity>,
)

@Dao
internal interface ConversationHistoryDao {
    @Query("SELECT * FROM ConversationEntity WHERE isDeleted = 0 ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun loadConversations(): List<ConversationEntity>

    @Query("SELECT * FROM MessageEntity ORDER BY conversationId, createdAt, id")
    suspend fun loadMessages(): List<MessageEntity>

    @Query("SELECT * FROM ThreadGoalEntity")
    suspend fun loadGoals(): List<ThreadGoalEntity>

    @Query("SELECT * FROM ScheduledTaskEntity ORDER BY nextRunAt, createdAt")
    suspend fun loadScheduledTasks(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM ConversationEntity WHERE id = :id LIMIT 1")
    suspend fun loadConversation(id: Long): ConversationEntity?

    @Query("SELECT COALESCE(MAX(id), 0) + 1 FROM ConversationEntity")
    suspend fun nextConversationId(): Long

    @Upsert
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Upsert
    suspend fun upsertMessage(message: MessageEntity)

    @Upsert
    suspend fun upsertGoal(goal: ThreadGoalEntity)

    @Upsert
    suspend fun upsertScheduledTask(task: ScheduledTaskEntity)

    @Update
    suspend fun updateScheduledTask(task: ScheduledTaskEntity): Int

    @Query("DELETE FROM MessageEntity WHERE conversationId = :conversationId AND id >= :messageIdInclusive")
    suspend fun deleteMessagesFrom(conversationId: Long, messageIdInclusive: Long)

    @Query("UPDATE ConversationEntity SET isPinned = :pinned, updatedAt = :timestamp WHERE id = :conversationId")
    suspend fun setPinned(conversationId: Long, pinned: Boolean, timestamp: Long)

    @Query("UPDATE ConversationEntity SET presentation = :presentation, updatedAt = :timestamp WHERE id = :conversationId AND isDeleted = 0")
    suspend fun setPresentation(conversationId: Long, presentation: String, timestamp: Long)

    @Query("UPDATE ConversationEntity SET standaloneResult = :result, updatedAt = :timestamp WHERE id = :conversationId AND isDeleted = 0")
    suspend fun setStandaloneResult(conversationId: Long, result: String, timestamp: Long)

    @Query("DELETE FROM ThreadGoalEntity WHERE conversationId = :conversationId")
    suspend fun clearGoal(conversationId: Long)

    @Query("DELETE FROM ScheduledTaskEntity WHERE conversationId = :conversationId AND taskId = :taskId")
    suspend fun deleteScheduledTask(conversationId: Long, taskId: String)

    @Query("UPDATE ConversationEntity SET isDeleted = 1, deletedAt = :timestamp, updatedAt = :timestamp, isPinned = 0 WHERE id = :conversationId")
    suspend fun markConversationDeleted(conversationId: Long, timestamp: Long)

    @Query("DELETE FROM ScheduledTaskEntity WHERE conversationId = :conversationId")
    suspend fun deleteScheduledTasksForConversation(conversationId: Long)

    @Query("SELECT COUNT(*) FROM ConversationEntity WHERE isDeleted = 1")
    suspend fun countDeletedConversations(): Int

    @Query("DELETE FROM ConversationEntity WHERE id IN (SELECT id FROM ConversationEntity WHERE isDeleted = 1 ORDER BY deletedAt ASC, id ASC LIMIT :count)")
    suspend fun deleteOldestDeletedConversations(count: Int)

    @Transaction
    suspend fun loadSnapshot(): HistoryRows = HistoryRows(loadConversations(), loadMessages(), loadGoals())

    @Transaction
    suspend fun createConversation(
        conversationId: Long,
        title: String,
        presentation: String,
        timestamp: Long,
    ) {
        if (loadConversation(conversationId) == null) {
            upsertConversation(
                ConversationEntity(
                    id = conversationId,
                    title = title,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    presentation = presentation,
                ),
            )
        }
    }

    @Transaction
    suspend fun setGoal(
        conversationId: Long,
        title: String,
        goal: ThreadGoalEntity,
        timestamp: Long,
    ) {
        val existing = loadConversation(conversationId)
        upsertConversation(
            ConversationEntity(
                id = conversationId,
                title = title,
                createdAt = existing?.createdAt ?: timestamp,
                updatedAt = timestamp,
                isPinned = existing?.isPinned ?: false,
                presentation = existing?.presentation ?: ConversationPresentation.Recent.name,
                standaloneResult = existing?.standaloneResult,
                isDeleted = existing?.isDeleted ?: false,
                deletedAt = existing?.deletedAt,
            ),
        )
        upsertGoal(goal)
    }

    @Transaction
    suspend fun append(
        conversationId: Long,
        title: String,
        messageId: Long,
        role: String,
        content: String,
        isError: Boolean,
        timestamp: Long,
    ) {
        val existing = loadConversation(conversationId)
        upsertConversation(
            ConversationEntity(
                id = conversationId,
                title = title,
                createdAt = existing?.createdAt ?: timestamp,
                updatedAt = timestamp,
                isPinned = existing?.isPinned ?: false,
                presentation = existing?.presentation ?: ConversationPresentation.Recent.name,
                standaloneResult = existing?.standaloneResult,
                isDeleted = existing?.isDeleted ?: false,
                deletedAt = existing?.deletedAt,
            ),
        )
        upsertMessage(
            MessageEntity(
                conversationId = conversationId,
                id = messageId,
                role = role,
                content = content,
                isError = isError,
                createdAt = timestamp,
            ),
        )
    }

    @Transaction
    suspend fun upsertScheduledTask(
        title: String,
        task: ScheduledTaskEntity,
        timestamp: Long,
    ) {
        val existing = loadConversation(task.conversationId)
        if (existing == null) {
            upsertConversation(
                ConversationEntity(
                    id = task.conversationId,
                    title = title,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                ),
            )
        }
        upsertScheduledTask(task)
    }

    @Transaction
    suspend fun softDeleteConversation(conversationId: Long, timestamp: Long) {
        markConversationDeleted(conversationId, timestamp)
        deleteScheduledTasksForConversation(conversationId)
        val overflow = countDeletedConversations() - MaximumDeletedConversations
        if (overflow > 0) deleteOldestDeletedConversations(overflow)
    }

    companion object {
        const val MaximumDeletedConversations = 100
    }
}

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, ThreadGoalEntity::class, ScheduledTaskEntity::class],
    version = 6,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6, spec = HistoryDatabase.Migration5To6::class),
    ],
    exportSchema = true,
)
@ConstructedBy(HistoryDatabaseConstructor::class)
internal abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): ConversationHistoryDao

    @DeleteColumn(tableName = "ScheduledTaskEntity", columnName = "destination")
    class Migration5To6 : AutoMigrationSpec
}

@Suppress("KotlinNoActualForExpect")
internal expect object HistoryDatabaseConstructor : RoomDatabaseConstructor<HistoryDatabase> {
    override fun initialize(): HistoryDatabase
}

internal class RoomConversationHistoryRepository(
    private val database: HistoryDatabase,
) : ConversationHistoryRepository {
    private val dao = database.historyDao()
    private var lastTimestamp = 0L

    override suspend fun loadAll(): List<StoredConversation> {
        val rows = dao.loadSnapshot()
        val conversations = rows.conversations
        val messages = rows.messages.groupBy(MessageEntity::conversationId)
        val goals = rows.goals.associateBy(ThreadGoalEntity::conversationId)
        lastTimestamp = maxOf(
            conversations.maxOfOrNull(ConversationEntity::updatedAt) ?: 0L,
            messages.values.flatten().maxOfOrNull(MessageEntity::createdAt) ?: 0L,
        )
        return conversations.map { conversation ->
            StoredConversation(
                id = conversation.id,
                title = conversation.title,
                createdAt = conversation.createdAt,
                updatedAt = conversation.updatedAt,
                isPinned = conversation.isPinned,
                presentation = runCatching {
                    ConversationPresentation.valueOf(conversation.presentation)
                }.getOrDefault(ConversationPresentation.Recent),
                standaloneResult = conversation.standaloneResult,
                goal = goals[conversation.id]?.toThreadGoal(),
                messages = messages[conversation.id].orEmpty().map { message ->
                    StoredMessage(
                        id = message.id,
                        conversationId = message.conversationId,
                        role = message.role,
                        content = message.content,
                        isError = message.isError,
                        createdAt = message.createdAt,
                    )
                },
            )
        }
    }

    override suspend fun nextConversationId(): Long = dao.nextConversationId()

    override suspend fun appendMessage(
        conversationId: Long,
        title: String,
        messageId: Long,
        role: String,
        content: String,
        isError: Boolean,
    ) {
        val timestamp = nextTimestamp()
        dao.append(
            conversationId = conversationId,
            title = title,
            messageId = messageId,
            role = role,
            content = content,
            isError = isError,
            timestamp = timestamp,
        )
    }

    override suspend fun deleteMessagesFrom(conversationId: Long, messageIdInclusive: Long) {
        dao.deleteMessagesFrom(conversationId, messageIdInclusive)
    }

    override suspend fun setPinned(conversationId: Long, pinned: Boolean) {
        dao.setPinned(conversationId, pinned, nextTimestamp())
    }

    override suspend fun setGoal(conversationId: Long, title: String, goal: ThreadGoal) {
        val timestamp = nextTimestamp()
        dao.setGoal(
            conversationId = conversationId,
            title = title,
            goal = ThreadGoalEntity(
                conversationId = conversationId,
                goalId = goal.goalId,
                objective = goal.objective,
                status = goal.status.name,
                tokenBudget = goal.tokenBudget,
                tokensUsed = goal.tokensUsed,
                timeUsedSeconds = goal.timeUsedSeconds,
                createdAt = goal.createdAt,
                updatedAt = goal.updatedAt,
            ),
            timestamp = timestamp,
        )
    }

    override suspend fun clearGoal(conversationId: Long) {
        dao.clearGoal(conversationId)
    }

    override suspend fun deleteConversation(conversationId: Long) {
        dao.softDeleteConversation(conversationId, nextTimestamp())
    }

    override suspend fun createConversation(
        conversationId: Long,
        title: String,
        presentation: ConversationPresentation,
    ) {
        dao.createConversation(conversationId, title, presentation.name, nextTimestamp())
    }

    override suspend fun setConversationPresentation(
        conversationId: Long,
        presentation: ConversationPresentation,
    ) {
        dao.setPresentation(conversationId, presentation.name, nextTimestamp())
    }

    override suspend fun setStandaloneResult(conversationId: Long, result: String) {
        dao.setStandaloneResult(conversationId, result, nextTimestamp())
    }

    override suspend fun loadScheduledTasks(conversationId: Long?): List<ScheduledTask> =
        dao.loadScheduledTasks()
            .asSequence()
            .filter { conversationId == null || it.conversationId == conversationId }
            .map(ScheduledTaskEntity::toScheduledTask)
            .toList()

    override suspend fun upsertScheduledTask(title: String, task: ScheduledTask) {
        dao.upsertScheduledTask(
            title = title,
            task = task.toEntity(),
            timestamp = nextTimestamp(),
        )
    }

    override suspend fun updateScheduledTask(task: ScheduledTask) {
        dao.updateScheduledTask(task.toEntity())
    }

    override suspend fun deleteScheduledTask(conversationId: Long, taskId: String) {
        dao.deleteScheduledTask(conversationId, taskId)
    }

    private fun nextTimestamp(): Long {
        val wallClock = Clock.System.now().toEpochMilliseconds()
        return maxOf(wallClock, lastTimestamp + 1).also { lastTimestamp = it }
    }
}

private fun ThreadGoalEntity.toThreadGoal(): ThreadGoal = ThreadGoal(
    goalId = goalId,
    objective = objective,
    status = runCatching { ThreadGoalStatus.valueOf(status) }.getOrDefault(ThreadGoalStatus.Paused),
    tokenBudget = tokenBudget,
    tokensUsed = tokensUsed,
    timeUsedSeconds = timeUsedSeconds,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun ScheduledTaskEntity.toScheduledTask(): ScheduledTask = ScheduledTask(
    taskId = taskId,
    conversationId = conversationId,
    name = name,
    prompt = prompt,
    status = runCatching { ScheduledTaskStatus.valueOf(status) }.getOrDefault(ScheduledTaskStatus.Paused),
    nextRunAt = nextRunAt,
    repeatIntervalMillis = repeatIntervalMillis,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastRunAt = lastRunAt,
)

private fun ScheduledTask.toEntity(): ScheduledTaskEntity = ScheduledTaskEntity(
    taskId = taskId,
    conversationId = conversationId,
    name = name,
    prompt = prompt,
    status = status.name,
    nextRunAt = nextRunAt,
    repeatIntervalMillis = repeatIntervalMillis,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastRunAt = lastRunAt,
)
