package ai.meteor.kcode.plugin.history

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HistoryMigrationTest {
    @Test
    fun everyExportedLegacySchemaMigratesAndRetainsConversationMessages(): Unit = runBlocking {
        val directory = Files.createTempDirectory("kcode-history-migration-")
        try {
            for (version in 1..6) {
                val path = directory.resolve("version-$version.db")
                val schema = Json.parseToJsonElement(Files.readString(Path.of(
                    "schemas", "ai.meteor.kcode.plugin.history.HistoryDatabase", "$version.json",
                ))).jsonObject.getValue("database").jsonObject
                val connection = BundledSQLiteDriver().open(path.toString())
                fun execute(sql: String) { connection.prepare(sql).use { it.step() } }
                try {
                    schema.getValue("entities").jsonArray.forEach { entity ->
                        val definition = entity.jsonObject
                        val table = definition.getValue("tableName").jsonPrimitive.content
                        execute(definition.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                        definition["indices"]?.jsonArray.orEmpty().forEach { index ->
                            execute(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                        }
                    }
                    schema.getValue("setupQueries").jsonArray.forEach { execute(it.jsonPrimitive.content) }
                    execute("INSERT INTO ConversationEntity (id,title,createdAt,updatedAt) VALUES (1,'legacy',1,2)")
                    execute("INSERT INTO MessageEntity (id,conversationId,role,content,isError,createdAt) VALUES (1,1,'User','retained',0,2)")
                    execute("PRAGMA user_version = $version")
                } finally { connection.close() }
                val resource = desktopHistoryRepositoryFactory(path).create()
                try {
                    val conversation = resource.repository.loadAll().single()
                    assertEquals("legacy", conversation.title, "version $version")
                    assertEquals("retained", conversation.messages.single().content, "version $version")
                } finally { resource.close() }
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
