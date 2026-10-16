package com.konprostart.tariffiacode.core.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A turn that dies must say where it died.
 *
 * The client-facing diagnosis of a failed chain is an error event; the diagnosis a person can act
 * on is the walk itself: which providers were tried, in which order, and what each one said when it
 * gave up. These lines go to the service's failure file, one turn per file.
 *
 * The empty key store is deliberate: a chain with no credentials is a turn that fails on every
 * provider without touching a socket, so the walk is observable and deterministic.
 */
class RouterTurnTraceTest {
    @Test
    fun `a failed turn logs the chain walk with reasons`() {
        val lines = mutableListOf<String>()
        val config = RouterConfig(listOf(RouterTierChain(RouterTier.HAIKU, listOf(RouterModel("deepseek", "x", 1_000)))))
        val engine =
            RouterEngine(
                keys = { emptyMap() },
                config = { config },
                log = { lines += it },
            )

        engine.stream("""{"model":"claude-haiku-4-5","max_tokens":32,"messages":[]}""") {}

        assertTrue(lines.isNotEmpty())
        assertEquals("the walk opens with the turn: $lines", true, lines.first().startsWith("turn start:"))
        assertEquals("and closes with the failure: $lines", true, lines.last().startsWith("turn failed:"))
    }

    @Test
    fun `the non-streamed walk traces the same way`() {
        val lines = mutableListOf<String>()
        val config = RouterConfig(listOf(RouterTierChain(RouterTier.HAIKU, listOf(RouterModel("deepseek", "x", 1_000)))))
        val engine =
            RouterEngine(
                keys = { emptyMap() },
                config = { config },
                log = { lines += it },
            )

        engine.handle("""{"model":"claude-haiku-4-5","max_tokens":32,"messages":[]}""")

        assertEquals("fresh turn, first line: $lines", true, lines.first().startsWith("turn start:"))
        assertEquals("stream=false is part of the trace: $lines", true, lines.first().contains("stream=false"))
    }
}
