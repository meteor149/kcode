package ai.meteor.kcode.plugin.defaultui

import ai.meteor.kcode.plugin.api.ApplicationServices
import ai.meteor.kcode.plugin.api.UiSlotKey
import ai.meteor.kcode.plugin.ui.api.NavigationDestination
import ai.meteor.kcode.plugin.ui.api.NavigationPagePresenter
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.plugin.ui.api.UiTextDictionary
import ai.meteor.kcode.ui.component.KcodeIconAsset
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.ServiceKey

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UiRegistryLifecycleTest {
    private val services = object : ApplicationServices {
        override fun <T> get(key: ServiceKey<T>): T? = null
    }

    @Test
    fun closingRevokesHeldContributionsAndRejectsRetainedRegistryCalls() = runTest {
        val context = Context()
        val registry = OwnedUiSlots(context)
        try {
            val key = UiSlotKey<String>("feature.value")
            val value = registry.register(key, "value")
            val texts = registry.registerTexts(UiTextDictionary("feature", mapOf("label" to "Value")))
            val destination = registry.registerNavigation(NavigationDestination(
                "feature", 0, KcodeIconAsset.Chat, { "Feature" }, UiRenderer { },
                presenter = NavigationPagePresenter { UiRenderer { } },
            ))
            val held = registry.snapshot()
            registry.close()
            assertFalse(held.textDictionaries.single().available.value)
            assertFalse(held.navigation.single().isAvailable(held))
            assertFailsWith<IllegalStateException> { held.navigation.single().presenter!!.prepare(services) }
            assertFailsWith<IllegalStateException> { registry.snapshot() }
            assertFailsWith<IllegalStateException> { registry.resolve(key) }
            assertFailsWith<IllegalStateException> { registry.register(key, "late") }
            value.dispose()
            texts.dispose()
            destination.dispose()
            registry.close()
        } finally { context.fiber.dispose() }
    }

    @Test
    fun withdrawalCancelsAndJoinsPreparationBeforeReplacement() = runTest {
        val context = Context()
        val registry = OwnedUiSlots(context)
        val entered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        try {
            val registration = registry.registerNavigation(NavigationDestination(
                "feature", 0, KcodeIconAsset.Chat, { "Feature" }, UiRenderer { },
                presenter = NavigationPagePresenter {
                    try { entered.complete(Unit); awaitCancellation() }
                    finally { withContext(NonCancellable) { cleanupEntered.complete(Unit); releaseCleanup.await() } }
                },
            ))
            val held = registry.snapshot().navigation.single()
            val preparing = async { held.presenter!!.prepare(services) }
            entered.await()
            val disposal = async { registration.dispose() }
            cleanupEntered.await()
            assertFalse(disposal.isCompleted)
            assertFalse(held.isAvailable(registry.snapshot()))
            val replacement = registry.registerNavigation(NavigationDestination(
                "feature", 0, KcodeIconAsset.Chat, { "Replacement" }, UiRenderer { },
                presenter = NavigationPagePresenter { UiRenderer { } },
            ))
            releaseCleanup.complete(Unit)
            disposal.await()
            preparing.join()
            registration.dispose()
            assertTrue(registry.snapshot().navigation.single().isAvailable(registry.snapshot()))
            assertFailsWith<IllegalStateException> { held.presenter!!.prepare(services) }
            replacement.dispose()
            registry.close()
        } finally { releaseCleanup.complete(Unit); context.fiber.dispose() }
    }

    @Test
    fun closeAlsoJoinsAPreparationWhoseRegistrationIsAlreadyWithdrawing() = runTest {
        val context = Context()
        val registry = OwnedUiSlots(context)
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val registration = registry.registerNavigation(NavigationDestination(
                "feature", 0, KcodeIconAsset.Chat, { "Feature" }, UiRenderer { },
                presenter = NavigationPagePresenter {
                    try { entered.complete(Unit); awaitCancellation() }
                    finally { withContext(NonCancellable) { cleanup.complete(Unit); release.await() } }
                },
            ))
            val presenter = registry.snapshot().navigation.single().presenter!!
            val preparing = async { presenter.prepare(services) }
            entered.await()
            val disposal = async { registration.dispose() }
            cleanup.await()
            val closing = async { registry.close() }
            runCurrent()
            assertFalse(disposal.isCompleted)
            assertFalse(closing.isCompleted)
            release.complete(Unit)
            disposal.await()
            closing.await()
            preparing.join()
        } finally { release.complete(Unit); context.fiber.dispose() }
    }

    @Test
    fun concurrentCloseWaitsForTheSamePreparationCleanup() = runTest {
        val context = Context()
        val registry = OwnedUiSlots(context)
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            registry.registerNavigation(NavigationDestination(
                "feature", 0, KcodeIconAsset.Chat, { "Feature" }, UiRenderer { },
                presenter = NavigationPagePresenter {
                    try { entered.complete(Unit); awaitCancellation() }
                    finally { withContext(NonCancellable) { cleanup.complete(Unit); release.await() } }
                },
            ))
            val presenter = registry.snapshot().navigation.single().presenter!!
            val preparing = async { presenter.prepare(services) }
            entered.await()
            val first = async { registry.close() }
            cleanup.await()
            val second = async { registry.close() }
            runCurrent()
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            release.complete(Unit)
            first.await()
            second.await()
            preparing.join()
            assertFailsWith<IllegalStateException> { registry.snapshot() }
        } finally { release.complete(Unit); context.fiber.dispose() }
    }

}
