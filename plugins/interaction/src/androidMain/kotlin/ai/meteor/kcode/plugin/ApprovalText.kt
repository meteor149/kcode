package ai.meteor.kcode.plugin

import java.util.Locale

internal actual fun formatApprovalText(template: String, vararg arguments: String): String =
    String.format(Locale.getDefault(), template, *arguments)
