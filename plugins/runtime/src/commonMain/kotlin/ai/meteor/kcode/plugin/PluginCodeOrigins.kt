package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.PluginCodeArtifact
import ai.meteor.kcode.plugin.api.PluginCodeOrigin

/** A snapshot of the descriptor graph for one imported code generation. */
data class PluginCodeNode(
    val artifact: PluginCodeArtifact,
    val dependencies: List<String>,
)

fun pluginCodeOrigin(rootId: String, nodes: List<PluginCodeNode>): PluginCodeOrigin {
    val graph = nodes.associateBy { it.artifact.id }
    require(graph.size == nodes.size) { "duplicate plugin code descriptor" }
    val root = requireNotNull(graph[rootId]) { "missing plugin code descriptor: $rootId" }
    val dependencies = mutableListOf<PluginCodeArtifact>()
    val visited = mutableSetOf(rootId)
    val visiting = mutableSetOf(rootId)
    fun visit(id: String) {
        require(id !in visiting) { "cyclic plugin code dependency: $id" }
        if (!visited.add(id)) return
        val node = requireNotNull(graph[id]) { "missing plugin code dependency: $id" }
        dependencies += node.artifact
        visiting += id
        node.dependencies.forEach(::visit)
        visiting -= id
    }
    root.dependencies.forEach(::visit)
    return PluginCodeOrigin(root.artifact, dependencies)
}
