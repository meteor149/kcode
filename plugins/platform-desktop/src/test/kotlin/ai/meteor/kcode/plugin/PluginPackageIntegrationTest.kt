package ai.meteor.kcode.plugin

import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeAgents
import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import ai.meteor.kcode.plugin.api.PluginCompositionStore
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.StoredPluginConfiguration
import ai.meteor.kcode.plugin.packages.NativePluginPackageResolver
import ai.meteor.kcode.plugin.packages.NativePluginPackagesPlugin
import ai.meteor.kcode.plugin.packages.desktopPackageHost
import ai.meteor.kcode.plugin.packages.kcodeConfigurationExtension
import ai.meteor.kcode.plugin.packages.kcodeVariantExtension
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.packages.PackageDependency
import org.cordis.packages.PackageFile
import org.cordis.packages.PackageTarget
import org.cordis.packages.PackageRuntime
import org.cordis.packages.PackageVariant
import org.cordis.packages.PluginPackageArchive
import org.cordis.packages.PluginPackageManifest
import org.cordis.packages.packageFileSha256

class PluginPackageIntegrationTest {
    private val abi = "a".repeat(64)

    @Test
    fun externalDependencyKeepsTheEntireFormerGoalFeatureFunctional(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-retained-goal-feature").toFile()
        val store = FilePluginCompositionStore(root)
        val aliases = NativeBuiltinAliases.filterValues { it == setOf("feature.goal") }
        val entries = mapOf(
            "consumer.commands.goal" to GoalCommandConsumerPlugin::class.java,
            "consumer.tools.goal" to GoalToolConsumerPlugin::class.java,
            "provider.continuation.goal" to GoalContinuationPlugin::class.java,
            "provider.goal-sessions.history" to GoalSessionProviderPlugin::class.java,
            "provider.ui.chat.goal" to ai.meteor.kcode.plugin.goalui.GoalDecorationPlugin::class.java,
            "consumer.goals.chat-restoration" to ai.meteor.kcode.plugin.goalui.GoalRestorationEffectPlugin::class.java,
        )
        val extra = nativePluginBundle(NativePluginServices(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            skillRuntime = null, conversationOverlayFactory = ai.meteor.kcode.plugin.api.ConversationOverlayFactory { null },
            settingsStore = null, historyRepository = null,
            packagedProviderIds = setOf("feature.goal"),
        )).filterNot { it.descriptor.id == "provider.plugin-installations.platform" }
        lateinit var goals: ai.meteor.kcode.chat.GoalSessionFactory
        val capture = kcodePlugin(PluginDescriptor("test.retained-goal", "test", "test", emptySet()),
            org.cordis.plugin<Unit>(name = "capture-retained-goal", inject = org.cordis.dependencies(ai.meteor.kcode.plugin.api.KcodeGoals.Key)) { ctx, _ ->
                goals = ctx.require(ai.meteor.kcode.plugin.api.KcodeGoals.Key).sessions
            }, Unit)
        try {
            val previous = entries.map { (id, entry) -> BundledPluginPackage(id,
                pack(root, id, "1.0.0", entry = entry.name, implementation = File(entry.protectionDomain.codeSource.location.toURI()))) }
            val consumer = pack(root, "example.consumer", "1.0.0", entry = PackageFixtureDependency::class.java.name,
                dependencies = listOf(PackageDependency("consumer.tools.goal", "1.0.0")))
            val first = runtime(root, store, extra = extra + capture, bundled = previous)
            try { first.pluginManager.importPackages(listOf(consumer)) } finally { first.close() }
            val aggregate = BundledPluginPackage("feature.goal", pack(root, "feature.goal", "1.0.0",
                entry = GoalFeaturePlugin::class.java.name,
                implementation = File(GoalFeaturePlugin::class.java.protectionDomain.codeSource.location.toURI())))
            val restored = runtime(root, store, extra = extra + capture, bundled = listOf(aggregate), aliases = aliases)
            try {
                assertEquals(entries.keys + setOf("example.consumer", "feature.goal"), restored.pluginManager.installed().map { it.id }.toSet())
                assertFalse(restored.pluginManager.installed().single { it.id == "feature.goal" }.enabled)
                assertTrue("core/goal" in restored.diagnostics().toolContributions)
                val target = ai.meteor.kcode.session.HistoryConversationState(42, "Retained Goal")
                val session = requireNotNull(goals.create(target))
                session.setGoalFromUser("Keep the legacy feature functional")
                assertEquals("Keep the legacy feature functional", session.getGoal()?.objective)
                restored.pluginManager.uninstall("example.consumer")
            } finally { restored.close() }
            val cleaned = runtime(root, store, extra = extra + capture, bundled = listOf(aggregate), aliases = aliases)
            try {
                assertEquals(listOf("feature.goal"), cleaned.pluginManager.installed().map { it.id })
                assertFalse("core/goal" in cleaned.diagnostics().toolContributions)
                cleaned.pluginManager.setEnabled("feature.goal", true)
                assertTrue("core/goal" in cleaned.diagnostics().toolContributions)
            } finally { cleaned.close() }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun externalDependencyKeepsTheEntireFormerScheduleFeatureFunctional(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-retained-schedule-feature").toFile()
        val store = FilePluginCompositionStore(root)
        val aliases = NativeBuiltinAliases.filterValues { it == setOf("feature.schedule") }
        val entries = mapOf(
            "provider.schedules.history" to ScheduledTaskProviderPlugin::class.java,
            "consumer.tools.schedule" to ScheduledTaskToolConsumerPlugin::class.java,
            "consumer.schedules.application" to ScheduleDispatchPlugin::class.java,
        )
        val extra = nativePluginBundle(NativePluginServices(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            skillRuntime = null, conversationOverlayFactory = ai.meteor.kcode.plugin.api.ConversationOverlayFactory { null },
            settingsStore = null, historyRepository = null,
            packagedProviderIds = setOf("feature.schedule"),
        )).filterNot { it.descriptor.id == "provider.plugin-installations.platform" }
        lateinit var schedules: ai.meteor.kcode.chat.ScheduledTaskCoordinator
        val capture = kcodePlugin(PluginDescriptor("test.retained-schedule", "test", "test", emptySet()),
            org.cordis.plugin<Unit>(name = "capture-retained-schedule", inject = org.cordis.dependencies(ai.meteor.kcode.plugin.api.KcodeSchedules.Key)) { ctx, _ ->
                schedules = ctx.require(ai.meteor.kcode.plugin.api.KcodeSchedules.Key).coordinator
            }, Unit)
        try {
            val previous = entries.map { (id, entry) -> BundledPluginPackage(id,
                pack(root, id, "1.0.0", entry = entry.name, implementation = File(entry.protectionDomain.codeSource.location.toURI()))) }
            val consumer = pack(root, "example.consumer", "1.0.0", entry = PackageFixtureDependency::class.java.name,
                dependencies = listOf(PackageDependency("consumer.tools.schedule", "1.0.0")))
            val first = runtime(root, store, extra = extra + capture, bundled = previous)
            try { first.pluginManager.importPackages(listOf(consumer)) } finally { first.close() }
            val aggregate = BundledPluginPackage("feature.schedule", pack(root, "feature.schedule", "1.0.0",
                entry = ScheduleFeaturePlugin::class.java.name,
                implementation = File(ScheduleFeaturePlugin::class.java.protectionDomain.codeSource.location.toURI())))
            val restored = runtime(root, store, extra = extra + capture, bundled = listOf(aggregate), aliases = aliases)
            try {
                assertEquals(entries.keys + setOf("example.consumer", "feature.schedule"), restored.pluginManager.installed().map { it.id }.toSet())
                assertFalse(restored.pluginManager.installed().single { it.id == "feature.schedule" }.enabled)
                assertTrue("core/schedule" in restored.diagnostics().toolContributions)
                val session = requireNotNull(schedules.sessionFor(42, "Retained Schedule"))
                val task = session.create("legacy", "Keep the legacy feature functional", 60, null, null)
                assertEquals(task.taskId, session.list().single().taskId)
                restored.pluginManager.uninstall("example.consumer")
            } finally { restored.close() }
            val cleaned = runtime(root, store, extra = extra + capture, bundled = listOf(aggregate), aliases = aliases)
            try {
                assertEquals(listOf("feature.schedule"), cleaned.pluginManager.installed().map { it.id })
                assertFalse("core/schedule" in cleaned.diagnostics().toolContributions)
                cleaned.pluginManager.setEnabled("feature.schedule", true)
                assertTrue("core/schedule" in cleaned.diagnostics().toolContributions)
            } finally { cleaned.close() }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun featureAggregationPreservesDisabledChoiceAndFailedMigrationCommit(): Unit = runBlocking {
        for (feature in listOf("feature.goal", "feature.schedule", "feature.subagents", "feature.localization", "feature.markdown", "feature.conversation-export")) {
            val root = Files.createTempDirectory("kcode-feature-package-migration").toFile()
            val store = PackageFailingStore()
            val aliases = NativeBuiltinAliases.filterValues { it == setOf(feature) }
            try {
                val previous = aliases.keys.map { id ->
                    BundledPluginPackage(id, pack(root, id, "1.0.0", entry = PackageFixtureDependency::class.java.name))
                }
                val first = runtime(root, store, bundled = previous)
                try { first.pluginManager.setEnabled(aliases.keys.last(), false) } finally { first.close() }
                val committed = store.snapshot
                val aggregate = BundledPluginPackage(feature,
                    pack(root, feature, "1.0.0", entry = PackageFixtureDependency::class.java.name))
                store.failNext = true
                assertFailsWith<java.io.IOException> { runtime(root, store, bundled = listOf(aggregate), aliases = aliases) }
                assertEquals(committed, store.snapshot)
                val restored = runtime(root, store, bundled = listOf(aggregate), aliases = aliases)
                try {
                    assertEquals(listOf(feature), restored.pluginManager.installed().map { it.id })
                    assertFalse(restored.pluginManager.installed().single().enabled)
                    assertEquals(setOf(feature), store.snapshot.bundledPackages.keys)
                    restored.pluginManager.setEnabled(feature, true)
                    assertTrue(restored.pluginManager.installed().single().enabled)
                } finally { restored.close() }
            } finally { root.deleteRecursively() }
        }
    }

    @Test
    fun explicitBundledPackagesPersistWithoutDefaultProductOrManualInstallationProvider(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-no-default-product").toFile()
        val store = FilePluginCompositionStore(root)
        try {
            val release = pack(root, "example.provider", "1.0.0")
            val first = runtime(root, store, bundled = listOf(BundledPluginPackage("example.provider", release)))
            try {
                assertEquals("private:first:default", reply(first))
                assertFalse(first.diagnostics().plugins.any { it.id == "core.tools" || it.id == "provider.ui.compose" })
                first.pluginManager.setEnabled("example.provider", false)
            } finally { first.close() }
            val restarted = runtime(root, store, bundled = listOf(BundledPluginPackage("example.provider", release)))
            try {
                assertFalse(restarted.pluginManager.installed().single().enabled)
                restarted.pluginManager.setEnabled("example.provider", true)
                assertEquals("private:first:default", reply(restarted))
            } finally { restarted.close() }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun retiringAliasesPreserveTransitivePackageDependenciesUntilConsumersAreRemoved(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-alias-dependencies").toFile()
        val store = FilePluginCompositionStore(root)
        try {
            val old = pack(root, "example.old", "1.0.0", entry = PackageFixtureDependency::class.java.name)
            val middle = pack(root, "example.middle", "1.0.0", entry = PackageFixtureDependency::class.java.name,
                dependencies = listOf(PackageDependency("example.old", "1.0.0")))
            val consumer = pack(root, "example.consumer", "1.0.0", entry = PackageFixtureDependency::class.java.name,
                dependencies = listOf(PackageDependency("example.middle", "1.0.0")))
            val aggregate = pack(root, "example.aggregate", "1.0.0", entry = PackageFixtureDependency::class.java.name)
            val first = runtime(root, store, bundled = listOf(BundledPluginPackage("example.old", old), BundledPluginPackage("example.middle", middle)))
            try { first.pluginManager.importPackages(listOf(consumer)) } finally { first.close() }
            val aliases = mapOf("example.old" to setOf("example.aggregate"), "example.middle" to setOf("example.aggregate"))
            val upgraded = runtime(root, store, bundled = listOf(BundledPluginPackage("example.aggregate", aggregate)), aliases = aliases)
            try {
                assertEquals(setOf("example.old", "example.middle", "example.consumer", "example.aggregate"), upgraded.pluginManager.installed().map { it.id }.toSet())
                assertFalse(upgraded.pluginManager.installed().single { it.id == "example.aggregate" }.enabled)
                assertTrue(upgraded.pluginManager.installed().filter { it.id != "example.aggregate" }.all { it.enabled })
                store.load().validate()
                upgraded.pluginManager.uninstall("example.consumer")
            } finally { upgraded.close() }
            val cleaned = runtime(root, store, bundled = listOf(BundledPluginPackage("example.aggregate", aggregate)), aliases = aliases)
            try {
                assertEquals(listOf("example.aggregate"), cleaned.pluginManager.installed().map { it.id })
                store.load().validate()
            } finally { cleaned.close() }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun failedStartupPreservesProviderErrorWhenSettleObservesItAgain(): Unit = runBlocking {
        val failure = IllegalStateException("provider startup failed")
        val failing = object : Plugin<Unit> {
            override val name = "failed-startup"
            override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
                throw failure
            }
        }
        val error = assertFailsWith<IllegalStateException> {
            KcodePluginRuntime.create(KcodePluginRuntimeConfig(
                interactionPolicy = InteractionPolicy(approver = ToolCallApprover { false }),
                bundle = listOf(kcodePlugin(
                    PluginDescriptor("test.failed-startup", "test", "test", emptySet()),
                    failing, Unit,
                )),
            ))
        }
        assertSame(failure, error)
    }

    @Test
    fun historicalBundledDescriptorIsReplacedBeforeAbiValidationAndUserPackagesStayRejected(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-bundled-sdk-upgrade").toFile()
        val store = FilePluginCompositionStore(root)
        try {
            val first = pack(root, "example.provider", "1.0.0")
            val second = pack(root, "example.provider", "2.0.0", resource = "new-sdk")
            runtime(root, store, bundled = listOf(BundledPluginPackage("example.provider", first))).close()
            val previous = store.load()
            val historical = previous.copy(external = previous.external.map { it.copy(apiVersion = CurrentPluginApiVersion - 1) })
            val manifest = File(root, "plugin-installations.json")
            manifest.writeText(Json.encodeToString(historical))
            assertEquals(CurrentPluginApiVersion - 1, store.load().external.single().apiVersion)
            assertFailsWith<IllegalArgumentException> { runtime(root, store) }
            assertEquals(historical, store.load())
            val upgraded = runtime(root, store, bundled = listOf(BundledPluginPackage("example.provider", second)))
            try {
                assertEquals(CurrentPluginApiVersion, upgraded.pluginManager.installed().single().apiVersion)
                assertEquals("private:new-sdk:default", reply(upgraded))
            } finally { upgraded.close() }
            assertEquals("2.0.0", store.load().external.single().version)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun bundledReleasesUpgradePreservingConfigurationAndDisabledStateAndRememberUninstall(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-bundled-upgrade").toFile()
        val store = FilePluginCompositionStore(root)
        try {
            val first = pack(root, "example.provider", "1.0.0")
            val second = pack(root, "example.provider", "2.0.0", resource = "second")
            val third = pack(root, "example.provider", "3.0.0", resource = "third")
            val runtime = runtime(root, store, bundled = listOf(BundledPluginPackage("example.provider", first.copy(configuration = StoredPluginConfiguration("string", JsonPrimitive("custom"))))))
            assertEquals("private:first:custom", reply(runtime))
            runtime.pluginManager.setEnabled("example.provider", false)
            runtime.close()
            val upgraded = runtime(root, store, bundled = listOf(BundledPluginPackage("example.provider", second)))
            assertEquals("2.0.0", upgraded.pluginManager.installed().single().version)
            assertFalse(upgraded.pluginManager.installed().single().enabled)
            upgraded.pluginManager.setEnabled("example.provider", true)
            assertEquals("private:second:custom", reply(upgraded))
            upgraded.pluginManager.uninstall("example.provider")
            upgraded.close()
            val restarted = runtime(root, store, bundled = listOf(BundledPluginPackage("example.provider", third)))
            try {
                assertTrue(restarted.pluginManager.installed().isEmpty())
                assertEquals(third.sha256, store.load().bundledPackages["example.provider"])
            } finally { restarted.close() }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun bundledStartupPreservesUserReplacementAndRejectsChangedReleaseOrFailedPublication(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-bundled-transaction").toFile()
        val store = PackageFailingStore()
        try {
            val first = pack(root, "example.provider", "1.0.0")
            val altered = pack(root, "example.provider", "1.0.0", resource = "altered")
            val second = pack(root, "example.provider", "2.0.0", resource = "second")
            val user = pack(root, "example.provider", "3.0.0", resource = "user")
            runtime(root, store, bundled = listOf(BundledPluginPackage("example.provider", first))).close()
            val before = store.snapshot
            assertFailsWith<IllegalArgumentException> { runtime(root, store, bundled = listOf(BundledPluginPackage("example.provider", altered))) }
            assertEquals(before, store.snapshot)
            store.failNext = true
            assertFailsWith<java.io.IOException> { runtime(root, store, bundled = listOf(BundledPluginPackage("example.provider", second))) }
            assertEquals(before, store.snapshot)
            val original = runtime(root, store, bundled = listOf(BundledPluginPackage("example.provider", first)))
            original.pluginManager.importPackages(listOf(user))
            original.close()
            val overridden = runtime(root, store, bundled = listOf(BundledPluginPackage("example.provider", second)))
            try {
                assertEquals("3.0.0", overridden.pluginManager.installed().single().version)
                assertEquals("private:user:default", reply(overridden))
            } finally { overridden.close() }
            assertFailsWith<IllegalArgumentException> { runtime(root, store, bundled = listOf(BundledPluginPackage("example.wrong", first))) }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun independentJarPackageLoadsResourcesUpdatesAndRestoresDisabledState() = runBlocking {
        val root = Files.createTempDirectory("kcode-package-runtime").toFile()
        val store = FilePluginCompositionStore(root)
        try {
            val first = pack(root, "example.provider", "1.0.0")
            val second = pack(root, "example.provider", "2.0.0", resource = "second")
            val runtime = runtime(root, store)
            runtime.pluginManager.importPackages(listOf(first))
            assertEquals("private:first:default", reply(runtime))
            val committed = runtime.pluginManager.installed().single()
            assertTrue(committed.packageInstallation != null)
            assertTrue(committed.dependencies.isEmpty())
            runtime.pluginManager.setEnabled(committed.id, false)
            runtime.pluginManager.importPackages(listOf(second))
            assertFalse(runtime.pluginManager.installed().single().enabled)
            runtime.close()

            val restored = runtime(root, store)
            try {
                assertEquals("2.0.0", restored.pluginManager.installed().single().version)
                assertFalse(restored.pluginManager.installed().single().enabled)
                restored.pluginManager.setEnabled(committed.id, true)
                assertEquals("private:second:default", reply(restored))
                restored.pluginManager.uninstall(committed.id)
                assertTrue(restored.pluginManager.installed().isEmpty())
            } finally { restored.close() }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun dependencySetPublicationIsAtomicAndEnableRemovalRulesAreEnforced() = runBlocking {
        val root = Files.createTempDirectory("kcode-package-transaction").toFile()
        val store = PackageFailingStore()
        try {
            val dependency = pack(root, "example.dependency", "1.0.0", entry = PackageFixtureDependency::class.java.name)
            val consumer = pack(root, "example.consumer", "1.0.0", dependencies = listOf(PackageDependency("example.dependency", "1.0.0")))
            val runtime = runtime(root, store)
            try {
                assertFailsWith<IllegalArgumentException> { runtime.pluginManager.importPackages(listOf(consumer)) }
                assertTrue(runtime.pluginManager.installed().isEmpty())
                store.failNext = true
                assertFailsWith<java.io.IOException> { runtime.pluginManager.importPackages(listOf(consumer, dependency)) }
                assertTrue(runtime.pluginManager.installed().isEmpty())
                assertTrue(store.snapshot.external.isEmpty())
                runtime.pluginManager.importPackages(listOf(consumer, dependency))
                assertEquals(listOf("example.dependency", "example.consumer"), runtime.pluginManager.installed().map { it.id })
                assertFailsWith<IllegalArgumentException> { runtime.pluginManager.uninstall("example.dependency") }
                assertFailsWith<IllegalArgumentException> { runtime.pluginManager.setEnabled("example.dependency", false) }
                runtime.pluginManager.applyChanges(PluginCompositionChange(enabled = mapOf("example.consumer" to false, "example.dependency" to false)))
                assertTrue(runtime.pluginManager.installed().all { !it.enabled })
                runtime.pluginManager.applyChanges(PluginCompositionChange(removals = setOf("example.consumer", "example.dependency")))
                assertTrue(runtime.pluginManager.installed().isEmpty())
            } finally { runtime.close() }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun failedSecondCandidateRestoresEntirePreviousComposition() = runBlocking {
        val root = Files.createTempDirectory("kcode-package-apply-failure").toFile()
        val store = PackageFailingStore()
        try {
            val first = pack(root, "example.provider", "1.0.0")
            val replacement = pack(root, "example.provider", "2.0.0", resource = "replacement")
            val broken = pack(root, "example.broken", "1.0.0", entry = PackageFixtureBroken::class.java.name)
            val runtime = runtime(root, store)
            try {
                runtime.pluginManager.importPackages(listOf(first))
                val committed = store.snapshot
                assertFailsWith<IllegalStateException> { runtime.pluginManager.importPackages(listOf(replacement, broken)) }
                assertEquals(committed, store.snapshot)
                assertEquals("1.0.0", runtime.pluginManager.installed().single().version)
                assertEquals("private:first:default", reply(runtime))
            } finally { runtime.close() }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun rejectsWrongPlatformAbiRepublishedReleaseAndRestartTampering() = runBlocking {
        val root = Files.createTempDirectory("kcode-package-rejection").toFile()
        val store = FilePluginCompositionStore(root)
        try {
            val first = pack(root, "example.provider", "1.0.0")
            val different = pack(root, "example.provider", "1.0.0", resource = "different")
            val wrongAbi = pack(root, "example.other", "1.0.0", runtimeAbi = "b".repeat(64))
            val runtime = runtime(root, store)
            runtime.pluginManager.importPackages(listOf(first))
            assertFailsWith<IllegalArgumentException> { runtime.pluginManager.importPackages(listOf(different)) }
            assertFailsWith<IllegalArgumentException> { runtime.pluginManager.importPackages(listOf(wrongAbi)) }
            val committed = runtime.pluginManager.installed().single()
            runtime.close()
            File(committed.artifactPath).writeBytes(byteArrayOf(1))
            assertFailsWith<IllegalArgumentException> { runtime(root, store) }
            assertEquals("1.0.0", store.load().external.single().version)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun stalePackageResolverRejectsCallsAfterWithdrawal(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-package-withdrawal").toFile()
        try {
            lateinit var resolver: PluginPackageResolver
            val capture = object : Plugin<Unit> {
                override val inject = org.cordis.dependencies(KcodePluginPackages.Key)
                override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) { resolver = ctx.require(KcodePluginPackages.Key).resolver }
            }
            val runtime = runtime(root, PackageFailingStore(), listOf(kcodePlugin(PluginDescriptor("example.capture", "test", "test", emptySet()), capture, Unit)))
            try {
                runtime.pluginManager.setEnabled("provider.plugin-packages.platform", false)
                assertFailsWith<IllegalStateException> { resolver.resolve(listOf(pack(root, "example.provider", "1.0.0")), emptyList()) }
            } finally { runtime.close() }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun cancellationDuringPreparationDoesNotPublishOrMutate(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-package-cancellation").toFile()
        val admitted = CompletableDeferred<Unit>()
        val store = PackageFailingStore()
        val provider = object : Plugin<Unit> {
            override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
                KcodePluginPackages(ctx, object : PluginPackageResolver {
                    override suspend fun resolve(imports: List<PluginPackageImport>, installed: List<DynamicPluginSpec>): List<DynamicPluginSpec> {
                        admitted.complete(Unit)
                        awaitCancellation()
                    }
                    override suspend fun verify(spec: DynamicPluginSpec) = Unit
                })
            }
        }
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            profile = KcodePluginProfile(includeDefaults = false),
            featurePlugins = listOf(
                kcodePlugin(PluginDescriptor("example.resolver", "test", "test", emptySet()), provider, Unit),
                kcodePlugin(PluginDescriptor("example.installations", "test", "test", emptySet()), PluginInstallationsProviderPlugin, store),
            ),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { context, loader, inventory -> DesktopDynamicPluginController(context, loader, inventory, root) },
        ))
        try {
            val before = store.snapshot
            val operation = launch { runtime.pluginManager.importPackages(listOf(PluginPackageImport("unused", "a".repeat(64)))) }
            admitted.await()
            operation.cancelAndJoin()
            assertTrue(runtime.pluginManager.installed().isEmpty())
            assertEquals(before, store.snapshot)
        } finally { runtime.close(); root.deleteRecursively() }
    }

    private suspend fun runtime(root: File, store: PluginCompositionStore, extra: List<KcodePluginMount> = emptyList(), bundled: List<BundledPluginPackage> = emptyList(), aliases: Map<String, Set<String>> = emptyMap()): KcodePluginRuntime = KcodePluginRuntime.create(
        KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            profile = KcodePluginProfile(includeDefaults = false),
            bundledPackages = bundled,
            builtinAliases = aliases,
            pluginCompositionStore = store,
            featurePlugins = listOf(
                kcodePlugin(PluginDescriptor("provider.plugin-packages.platform", "test", "test", emptySet()), NativePluginPackagesPlugin(root, desktopPackageHost(), abi), Unit),
            ) + extra,
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { context, loader, inventory -> DesktopDynamicPluginController(context, loader, inventory, root) },
        ),
    )

    private suspend fun reply(runtime: KcodePluginRuntime) = runtime.chatService.reply(ModelConfiguration(ModelProvider.DeepSeek, "test", "test", temperature = 0.6), emptyList(), "test")

    private fun pack(
        root: File,
        id: String,
        version: String,
        resource: String = "first",
        entry: String = PackageFixtureProvider::class.java.name,
        dependencies: List<PackageDependency> = emptyList(),
        runtimeAbi: String = abi,
        implementation: File? = null,
    ): PluginPackageImport {
        val source = File(root, "source-${java.util.UUID.randomUUID()}").also { it.mkdirs() }
        val jar = File(source, "plugin.jar")
        val classes = File(PackageFixtureProvider::class.java.protectionDomain.codeSource.location.toURI()).toPath()
        if (implementation != null) implementation.copyTo(jar) else JarOutputStream(jar.outputStream()).use { output ->
            Files.list(classes.resolve("ai/meteor/kcode/plugin")).use { paths ->
                paths.filter { it.fileName.toString().startsWith("PackageFixture") }.forEach { file ->
                    output.putNextEntry(JarEntry(classes.relativize(file).toString().replace('\\', '/')))
                    Files.copy(file, output)
                    output.closeEntry()
                }
            }
            output.putNextEntry(JarEntry("sample.txt"))
            output.write(resource.encodeToByteArray())
            output.closeEntry()
        }
        val manifest = PluginPackageManifest(
            id = id, version = version, dependencies = dependencies,
            variants = listOf(PackageVariant("desktop", listOf("windows", "linux", "macos").map { PackageTarget(it, listOf("arm", "x86")) }, PackageRuntime("jvm", entry, "17"), "plugin.jar", extensions = kcodeVariantExtension(runtimeAbi))),
            files = listOf(PackageFile("plugin.jar", jar.length(), packageFileSha256(jar))),
            extensions = kcodeConfigurationExtension(if (entry == PackageFixtureProvider::class.java.name) StoredPluginConfiguration("string", JsonPrimitive("default")) else StoredPluginConfiguration("unit")),
        )
        val target = File(root, "$id-$version-${java.util.UUID.randomUUID()}.kplugin")
        val digest = PluginPackageArchive().pack(manifest, source, target)
        return PluginPackageImport(target.absolutePath, digest)
    }
}

class PackageFixtureProvider : Plugin<String> {
    override val config = ConfigValidator<String> { require(it != "bad-config"); it }
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        check(javaClass.classLoader !== KcodeAgents::class.java.classLoader)
        val resource = requireNotNull(javaClass.classLoader.getResourceAsStream("sample.txt")).use { it.readBytes().decodeToString() }
        KcodeAgents(ctx, object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String) = "private:$resource:$config"
        })
    }
}

class PackageFixtureDependency : Plugin<Unit> {
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) = Unit
}

class PackageFixtureBroken : Plugin<Unit> {
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) { error("candidate failure") }
}

private class PackageFailingStore : PluginCompositionStore {
    var snapshot = PluginCompositionSnapshot()
    var failNext = false
    override suspend fun load() = snapshot
    override suspend fun save(snapshot: PluginCompositionSnapshot) {
        if (failNext) { failNext = false; throw java.io.IOException("publication failed") }
        this.snapshot = snapshot
    }
}
