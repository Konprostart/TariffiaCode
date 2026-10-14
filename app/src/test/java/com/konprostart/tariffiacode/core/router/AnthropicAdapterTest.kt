package com.konprostart.tariffiacode.core.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anthropic as a provider: the one adapter that does not translate.
 *
 * The property worth testing is that nothing is lost. A passing-through provider understands
 * everything the client sent - cache control, thinking, effort, betas - and a router that rebuilds
 * the request from the fields it happens to model would quietly drop the rest, which is the kind of
 * bug that only appears as a slightly worse answer nobody can trace.
 */
class AnthropicAdapterTest {
    private val adapter = AnthropicAdapter("a-key")
    private val model = RouterModel("anthropic", "claude-sonnet-4-5", 200_000)

    @Test
    fun `it authenticates the way Anthropic does, not with a bearer token`() {
        assertEquals("a-key", adapter.extraHeaders()["x-api-key"])
        assertNotNull("Anthropic refuses a request with no version header", adapter.extraHeaders()["anthropic-version"])
        assertTrue("and it does not want a bearer token here", !adapter.extraHeaders().containsKey("Authorization"))
    }

    @Test
    fun `fields the router does not model survive the round trip`() {
        val raw =
            """
            {"model":"claude-opus-5-5","max_tokens":100,"stream":false,
            "system":[{"type":"text","text":"be brief","cache_control":{"type":"ephemeral"}}],
            "output_config":{"effort":"medium"},
            "thinking":{"type":"enabled","budget_tokens":1024}}
            """.trimIndent().replace("\n", "")

        val built = adapter.buildRequest(request(raw), model, raw)
        val sent = Json.parseToJsonElement(built.body).jsonObject

        assertEquals("claude-sonnet-4-5", sent["model"]?.jsonPrimitive?.content)
        assertNotNull("cache_control must survive", sent.toString().contains("cache_control"))
        assertNotNull("effort must survive", sent.toString().contains("effort"))
        assertNotNull("thinking must survive", sent.toString().contains("thinking"))
    }

    @Test
    fun `the model sent is the chain's, not the one the client asked for`() {
        val raw = """{"model":"claude-opus-5-5","messages":[]}"""
        val sent = Json.parseToJsonElement(adapter.buildRequest(request(raw), model, raw).body).jsonObject

        assertEquals(
            "the client's model is how the tier was chosen; the provider's is what to call",
            "claude-sonnet-4-5",
            sent["model"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `an already-Anthropic answer is read, not rebuilt`() {
        val body =
            """{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5",
            "content":[{"type":"text","text":"pong"}],"stop_reason":"end_turn",
            "usage":{"input_tokens":5,"output_tokens":2}}"""

        val parsed = adapter.parseResponse(200, body, "claude-sonnet-4-5")

        assertNotNull(parsed)
        assertEquals("pong", parsed!!.content.firstOrNull()?.text)
    }

    @Test
    fun `a refusal is null so the chain can move on`() {
        assertNull(adapter.parseResponse(429, """{"error":"rate limited"}""", "m"))
        assertNull(adapter.parseResponse(500, "", "m"))
    }

    @Test
    fun `a stream frame passes through as the event object`() {
        val frame = adapter.parseStreamEvent("""data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"hi"}}""")

        assertNotNull(frame)
        val event = Json.parseToJsonElement(frame!!).jsonObject
        assertEquals("content_block_delta", event["type"]?.jsonPrimitive?.content)
        assertTrue("and it must not be wrapped again by the adapter", !event.toString().contains("\"data\""))
    }

    @Test
    fun `an empty frame is nothing to forward, not a failure`() {
        assertNull(adapter.parseStreamEvent("data: [DONE]"))
        assertNull(adapter.parseStreamEvent("data: "))
    }

    private fun request(raw: String) = Json.decodeFromString<AnthropicRequest>(raw)
}
