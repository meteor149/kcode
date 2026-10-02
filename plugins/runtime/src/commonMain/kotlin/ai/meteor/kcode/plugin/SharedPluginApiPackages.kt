package ai.meteor.kcode.plugin

/** Preserve host ABI identity without exposing the entire implementation namespace. */
val SharedPluginApiPackages: Set<String> = setOf(
    "ai.meteor.kcode.plugin.api",
    "ai.meteor.kcode.model",
    "ai.meteor.kcode.settings",
    "ai.meteor.kcode.tools.permission",
    "ai.meteor.kcode.chat",
    "ai.meteor.kcode.history",
    "ai.meteor.kcode.artifact",
    "ai.meteor.kcode.webcontainer",
    "ai.meteor.kcode.skill",
    "ai.meteor.kcode.export",
    "ai.meteor.kcode.ApplicationHostOptions",
    "ai.meteor.kcode.AgentModelRuntime",
    "ai.meteor.kcode.AgentToolContext",
    "ai.meteor.kcode.AgentContinuationContext",
    "ai.meteor.kcode.SubagentCoordinatorFactory",
    "ai.meteor.kcode.SubagentCoordinatorFactory\$Companion",
    "ai.meteor.kcode.SubagentCoordinator",
    "ai.meteor.kcode.SubAgentLaunch",
    "ai.meteor.kcode.SubAgentSnapshot",
    "ai.meteor.kcode.ToolExecutionRequest",
    "androidx.compose",
    "ai.koog",
)
