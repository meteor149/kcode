package ai.meteor.kcode.plugin.executionsettings

import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeShellMode
import ai.meteor.kcode.plugin.api.KcodeShell
import ai.meteor.kcode.plugin.api.KcodeUbuntuShell
import ai.meteor.kcode.plugin.api.ShellBackend
import ai.meteor.kcode.plugin.ui.api.ApplicationSlots
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.SettingsPageRequest
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.LegacySettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.cordis.Context
import org.cordis.plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsShellModeOwnershipTest {
    private suspend fun settle(context: Context) {
        var observed = context.registry.values().flatMap { it.fibers.snapshot() }
        while (true) {
            observed.forEach { it.await() }
            val current = context.registry.values().flatMap { it.fibers.snapshot() }
            if (current.toSet() == observed.toSet()) return
            observed = current
        }
    }

    @Test
    fun headlessPolicyAddsItsSettingsWhenUiAppearsAndWithdrawsOnlyItsOwnItem() = runTest {
        val context = Context()
        KcodeSettings(context, object : AppSettingsStore {
            override val protection = SettingsProtection.Transient
            override suspend fun load() = LegacySettings(shellExecutionMode = "adb")
            override suspend fun save(settings: StoredAppSettings) = Unit
        })
        try {
            val feature = context.plugin(SettingsShellModePlugin, Unit).await()
            val retained = context.require(KcodeShellMode.Key).policy
            val settingsPolicy = checkNotNull(retained.settings)
            val saved = settingsPolicy.update(LegacySettings(shellExecutionMode = "root"), ShellExecutionMode.Adb)
            assertEquals(ShellExecutionMode.Adb, settingsPolicy.resolve(saved))
            assertEquals(ShellExecutionMode.Adb, retained.mode())
            val slots = KcodeUiSlots(context)
            val page = UiRenderer<SettingsPageRequest> { }
            val pageRegistration = slots.register(ApplicationSlots.Settings, page)
            settle(context)
            assertTrue(slots.snapshot().settingsSections.isEmpty())
            val backend = context.plugin(plugin<Unit>(name = "system-shell") { ctx, _ ->
                KcodeShell(ctx, ShellBackend { error("No commands in settings test") })
            }, Unit).await()
            settle(context)
            assertEquals(listOf("shell"), slots.snapshot().settingsSections.map { it.id })
            feature.dispose()
            assertTrue(slots.snapshot().settingsSections.isEmpty())
            assertSame(page, slots.snapshot().settings)
            assertFailsWith<IllegalStateException> { retained.mode() }
            assertNull(retained.settings)
            assertFailsWith<IllegalStateException> { settingsPolicy.resolve(saved) }
            assertFailsWith<IllegalStateException> { settingsPolicy.update(saved, ShellExecutionMode.Root) }
            context.plugin(SettingsShellModePlugin, Unit).await()
            settle(context)
            assertEquals(listOf("shell"), slots.snapshot().settingsSections.map { it.id })
            assertSame(page, slots.snapshot().settings)
            backend.dispose()
            settle(context)
            assertTrue(slots.snapshot().settingsSections.isEmpty())
            pageRegistration.dispose()
        } finally {
            context.fiber.dispose()
        }
    }

    @Test
    fun headlessMutationValidationRejectsInvalidModesAndUnownedWrites() = runTest {
        val context = Context()
        var persisted = StoredAppSettings()
        val settings = KcodeSettings(context, object : AppSettingsStore {
            override val protection = SettingsProtection.Transient
            override suspend fun load() = persisted
            override suspend fun save(settings: StoredAppSettings) { persisted = settings }
        })
        fun proposal(code: String) = StoredAppSettings(namespaces = mapOf(
            "feature.execution-settings" to (Json.parseToJsonElement("""{"mode":"$code","future":null}""") as JsonObject),
        ))
        try {
            var feature = context.plugin(SettingsShellModePlugin, Unit).await()
            assertFailsWith<IllegalArgumentException> { settings.mutationStore.save(proposal("invalid")) }
            assertEquals(StoredAppSettings(), persisted)
            settings.mutationStore.save(proposal("adb"))
            assertEquals(proposal("adb"), persisted)
            feature.dispose()
            assertFailsWith<IllegalArgumentException> { settings.mutationStore.save(proposal("root")) }
            assertEquals(proposal("adb"), persisted)
            feature = context.plugin(SettingsShellModePlugin, Unit).await()
            settings.mutationStore.save(proposal("root"))
            assertEquals(proposal("root"), persisted)
            feature.dispose()
        } finally { context.fiber.dispose() }
    }

    @Test
    fun eitherBackendKeepsOneSectionAndLastWithdrawalRemovesIt() = runTest {
        val context = Context()
        KcodeSettings(context, object : AppSettingsStore {
            override val protection = SettingsProtection.Transient
            override suspend fun load() = StoredAppSettings()
            override suspend fun save(settings: StoredAppSettings) = Unit
        })
        val slots = KcodeUiSlots(context)
        suspend fun settle() {
            settle(context)
        }
        suspend fun system() = context.plugin(plugin<Unit>(name = "system") { ctx, _ ->
            KcodeShell(ctx, ShellBackend { error("Never execute") })
        }, Unit).await()
        suspend fun ubuntu() = context.plugin(plugin<Unit>(name = "ubuntu") { ctx, _ ->
            KcodeUbuntuShell(ctx, ShellBackend { error("Never execute") })
        }, Unit).await()
        try {
            context.plugin(SettingsShellModePlugin, Unit).await()
            var system = system()
            var ubuntu = ubuntu()
            settle()
            assertEquals(listOf("shell"), slots.snapshot().settingsSections.map { it.id })
            system.dispose()
            settle()
            assertEquals(listOf("shell"), slots.snapshot().settingsSections.map { it.id })
            ubuntu.dispose()
            settle()
            assertTrue(slots.snapshot().settingsSections.isEmpty())
            ubuntu = ubuntu()
            settle()
            assertEquals(listOf("shell"), slots.snapshot().settingsSections.map { it.id })
            system = system()
            settle()
            assertEquals(listOf("shell"), slots.snapshot().settingsSections.map { it.id })
            ubuntu.dispose()
            settle()
            assertEquals(listOf("shell"), slots.snapshot().settingsSections.map { it.id })
            system.dispose()
            settle()
            assertTrue(slots.snapshot().settingsSections.isEmpty())
        } finally { context.fiber.dispose() }
    }
}
