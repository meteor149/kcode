package ai.meteor.kcode.plugin.profileui

import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.profiles.ProfileCompiler
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.cordis.loader.EntryOptions
import org.cordis.loader.IsolationRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileTreeFormTest {
    private val entries = listOf(
        ProfileEntry("group", "core.group", children = listOf(
            ProfileEntry("child", "core.group", children = emptyList()),
            ProfileEntry("leaf", "example.module"),
        )),
        ProfileEntry("outside", "example.module"),
    )
    private val modules = setOf("example.module", "example.alternate")
    private val compiler = ProfileCompiler()

    @Test
    fun configuredInsertionAndGroupInsertionCompileToTheRequestedHierarchy() {
        val insert = ProfileTreeForm(ProfileTreeAction.Insert, id = "inserted", module = "example.module",
            parent = "group", position = "0", configuration = "\"configured\"", configurationKind = "string")
            .operation(entries, modules)
        val group = ProfileTreeForm(ProfileTreeAction.Group, id = "new-group", position = "0").operation(entries, modules)
        val compiled = compile(insert, group)
        assertEquals(listOf("new-group", "group", "outside"), compiled.map { it.id })
        assertEquals(emptyList<EntryOptions>(), compiled.first().config)
        val children = compiled[1].config as List<*>
        assertEquals(listOf("inserted", "child", "leaf"), children.filterIsInstance<EntryOptions>().map { it.id })
        assertEquals(JsonPrimitive("configured"), (children.first() as EntryOptions).config)
        val default = ProfileTreeForm(ProfileTreeAction.Insert, id = "default", module = "example.module")
            .operation(entries, modules) as ProfileOperation.Insert
        assertNull(default.entries.single().config)
        val explicit = ProfileTreeForm(ProfileTreeAction.Insert, id = "unit", module = "example.module", configurationKind = "unit")
            .operation(entries, modules) as ProfileOperation.Insert
        assertEquals(JsonNull, explicit.entries.single().config)
        assertEquals("unit", explicit.entries.single().configurationKind)
    }

    @Test
    fun movementCanReparentAndReturnToRootButCannotCreateCycles() {
        val move = ProfileTreeForm(ProfileTreeAction.Move, target = "outside", parent = "child", position = "0")
            .operation(entries, modules)
        val compiled = compile(move)
        val nested = (((compiled.first().config as List<*>).first() as EntryOptions).config as List<*>)
        assertEquals("outside", (nested.single() as EntryOptions).id)
        val root = ProfileTreeForm(ProfileTreeAction.Move, target = "leaf", position = "0").operation(entries, modules)
        assertEquals(listOf("leaf", "group", "outside"), compile(root).map { it.id })
        listOf("group", "child").forEach { parent ->
            assertFailsWith<IllegalArgumentException> {
                ProfileTreeForm(ProfileTreeAction.Move, target = "group", parent = parent).operation(entries, modules)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ProfileTreeForm(ProfileTreeAction.Move, target = "leaf", parent = "outside").operation(entries, modules)
        }
    }

    @Test
    fun invalidIdentitiesModulesPositionsAndScalarCodecsFailBeforeSaving() {
        listOf("", " leaf ", "leaf").forEach { id ->
            assertFailsWith<IllegalArgumentException> {
                ProfileTreeForm(ProfileTreeAction.Insert, id = id, module = "example.module").operation(entries, modules)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ProfileTreeForm(ProfileTreeAction.Insert, id = "new", module = "unknown").operation(entries, modules)
        }
        assertFailsWith<IllegalArgumentException> {
            ProfileTreeForm(ProfileTreeAction.Move, target = "leaf", position = "1.5").operation(entries, modules)
        }
        assertFailsWith<IllegalArgumentException> {
            ProfileTreeForm(ProfileTreeAction.Configure, target = "leaf", configuration = "\"not a number\"", configurationKind = "int")
                .operation(entries, modules)
        }
        assertFailsWith<IllegalArgumentException> {
            ProfileTreeForm(ProfileTreeAction.Configure, target = "group", configuration = "{}").operation(entries, modules)
        }
        val configure = ProfileTreeForm(ProfileTreeAction.Configure, target = "leaf", configuration = "42", configurationKind = "int")
            .operation(entries, modules)
        assertEquals(JsonPrimitive(42), ((compile(configure).first().config as List<*>).last() as EntryOptions).config)
    }

    @Test
    fun moduleReplacementRetainsEntryIdentityAndGuardsTheExpectedModule() {
        val operation = ProfileTreeForm(ProfileTreeAction.Replace, target = "outside", module = "example.alternate")
            .operation(entries, modules) as ProfileOperation.Replace
        assertEquals("example.module", operation.expectedPackageId)
        val result = compile(operation).last()
        assertEquals("outside", result.id)
        assertEquals("example.alternate", result.name)
        val changed = entries.map { if (it.id == "outside") it.copy(packageId = "another.module") else it }
        assertFailsWith<IllegalArgumentException> {
            compiler.compile(ProfileDefinition(id = "tree", patches = listOf(ProfileOperation.Insert(changed), operation)), emptyList()).requireValid()
        }
    }

    @Test
    fun scopeFormsCompileLocalAndSharedRealmsWithoutChangingChildren() {
        val operation = ProfileTreeForm(ProfileTreeAction.Context, target = "group",
            inject = """{"service":true}""", intercept = """{"service":false}""",
            isolate = """{"service":null,"other":"named"}""").operation(entries, modules)
        val result = compile(operation).first()
        assertEquals(IsolationRule.Local, result.isolate!!["service"])
        assertEquals(IsolationRule.Shared("named"), result.isolate!!["other"])
        assertEquals(true, result.inject!!["service"])
        assertEquals(2, (result.config as List<*>).size)
        assertFailsWith<IllegalArgumentException> {
            ProfileTreeForm(ProfileTreeAction.Context, target = "leaf", isolate = """{"service":1}""").operation(entries, modules)
        }
        assertFailsWith<IllegalArgumentException> {
            ProfileTreeForm(ProfileTreeAction.Context, target = "leaf", inject = """{"":true}""").operation(entries, modules)
        }
    }

    @Test
    fun enableDisableAndRemovalKeepOtherInstancesIntact() {
        val disable = ProfileTreeForm(ProfileTreeAction.Disable, target = "outside").operation(entries, modules)
        assertEquals(true, compile(disable).last().disabled)
        val enable = ProfileTreeForm(ProfileTreeAction.Enable, target = "outside").operation(entries, modules)
        assertEquals(false, compile(disable, enable).last().disabled)
        val remove = ProfileTreeForm(ProfileTreeAction.Remove, target = "group").operation(entries, modules)
        assertEquals(listOf("outside"), compile(remove).map { it.id })
        assertTrue(entries.first().children!!.size == 2)
    }

    private fun compile(vararg operations: ProfileOperation): List<EntryOptions> = compiler.compile(
        ProfileDefinition(id = "tree", patches = listOf(ProfileOperation.Insert(entries)) + operations), emptyList(),
    ).requireValid().entries
}
