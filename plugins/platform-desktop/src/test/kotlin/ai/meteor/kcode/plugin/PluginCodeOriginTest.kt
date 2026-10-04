package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.PluginCodeArtifact
import ai.meteor.kcode.plugin.api.PluginCodeOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.cordis.Context

class PluginCodeOriginTest {
    @Test
    fun dependencySnapshotPreservesSearchOrderAndDeduplicatesDiamond() {
        fun node(id: String, vararg dependencies: String) = PluginCodeNode(
            PluginCodeArtifact(id, "1", "/$id.jar", "a".repeat(64), "Plugin"), dependencies.toList(),
        )
        val nodes = mutableListOf(node("root", "a", "b"), node("a", "c"), node("b", "c"), node("c"))
        val origin = pluginCodeOrigin("root", nodes)
        assertEquals(listOf("a", "c", "b"), origin.dependencies.map { it.id })
        nodes.clear()
        assertEquals(3, origin.dependencies.size)
        assertFailsWith<IllegalArgumentException> { pluginCodeOrigin("root", listOf(node("root", "missing"))) }
        assertFailsWith<IllegalArgumentException> { pluginCodeOrigin("root", listOf(node("root", "a"), node("a", "root"))) }
        assertFailsWith<IllegalArgumentException> { pluginCodeOrigin("root", listOf(node("root"), node("root"))) }
    }

    @Test
    fun codeOriginsAreScopedAndNestedBindingsDoNotOverwriteParent() {
        fun origin(id: String) = PluginCodeOrigin(PluginCodeArtifact(id, "1", "/$id.jar", "a".repeat(64), "Plugin"))
        val root = Context()
        val first = origin("first")
        val second = origin("second")
        val parent = first.bind(root)
        val child = second.bind(parent)
        assertEquals(first, PluginCodeOrigin.current(parent))
        assertEquals(second, PluginCodeOrigin.current(child))
        assertEquals(null, PluginCodeOrigin.current(root))
    }
}
