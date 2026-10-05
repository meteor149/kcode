package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.ui.api.ConversationDecoration
import ai.meteor.kcode.plugin.ui.api.ConversationDecorationPresenter
import ai.meteor.kcode.plugin.ui.api.ThemeRenderer
import ai.meteor.kcode.plugin.ui.api.UiRenderer

/** Inspect the private renderer behind its provider-owned withdrawal guard. */
internal fun uiImplementation(value: Any): Any {
    var current = value
    while (current.javaClass.name.startsWith("ai.meteor.kcode.plugin.defaultui.OwnedUiSlots")) {
        val wrapper = current
        val delegate = wrapper.javaClass.declaredFields.mapNotNull { field ->
            field.isAccessible = true
            field.get(wrapper)
        }.firstNotNullOfOrNull { field ->
            when (field) {
                is UiRenderer<*> -> field
                is ThemeRenderer -> field
                is ConversationDecorationPresenter -> field
                is ConversationDecoration -> field.presenter
                else -> null
            }
        } ?: return current
        current = delegate
    }
    return current
}
