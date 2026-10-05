package ai.meteor.kcode.plugin

import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun ClassLoader.bundledArchive(id: String): InputStream {
    val records = checkNotNull(getResourceAsStream("kcode/plugins/index.json")).use { input ->
        Json.parseToJsonElement(input.bufferedReader().readText()).jsonArray
    }
    val filename = records.single { it.jsonObject.getValue("id").jsonPrimitive.content == id }
        .jsonObject.getValue("file").jsonPrimitive.content
    return checkNotNull(getResourceAsStream("kcode/plugins/$filename"))
}
