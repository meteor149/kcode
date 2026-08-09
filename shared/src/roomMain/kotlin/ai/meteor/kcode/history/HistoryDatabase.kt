@file:OptIn(kotlin.time.ExperimentalTime::class)

package ai.meteor.kcode.history

import androidx.room3.ConstructedBy
import androidx.room3.AutoMigration
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlin.time.Clock

@Entity(indices = [Index(value = ["updatedAt"])])
internal data class ConversationEntity(
    @androidx.room3.PrimaryKey val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val isPinned: Boolean = false,
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

internal data class HistoryRows(
    val conversations: List<ConversationEntity>,
    val messages: List<MessageEntity>,
)

@Dao
internal interface ConversationHistoryDao {
    @Query("SELECT * FROM ConversationEntity ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun loadConversations(): List<ConversationEntity>

    @Query("SELECT * FROM MessageEntity ORDER BY conversationId, createdAt, id")
    suspend fun loadMessages(): List<MessageEntity>

    @Query("SELECT * FROM ConversationEntity WHERE id = :id LIMIT 1")
    suspend fun loadConversation(id: Long): ConversationEntity?

    @Upsert
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Upsert
    suspend fun upsertMessage(message: MessageEntity)

    @Query("DELETE FROM MessageEntity WHERE conversationId = :conversationId AND id >= :messageIdInclusive")
    suspend fun deleteMessagesFrom(conversationId: Long, messageIdInclusive: Long)

    @Query("UPDATE ConversationEntity SET isPinned = :pinned, updatedAt = :timestamp WHERE id = :conversationId")
    suspend fun setPinned(conversationId: Long, pinned: Boolean, timestamp: Long)

    @Query("DELETE FROM ConversationEntity WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: Long)

    @Transaction
    suspend fun loadSnapshot(): HistoryRows = HistoryRows(loadConversations(), loadMessages())

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
}

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
    exportSchema = true,
)
@ConstructedBy(HistoryDatabaseConstructor::class)
internal abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): ConversationHistoryDao
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

    override suspend fun deleteConversation(conversationId: Long) {
        dao.deleteConversation(conversationId)
    }

    private fun nextTimestamp(): Long {
        val wallClock = Clock.System.now().toEpochMilliseconds()
        return maxOf(wallClock, lastTimestamp + 1).also { lastTimestamp = it }
    }
}
