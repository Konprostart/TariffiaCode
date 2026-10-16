package com.konprostart.tariffiacode.core.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The shape of a streamed delta, asserted rather than assumed.
 *
 * Every test before this checked that *something* came back. Nothing checked that it came back in a
 * shape a strict client can read - so a delta nested inside a delta shipped for a week without
 * complaint, because the one client in the loop is lenient and our own fakes never parsed the output.
 *
 * The assertion is on the exact key path, not on the text. A change that reintroduces the nesting
 * moves `type` one level too deep, and the text can still contain the right words, which is exactly
 * how this got through the first time.
 */
class AnthropicStreamShapeTest {
    /**
     * A fresh adapter per call, because the adapter carries per-turn stream state and the engine
     * builds one per attempt. Sharing one instance across calls would test a shape that never occurs.
     */
    private fun adapter() = OpenAiCompatibleAdapter()

    @Test
    fun `a text delta is not nested inside another delta`() {
        val event =
            adapter().parseStreamEvent("""data: {"choices":[{"delta":{"content":"Count"}}]}""")

        assertNotNull("a plain text delta must produce an event", event)
        val frame = firstFrame(event!!)

        val delta = frame["delta"]?.jsonObject
        assertNotNull("the frame must carry a delta, as Anthropic does: $frame", delta)
        assertEquals("text_delta", delta!!["type"]?.jsonPrimitive?.content)
        assertEquals("Count", delta["text"]?.jsonPrimitive?.content)

        assertFalse(
            "the delta must not contain another delta - that nesting is what a strict client rejects: $frame",
            delta.containsKey("delta"),
        )
    }

    @Test
    fun `a tool argument delta carries partial json at the top of the delta`() {
        val event =
            adapter().parseStreamEvent(
                """data: {"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"{\"a\":"}}]}}]}""",
            )

        assertNotNull(event)
        val delta = firstFrame(event!!)["delta"]?.jsonObject

        assertNotNull("a tool delta must produce a delta object: ${firstFrame(event)}", delta)
        assertEquals("input_json_delta", delta!!["type"]?.jsonPrimitive?.content)
        assertEquals("""{"a":""", delta["partial_json"]?.jsonPrimitive?.content)
        assertFalse(delta.containsKey("delta"))
    }

    @Test
    fun `the frame keeps the type and index Anthropic clients key off`() {
        val frame = firstFrame(adapter().parseStreamEvent("""data: {"choices":[{"delta":{"content":"x"}}]}""")!!)

        assertEquals("content_block_delta", frame["type"]?.jsonPrimitive?.content)
        assertNotNull("clients index into blocks by this", frame["index"])
    }

    /**
     * The first *delta* frame, skipping the turn and block that open it.
     *
     * A stream now opens with `message_start` and `content_block_start` before any delta exists -
     * see OpenAiStreamLifecycleTest, which asserts that sequence. This file is about what a delta
     * looks like, so it reads the delta rather than the frame that happens to come first.
     */
    private fun firstFrame(events: String): JsonObject {
        val decoder = Json
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
                        if (depth == 0) end = i + 1
                    }
                }
                if (end > 0) break
                i++
            }
            if (end < 0) break
            val obj = decoder.parseToJsonElement(events.substring(start, end)).jsonObject
            index = end
            if (obj["type"]?.jsonPrimitive?.content == "content_block_delta") return obj
        }
        throw AssertionError("no content_block_delta in: $events")
    }
}
