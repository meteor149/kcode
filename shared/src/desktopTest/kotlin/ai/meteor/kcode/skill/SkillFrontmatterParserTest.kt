package ai.meteor.kcode.skill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SkillFrontmatterParserTest {
    @Test
    fun parsesAndNormalizesFrontmatter() {
        val parsed = SkillFrontmatterParser.parse(
            """
                ---
                name: "web-builder"
                description: >
                  Build and debug
                  mobile Web apps.
                ---
                # Instructions
            """.trimIndent(),
            fallbackName = "fallback",
        )

        assertEquals("web-builder", parsed.name)
        assertEquals("Build and debug mobile Web apps.", parsed.description)
    }

    @Test
    fun fallsBackToDirectoryName() {
        val parsed = SkillFrontmatterParser.parse("---\ndescription: Useful skill\n---", "directory-name")
        assertEquals("directory-name", parsed.name)
    }

    @Test
    fun rejectsMissingDescriptionAndOversizedName() {
        assertFailsWith<IllegalArgumentException> {
            SkillFrontmatterParser.parse("---\nname: incomplete\n---", "fallback")
        }
        assertFailsWith<IllegalArgumentException> {
            SkillFrontmatterParser.parse("---\nname: ${"x".repeat(65)}\ndescription: valid\n---", "fallback")
        }
    }

    @Test
    fun rejectsMissingDelimiter() {
        assertFailsWith<IllegalArgumentException> {
            SkillFrontmatterParser.parse("description: no delimiter", "fallback")
        }
    }
}
