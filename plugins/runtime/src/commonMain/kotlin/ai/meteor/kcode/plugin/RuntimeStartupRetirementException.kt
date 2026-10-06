package ai.meteor.kcode.plugin

import ai.meteor.kcode.AgentRuntimeOwner

/** Startup never published a runtime, but its resource owner has not retired safely. */
internal class RuntimeStartupRetirementException(
    cause: Throwable,
    val retirement: AgentRuntimeOwner,
) : IllegalStateException("Profile startup resources require retirement", cause)
