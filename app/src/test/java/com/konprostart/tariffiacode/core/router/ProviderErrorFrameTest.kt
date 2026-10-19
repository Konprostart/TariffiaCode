package com.konprostart.tariffiacode.core.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading a refusal out of a payload that arrived as a success.
 *
 * The shape of the check is the whole point: three vendors report a failure inside a 200 in three
 * different bodies, and a frame that is an answer must never be mistaken for one. A detector that
 * is too eager breaks every working turn, which is a far worse symptom than the one it prevents.
 */
class ProviderErrorFrameTest {
    private fun payload(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `an Anthropic error frame is read, with its type and message`() {
        val error =
            providerReportedError(
                payload("""{"type":"error","error":{"type":"rate_limit_error","message":"credit balance too low"}}"""),
            )

        assertEquals("rate_limit_error", error?.errorType)
        assertEquals("credit balance too low", error?.detail)
    }

    @Test
    fun `an OpenAI-shaped error body is read`() {
        val error =
            providerReportedError(
                payload("""{"error":{"message":"No credits left","code":"insufficient_quota"}}"""),
            )

        assertEquals("insufficient_quota", error?.errorType)
        assertEquals("No credits left", error?.detail)
    }

    @Test
    fun `a bare string error is read`() {
        assertEquals("something went wrong", providerReportedError(payload("""{"error":"something went wrong"}"""))?.detail)
    }

    @Test
    fun `a Gemini error object is read`() {
        val error =
            providerReportedError(
                payload("""{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}"""),
            )

        assertEquals("RESOURCE_EXHAUSTED", error?.errorType)
        assertEquals("Quota exceeded", error?.detail)
    }

    @Test
    fun `an ordinary frame is not an error`() {
        // Every one of these is a frame a healthy provider sends during a normal turn. Treating any
        // of them as a refusal would fail over on every turn that mentions the word "error".
        assertNull(
            providerReportedError(
                payload("""{"type":"content_block_delta","delta":{"type":"text_delta","text":"hi"}}"""),
            ),
        )
        assertNull(
            providerReportedError(
                payload("""{"choices":[{"delta":{"content":"hi"}}],"usage":{"prompt_tokens":3}}"""),
            ),
        )
        assertNull(providerReportedError(payload("""{"type":"message_start","message":{"model":"x"}}""")))
        assertNull(providerReportedError(null))
    }

    @Test
    fun `no healthy frame of any vendor is read as a refusal`() {
        // The dangerous direction of this check is inventing a failure: it would empty the chain on a
        // provider that is answering perfectly well, on every turn. Each of these is a frame that
        // arrives during a normal turn, so each of them has to read as an answer.
        val healthy =
            listOf(
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                """{"type":"message_stop"}""",
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tool_1","name":"read"}}""",
                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{"}}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":9,"completion_tokens":0}}""",
                """{"candidates":[{"content":{"parts":[{"text":"hi"}]},"finishReason":"STOP"}]}""",
                """{"promptFeedback":{"blockReason":"STOP"}}""",
                """{"usageMetadata":{"promptTokenCount":9}}""",
            )
        healthy.forEach { frame ->
            assertNull("a healthy frame must not be a refusal: $frame", providerReportedError(payload(frame)))
        }
    }

    @Test
    fun `an empty error member is not a refusal`() {
        // A gateway that pads its frames with `"error": null` is not refusing anything, and reading
        // that as a failure would fail over every turn it ever answers.
        assertNull(providerReportedError(payload("""{"error":null,"choices":[{"delta":{"content":"hi"}}]}""")))
        assertNull(providerReportedError(payload("""{"error":{},"choices":[{"delta":{"content":"hi"}}]}""")))
        assertNull(providerReportedError(payload("""{"error":"","choices":[{"delta":{"content":"hi"}}]}""")))
    }

    @Test
    fun `a bare error type with no error object is still a refusal`() {
        val error = providerReportedError(payload("""{"type":"error","message":"something broke"}"""))

        assertEquals("error", error?.errorType)
    }

    @Test
    fun `a very long vendor message is truncated rather than passed whole`() {
        val error = providerReportedError(payload("""{"error":{"message":"${"x".repeat(5_000)}"}}"""))

        assertEquals("a vendor must not be able to flood a log line", 300, error?.detail?.length)
    }

    @Test
    fun `a very long error type is truncated too`() {
        // Both fields reach the log line and the client's error event, and a vendor that puts a novel
        // in its `code` must not be able to flood either.
        val error = providerReportedError(payload("""{"error":{"code":"${"x".repeat(5_000)}","message":"boom"}}"""))

        assertEquals(300, error?.errorType?.length)
        assertEquals("the reason itself is still readable", "boom", error?.detail)
    }

    @Test
    fun `the description names the provider and the type`() {
        val described = ProviderReportedError("rate_limit_error", "credit balance too low").describe("anthropic")

        assertEquals("anthropic reported an error: rate_limit_error: credit balance too low", described)
    }
}
