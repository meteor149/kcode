package ai.meteor.kcode.plugin.overlay

import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.plugin.ui.api.ApplicationUiSlots
import ai.meteor.kcode.plugin.ui.api.LocalApplicationUiSlots
import ai.meteor.kcode.plugin.ui.api.MessageContentRequest
import ai.meteor.kcode.plugin.ui.api.MessagePresentation
import ai.meteor.kcode.plugin.ui.api.ThemeRenderer
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

internal class ConversationOverlayPresentationScenario {
    fun verifyCurrentContributions() = runTest {
        val localization = object : ai.meteor.kcode.localization.TranslationCatalog {
            override val available = MutableStateFlow(true)
            override fun snapshot() = ai.meteor.kcode.localization.LocalizationSnapshot(
                listOf(ai.meteor.kcode.localization.LanguageOption(ai.meteor.kcode.localization.AppLanguage.English, emptyMap())),
                ai.meteor.kcode.localization.AppLanguage.English,
            )
            override fun translate(language: ai.meteor.kcode.localization.AppLanguage, value: ai.meteor.kcode.localization.LocalizedText, vararg arguments: Any) = value.key
            override fun displayText(language: ai.meteor.kcode.localization.AppLanguage, value: ai.meteor.kcode.localization.LocalizedText, arguments: List<Any>) = value.key
        }
        val slots = MutableStateFlow(ApplicationUiSlots(localization = localization))
        var themeStarted = 0
        var themeStopped = 0
        var messageEntered = 0
        var messageStopped = 0
        var rendered = ""
        val theme = ThemeRenderer { content ->
            DisposableEffect(Unit) { themeStarted++; onDispose { themeStopped++ } }
            content()
        }
        fun message(id: String) = MessagePresentation(id, 0, { true }, UiRenderer {
            DisposableEffect(id) { messageEntered++; onDispose { messageStopped++ } }
            SideEffect { rendered = id }
        })
        val clock = object : MonotonicFrameClock {
            var nanos = 0L
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                yield()
                nanos += 16_000_000L
                return onFrame(nanos)
            }
        }
        val recomposer = Recomposer(backgroundScope.coroutineContext + clock)
        val composition = Composition(UnitApplier(), recomposer)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        suspend fun renderChanges() {
            testScheduler.runCurrent()
            Snapshot.sendApplyNotifications()
            testScheduler.runCurrent()
            recomposer.awaitIdle()
        }
        try {
            composition.setContent {
                ConversationOverlayPresentation(slots) {
                    LocalApplicationUiSlots.current.messagePresentations.firstOrNull()?.renderer?.Render(
                        MessageContentRequest(ChatMessage(1L, MessageRole.Assistant, "fixture"), true) { _, _ -> },
                    )
                }
            }
            renderChanges()
            assertEquals(0, themeStarted)
            assertEquals(0, messageEntered)
            slots.value = ApplicationUiSlots(localization = localization, theme = theme, messagePresentations = listOf(message("first")))
            renderChanges()
            assertEquals("first", rendered)
            assertEquals(1, themeStarted)
            slots.value = slots.value.copy(messagePresentations = listOf(message("replacement")))
            renderChanges()
            assertEquals("replacement", rendered)
            assertEquals(1, themeStarted)
            assertEquals(1, messageStopped)
            slots.value = slots.value.copy(messagePresentations = emptyList())
            renderChanges()
            assertEquals(2, messageStopped)
            slots.value = slots.value.copy(theme = null)
            renderChanges()
            assertEquals(1, themeStopped)
            slots.value = ApplicationUiSlots(localization = localization, theme = theme, messagePresentations = listOf(message("restored")))
            renderChanges()
            assertEquals("restored", rendered)
            assertEquals(2, themeStarted)
        } finally {
            composition.dispose()
            recomposer.close()
            runner.cancel()
        }
        assertEquals(2, themeStopped)
        assertEquals(3, messageStopped)
    }

    private class UnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
