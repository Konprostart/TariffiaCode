package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [output] is captured verbatim from `agy models` run on a signed-in real device - the bare-slug
 * format of the 1.1.7 release, and the two-column `slug + label` format 1.1.17 prints. An earlier
 * version of this parser was built against the differently-formatted output of an unrelated locally
 * installed 1.1.1 build.
 */
class AntigravityModelsTest {
    private val output =
        """
        gemini-3.6-flash-high
        gemini-3.6-flash-medium
        gemini-3.6-flash-low
        gemini-3.5-flash-high
        gemini-3.5-flash-medium
        gemini-3.5-flash-low
        gemini-3.1-pro-high
        gemini-3.1-pro-low
        claude-sonnet-4-6
        claude-opus-4-6-thinking
        gpt-oss-120b-medium
        """.trimIndent()

    /** Verbatim shape of 1.1.17: one slug per line, followed by the CLI's own display label. */
    private val labelledOutput =
        """
        gemini-3.7-flash-low Gemini 3.7 Flash (Low)
        gemini-3.7-flash-medium Gemini 3.7 Flash
        gemini-3.7-flash-high Gemini 3.7 Flash (High)
        claude-opus-4-6-thinking Claude Opus 4.6 (Thinking)
        gpt-oss-120b-medium GPT-OSS 120B
        """.trimIndent()

    @Test
    fun `parses the real agy models output`() {
        val entries = AntigravityModels.parse(output)
        assertEquals(11, entries.size)
        assertEquals(AntigravityModels.Entry("gemini-3.6-flash", "high"), entries.first())
        assertEquals(AntigravityModels.Entry("gpt-oss-120b", "medium"), entries.last())
    }

    @Test
    fun `parses 1 1 17 two-column lines into slug and label`() {
        val entries = AntigravityModels.parse(labelledOutput)
        assertEquals(5, entries.size)
        assertEquals(
            AntigravityModels.Entry("gemini-3.7-flash", "low", "Gemini 3.7 Flash (Low)"),
            entries.first(),
        )
        assertEquals(
            AntigravityModels.Entry("claude-opus-4-6-thinking", null, "Claude Opus 4.6 (Thinking)"),
            entries[3],
        )
    }

    @Test
    fun `the picker names a model by its CLI label and ids it by its slug`() {
        val catalog = AntigravityModels.catalog(AntigravityModels.parse(labelledOutput))
        val provider = catalog.all.single()
        assertEquals(5, provider.models.size)
        assertEquals("Gemini 3.7 Flash (Low)", provider.models.getValue("gemini-3.7-flash-low").name)
        assertEquals("GPT-OSS 120B", provider.models.getValue("gpt-oss-120b-medium").name)
        assertEquals("gemini-3.7-flash-low", catalog.default[AntigravityModels.PROVIDER_ID])
    }

    @Test
    fun `a version number ending in a digit is not mistaken for a variant`() {
        val entry = AntigravityModels.parse("claude-sonnet-4-6").single()
        assertEquals(AntigravityModels.Entry("claude-sonnet-4-6", null), entry)
    }

    /** The picker lists whole CLI ids, effort included, so an effort can never be left unselected. */
    @Test
    fun `catalog lists one model per printed line`() {
        val catalog = AntigravityModels.catalog(AntigravityModels.parse(output))
        val provider = catalog.all.single()
        assertEquals(11, provider.models.size)
        assertTrue(provider.models.containsKey("gemini-3.6-flash-high"))
        assertTrue(provider.models.containsKey("gemini-3.1-pro-low"))
        assertTrue(provider.models.containsKey("claude-sonnet-4-6"))
        assertTrue(provider.models.values.all { it.variants.isEmpty() })
        assertEquals("gemini-3.6-flash-high", catalog.default[AntigravityModels.PROVIDER_ID])
    }

    /** `--model claude-opus-4-6-thinking` is accepted whole; `thinking` is not a `--effort` value. */
    @Test
    fun `a thinking suffix stays part of the model id`() {
        assertEquals(
            AntigravityModels.Entry("claude-opus-4-6-thinking", null),
            AntigravityModels.parse("claude-opus-4-6-thinking").single(),
        )
        val catalog = AntigravityModels.catalog(AntigravityModels.parse(output))
        assertTrue(catalog.all.single().models.containsKey("claude-opus-4-6-thinking"))
    }

    @Test
    fun `falls back to a single placeholder model when nothing was parsed`() {
        val catalog = AntigravityModels.catalog(emptyList())
        val provider = catalog.all.single()
        assertEquals(1, provider.models.size)
        assertTrue(provider.models.containsKey("default"))
    }

    @Test
    fun `a slug without a variant suffix has no variant`() {
        val entries = AntigravityModels.parse("some-custom-model\n")
        assertEquals(AntigravityModels.Entry("some-custom-model", null), entries.single())
    }

    /** `--model gemini-3.1-pro` alone is rejected by the CLI with "requires --effort". */
    @Test
    fun `an effort is sent through its own flag`() {
        // The picker id already carries the effort, so no separate variant is needed or trusted.
        assertEquals(listOf("--model", "gemini-3.1-pro", "--effort", "high"), AntigravityModels.cliArgs("gemini-3.1-pro-high", null))
        assertEquals(listOf("--model", "gpt-oss-120b", "--effort", "medium"), AntigravityModels.cliArgs("gpt-oss-120b-medium", null))
    }

    /** Adding `--effort` to a model that has none makes the CLI report a conflict. */
    @Test
    fun `a model with no effort is sent alone`() {
        assertEquals(listOf("--model", "claude-sonnet-4-6"), AntigravityModels.cliArgs("claude-sonnet-4-6", null))
        assertEquals(listOf("--model", "claude-opus-4-6-thinking"), AntigravityModels.cliArgs("claude-opus-4-6-thinking", null))
    }

    /**
     * Sessions recorded while 1.1.17's two-column output was parsed whole carry the display label
     * inside the stored id; only the leading slug may reach `--model`.
     */
    @Test
    fun `an id polluted with a display label still sends a clean slug`() {
        assertEquals(
            listOf("--model", "gemini-3.7-flash", "--effort", "high"),
            AntigravityModels.cliArgs("gemini-3.7-flash-high Gemini 3.7 Flash (High)", null),
        )
        assertEquals(
            listOf("--model", "gpt-oss-120b", "--effort", "medium"),
            AntigravityModels.cliArgs("gpt-oss-120b-medium GPT-OSS 120B", "low"),
        )
    }

    @Test
    fun `no model selected omits every flag`() {
        assertEquals(emptyList<String>(), AntigravityModels.cliArgs(null, null))
        assertEquals(emptyList<String>(), AntigravityModels.cliArgs("default", null))
    }
}
