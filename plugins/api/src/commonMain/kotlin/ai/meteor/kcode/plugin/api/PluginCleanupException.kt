package ai.meteor.kcode.plugin.api

/** Stable teardown failure aggregate; coroutine stack recovery must preserve every failure. */
class PluginCleanupException(
    val owner: String,
    failures: List<Throwable>,
) : IllegalStateException("$owner failed during withdrawal cleanup", failures.firstOrNull()) {
    val failures: List<Throwable> = failures.toList()

    init {
        require(this.failures.isNotEmpty())
        this.failures.drop(1).forEach(::addSuppressed)
    }
}
