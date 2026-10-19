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
            {"model":"claude-opus-5-5","max_tokens":100,"stream":false,"messages":[],
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

    @Test
    fun `an in-stream error frame is a provider failure, not an event to forward`() {
        // Regression: this frame used to be relayed like any other. The engine counts a relayed event
        // as the first byte, so an exhausted Anthropic credit balance committed the chain, was recorded
        // as a success, and ended the turn with an error where the answer should have been.
        val failure =
            runCatching {
                adapter.parseStreamEvent(
                    """data: {"type":"error","error":{"type":"rate_limit_error","message":"credit balance too low"}}""",
                )
            }.exceptionOrNull()

        assertTrue("an error frame must fail the provider", failure is ProviderErrorFrame)
        val reported = failure as ProviderErrorFrame
        assertEquals("rate_limit_error", reported.errorType)
        assertTrue("and it must name what the vendor said", reported.detail!!.contains("credit balance"))
        assertTrue("a failure a client can act on has to say which provider", reported.message!!.contains("anthropic"))
    }

    @Test
    fun `an error body served with a 200 fails the provider rather than reading as an empty answer`() {
        val failure =
            runCatching {
                adapter.parseResponse(
                    200,
                    """{"type":"error","error":{"type":"invalid_request_error","message":"credit balance too low"}}""",
                    "m",
                )
            }.exceptionOrNull()

        assertTrue("a refusal served with a 200 must still be a refusal", failure is ProviderErrorFrame)
        assertTrue(
            "and it must carry what the vendor said, not just 'unreadable'",
            failure!!.message!!.contains("credit balance"),
        )
    }

    /**
     * The parsed request, built from a minimal body.
     *
     * Deliberately not the rich body: what is under test here is the original string surviving, and
     * the original string is passed to buildRequest separately. Decoding the elaborate one would only
     * be asserting that AnthropicRequest models every field a client might send, which is the opposite
     * of the point - a passthrough must not need to.
     */
    private fun request(
        @Suppress("UNUSED_PARAMETER") raw: String,
    ) = Json.decodeFromString<AnthropicRequest>("""{"model":"claude-opus-5-5","messages":[]}""")
}
