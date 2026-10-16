package com.konprostart.tariffiacode.core.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which entries survive table validation, and which are refused for naming the wrong vendor.
 *
 * A claude model id copied onto a provider that never knew that name is refused before any prompt
 * is sent, and the refusal repeated across a chain once hid behind long provider timeouts. Keeping
 * such an entry out of the table turns "no routing at all" into a named rejection the user can act
 * on instead of a string of identical upstream failures.
 */
class RouterConfigTableTest {
    @Test
    fun `a claude id on a non-anthropic provider is rejected`() {
        val table =
            """
            {"chains":[
              {"tier":"sonnet","models":[{"provider":"deepseek","model":"claude-sonnet-4-20250514","contextWindow":200000}]}
            ]}
            """.trimIndent()

        val result = RouterConfigTable.fromJson(table)

        assertTrue(
            "the rejection has to name the entry and the reason: ${result.rejected}",
            result.rejected.any { entry ->
                entry.contains("deepseek/claude-sonnet-4-20250514") && entry.contains("cannot run on provider")
            },
        )
        assertTrue("dropping the last entry also shuts the chain down: ${result.rejected}", result.rejected.any { it.contains("empty") })
        assertTrue("nothing servable is left, so the table must say so", !result.isUsable)
    }

    @Test
    fun `a claude id on anthropic is kept`() {
        val table =
            """
            {"chains":[
              {"tier":"sonnet","models":[{"provider":"anthropic","model":"claude-sonnet-4-20250514","contextWindow":200000}]}
            ]}
            """.trimIndent()

        val result = RouterConfigTable.fromJson(table)

        assertTrue(result.rejected.isEmpty())
        assertEquals(
            "claude-sonnet-4-20250514",
            result.config.chainFor(RouterTier.SONNET).single().model,
        )
    }

    @Test
    fun `an openrouter entry keeps its namespaced claude id`() {
        val table =
            """
            {"chains":[
              {"tier":"opus","models":[{"provider":"openrouter","model":"anthropic/claude-opus-4-20250514","contextWindow":200000}]}
            ]}
            """.trimIndent()

        val result = RouterConfigTable.fromJson(table)

        assertTrue(result.rejected.isEmpty())
        assertEquals(
            "anthropic/claude-opus-4-20250514",
            result.config.chainFor(RouterTier.OPUS).single().model,
        )
    }

    @Test
    fun `a bare claude id on openrouter is rejected even though openrouter hosts claude`() {
        val table =
            """
            {"chains":[
              {"tier":"sonnet","models":[{"provider":"openrouter","model":"claude-sonnet-4-20250514","contextWindow":200000}]}
            ]}
            """.trimIndent()

        val result = RouterConfigTable.fromJson(table)

        assertTrue(result.rejected.any { it.contains("cannot run on provider") })
        assertTrue(!result.isUsable)
    }

    @Test
    fun `chain ids that name no claude anywhere are left untouched`() {
        val table =
            """
            {"chains":[
              {"tier":"haiku","models":[{"provider":"groq","model":"llama-3.1-8b-instant","contextWindow":200000}]},
              {"tier":"sonnet","models":[{"provider":"deepseek","model":"deepseek-chat","contextWindow":200000}]},
              {"tier":"opus","models":[{"provider":"deepseek","model":"deepseek-reasoner","contextWindow":200000}]}
            ]}
            """.trimIndent()

        val result = RouterConfigTable.fromJson(table)

        assertTrue("no entry here belongs anywhere else: ${result.rejected}", result.rejected.isEmpty())
    }
}
