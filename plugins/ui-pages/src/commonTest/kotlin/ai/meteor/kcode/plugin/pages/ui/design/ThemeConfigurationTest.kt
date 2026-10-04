package ai.meteor.kcode.plugin.pages.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ThemeConfigurationTest {
    @Test
    fun partialDeploymentOverridesOnlySelectedRolesAndDesignValues() {
        val baseline = resolveThemeConfiguration(Unit)
        val selected = resolveThemeConfiguration(Json.parseToJsonElement("""{
            "colors":{"onSurface":"#123456"},
            "extendedColors":{"panel":"#AA112233"},
            "spacing":{"md":22}, "radius":{"control":19}, "size":{"touchTarget":64},
            "glass":{"blurRadius":6,"tintOpacity":0.5,"noiseFactor":0.1},
            "overlay":{"floatingSize":68,"sheetRadius":17}, "fontScale":1.25
        }"""))
        assertEquals(Color(0xFF123456), selected.colorScheme.onSurface)
        assertEquals(Color(0xAA112233), selected.extendedColors.panel)
        assertEquals(baseline.colorScheme.primary, selected.colorScheme.primary)
        assertEquals(baseline.designTokens.spacing.xs, selected.designTokens.spacing.xs)
        assertEquals(22.dp, selected.designTokens.spacing.md)
        assertEquals(19.dp, selected.designTokens.radius.control)
        assertEquals(64.dp, selected.designTokens.size.touchTarget)
        assertEquals(6.dp, selected.designTokens.glass.blurRadius)
        assertEquals(0.5f, selected.designTokens.glass.tintOpacity)
        assertEquals(0.1f, selected.designTokens.glass.noiseFactor)
        assertEquals(17.5.sp, selected.typography.bodyMedium.fontSize)
        assertEquals(68.dp, selected.designTokens.overlay.floatingSize)
        assertEquals(17.dp, selected.designTokens.overlay.sheetRadius)
    }

    @Test
    fun invalidDeploymentFailsBeforeAThemeCanBePublished() {
        for (configuration in listOf(
            """{"unknown":1}""",
            """{"colors":{"typo":"#123456"}}""",
            """{"colors":{"primary":"red"}}""",
            """{"spacing":{"md":-1}}""",
            """{"spacing":{"md":"22"}}""",
            """{"radius":null}""",
            """{"size":{"touchTarget":513}}""",
            """{"glass":{"tintOpacity":1.1}}""",
            """{"fontScale":0}""",
            """{"overlay":{"bubbleMaxWidth":200}}""",
        )) {
            assertFailsWith<Exception>(configuration) {
                resolveThemeConfiguration(Json.parseToJsonElement(configuration))
            }
        }
    }
}
