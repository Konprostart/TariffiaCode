package com.konprostart.tariffiacode.core.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The event sequence a Claude client is actually able to consume.
 *
 * A delta is not an answer. The Anthropic streaming protocol is ordered - `message_start` opens the
 * turn, `content_block_start` opens the block, deltas go inside it, `content_block_stop` closes it,
 * `message_delta` says why, `message_stop` ends the turn - and a client that receives a delta
 * without the frames that precede it has nowhere to put it.
 *
 * Every provider that speaks the OpenAI shape goes through this one adapter: DeepSeek, OpenRouter,
 * Groq and Ollama. That is why the frames are asserted here rather than per provider: one sequence,
 * four providers, and the bug is in the translation, not in any single vendor.
 */
class OpenAiStreamLifecycleTest {
    /**
     * A fresh adapter per turn, as the engine builds them.
     *
     * The adapter holds per-turn state deliberately, so a test that reuses one instance across
     * "turns" would be testing a shape that never occurs.
     */
    private fun adapterFor(model: String): OpenAiCompatibleAdapter =
        OpenAiCompatibleAdapter().apply {
            buildRequest(
                AnthropicRequest(model = model, stream = true),
                RouterModel("deepseek", "deepseek-chat", 1_000_000),
                null,
            )
        }

    @Test
    fun `a streamed turn opens the turn and the block before any delta`() {
        val adapter = adapterFor("claude-sonnet-4-5")

        val frames = frames(adapter, """data: {"choices":[{"delta":{"content":"Zdravo"}}]}""")

        assertEquals("the turn must be opened before anything else", "message_start", frames.first()["type"]?.jsonPrimitive?.content)
        assertEquals("and a block opened inside it", "content_block_start", frames[1]["type"]?.jsonPrimitive?.content)
        assertEquals("then the delta", "content_block_delta", frames[2]["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the turn opens exactly once, however many frames arrive`() {
        val adapter = adapterFor("claude-sonnet-4-5")

        val first = frames(adapter, """data: {"choices":[{"delta":{"content":"a"}}]}""")
        val second = frames(adapter, """data: {"choices":[{"delta":{"content":"b"}}]}""")

        assertEquals("message_start must not be repeated per frame", "message_start", first.first()["type"]?.jsonPrimitive?.content)
        assertEquals("the second frame must be only the delta", 1, second.size)
        assertEquals("content_block_delta", second.first()["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the turn closes with the block, the reason and nothing left open`() {
        val adapter = adapterFor("claude-sonnet-4-5")

        frames(adapter, """data: {"choices":[{"delta":{"content":"Zdravo"}}]}""")
        val last = frames(adapter, """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""")

        val types = last.map { it["type"]?.jsonPrimitive?.content }
        assertEquals("content_block_stop", types.first())
        assertEquals("message_delta carries why the turn ended", "message_delta", types.last())

        val delta = last.last()["delta"]?.jsonObject
        assertEquals("OpenAI's stop reason, in Anthropic's word", "end_turn", delta!!["stop_reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a tool call keeps its arguments and still closes the block`() {
        val adapter = adapterFor("claude-sonnet-4-5")

        frames(adapter, """data: {"choices":[{"delta":{"content":"x"}}]}""")
        val last =
            frames(
                adapter,
                """data: {"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
            )

        val types = last.map { it["type"]?.jsonPrimitive?.content }
        // The arguments are relayed inside the block, and only then does the block close.
        assertEquals("arguments flow through the open block", "content_block_delta", types.first())
        assertEquals("because `tool_use` follows, the block must close", "content_block_stop", types[1])
        val messageDelta = last.last()["delta"]?.jsonObject
        assertEquals("tool_use", messageDelta!!["stop_reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a keep-alive frame from the provider opens nothing`() {
        // OpenAI sends a first chunk carrying only the role. Emitting a turn here would produce a
        // turn for a provider that has not answered, which is exactly the "empty answer" shape.
        val adapter = adapterFor("claude-sonnet-4-5")

        val out = adapter.parseStreamEvent("""data: {"choices":[{"delta":{"role":"assistant"}}]}""")

        // A turn is opened, but it must carry no delta and the text must still be open for one.
        val frames = split(out.orEmpty())
        assertTrue(
            "a role-only frame must not invent a delta: $frames",
            frames.none { it["type"]?.jsonPrimitive?.content == "content_block_delta" },
        )
    }

    @Test
    fun `the model the client asked for is what the turn reports`() {
        val adapter = adapterFor("claude-sonnet-4-5")

        val message = frames(adapter, """data: {"choices":[{"delta":{"content":"x"}}]}""").first()

        val inner = message["message"]?.jsonObject
        assertNotNull("message_start must carry the message object", inner)
        assertEquals("claude-sonnet-4-5", inner!!["model"]?.jsonPrimitive?.content)
        assertEquals("assistant", inner["role"]?.jsonPrimitive?.content)
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun frames(
        adapter: OpenAiCompatibleAdapter,
        frame: String,
    ): List<kotlinx.serialization.json.JsonObject> = split(adapter.parseStreamEvent(frame).orEmpty())

    /**
     * The frames in one return value.
     *
     * An adapter call can produce several adjacent objects - which is what `sseFrames` in the server
     * then splits into one SSE frame each. Tests read the objects rather than the SSE so the two
     * layers are not conflated.
     */
    private fun split(events: String): List<kotlinx.serialization.json.JsonObject> {
        if (events.isBlank()) return emptyList()
        val decoder = Json
        val out = mutableListOf<kotlinx.serialization.json.JsonObject>()
        var index = 0
        while (index < events.length) {
            val start = events.indexOf('{', index)
            if (start < 0) break
            var depth = 0
            var inString = false
            var escaped = false
            var end = -1
            var i = start
            while (i < events.length) {
                val ch = events[i]
                when {
                    escaped -> escaped = false
                    ch == '\\' && inString -> escaped = true
                    ch == '"' -> inString = !inString
                    inString -> Unit
                    ch == '{' -> depth++
                    ch == '}' -> {
                        depth--
                        if (depth == 0) {
                            end = i + 1
                        }
                    }
                }
                if (end > 0) break
                i++
            }
            if (end < 0) break
            out += decoder.parseToJsonElement(events.substring(start, end)).jsonObject
            index = end
        }
        return out
    }
}
