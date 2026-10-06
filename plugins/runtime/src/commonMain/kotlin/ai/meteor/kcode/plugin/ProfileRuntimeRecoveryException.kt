package ai.meteor.kcode.plugin

/** Private runtime signal: ordinary commands cannot use an incompletely restored tree. */
internal class ProfileRuntimeRecoveryException(cause: Throwable) :
    IllegalStateException("Profile composition requires recovery", cause)
