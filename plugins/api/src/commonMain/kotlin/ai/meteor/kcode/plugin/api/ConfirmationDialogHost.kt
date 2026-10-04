package ai.meteor.kcode.plugin.api

/** Generic OS confirmation content; no tool or permission policy belongs to the host. */
data class ConfirmationDialogRequest(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val cancelLabel: String,
)

fun interface ConfirmationDialogHost {
    suspend fun confirm(request: ConfirmationDialogRequest): Boolean
}
