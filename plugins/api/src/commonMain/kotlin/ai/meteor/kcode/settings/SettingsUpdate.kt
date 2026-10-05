package ai.meteor.kcode.settings

/** Opaque feature-owned field identities; values are validated by their registered feature. */
class SettingsUpdate(values: Map<String, String> = emptyMap()) {
    private val fields = values.toMap()
    val values: Map<String, String> get() = fields.toMap()
    val suppliedFields: List<String> get() = fields.keys.toList()
    val isEmpty: Boolean get() = fields.isEmpty()

    init {
        require(fields.keys.all { it.isNotBlank() && it == it.trim() && it.none(Char::isISOControl) }) {
            "Settings field identities must be nonempty and contain no surrounding whitespace or controls"
        }
    }

    override fun equals(other: Any?): Boolean = other is SettingsUpdate && fields == other.fields
    override fun hashCode(): Int = fields.hashCode()
    override fun toString(): String = "SettingsUpdate(fields=$suppliedFields)"
}

data class AppliedSettingsUpdate(
    val settings: StoredAppSettings,
    val changedFields: List<String>,
)
