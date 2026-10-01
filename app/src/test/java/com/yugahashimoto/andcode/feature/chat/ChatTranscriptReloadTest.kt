package com.yugahashimoto.andcode.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcript reload must never discard what only this client has: a turn interrupted by a
 * replacement prompt is finalized asynchronously, and some runtimes never persist its partial
 * output at all. These tests pin the retention rules of [mergeReloadedMessages] directly.
 */
class ChatTranscriptReloadTest {
    private fun assistant(
        id: String,
        text: String,
        timestamp: Long,
    ) = ChatMessage(
        id = id,
        isUser = false,
        parts = listOf(ChatPart.Text(id = "$id-t", text = text)),
        timestamp = timestamp,
    )

    private fun user(
        id: String,
        text: String,
        timestamp: Long,
    ) = ChatMessage(
        id = id,
        isUser = true,
        parts = listOf(ChatPart.Text(id = "$id-t", text = text)),
        timestamp = timestamp,
    )

    @Test
    fun `keeps a streamed bubble the transcript does not carry yet, ahead of later messages`() {
        val interrupted =
            assistant("m-aborted", "partial answer", timestamp = 200)
                .let { it.copy(isStreaming = false) }
        val reloaded =
            listOf(
                user("u1", "first", timestamp = 100),
                user("u2", "second", timestamp = 300),
            )

        val merged =
            mergeReloadedMessages(
                reloaded = reloaded,
                existing = listOf(interrupted),
                retainIds = setOf("m-aborted"),
            )

        assertEquals(listOf("u1", "m-aborted", "u2"), merged.map { it.id })
    }

    @Test
    fun `appends a retained bubble when nothing in the transcript is newer`() {
        val interrupted = assistant("m-aborted", "partial answer", timestamp = 500)
        val reloaded = listOf(user("u1", "first", timestamp = 100))

        val merged =
            mergeReloadedMessages(
                reloaded = reloaded,
                existing = listOf(interrupted),
                retainIds = setOf("m-aborted"),
            )

        assertEquals(listOf("u1", "m-aborted"), merged.map { it.id })
    }

    @Test
    fun `a bubble the transcript now carries under its own id is not doubled up`() {
        val streamedEarlier = assistant("m-aborted", "partial answer", timestamp = 200)
        val persisted = assistant("m-aborted", "final answer", timestamp = 200)

        val merged =
            mergeReloadedMessages(
                reloaded = listOf(persisted),
                existing = listOf(streamedEarlier),
                retainIds = setOf("m-aborted"),
            )

        assertEquals(listOf("m-aborted"), merged.map { it.id })
        assertEquals("final answer", merged.single().text)
    }

    @Test
    fun `ids outside the retain set are still dropped as before`() {
        val stale = assistant("m-stale", "gone", timestamp = 150)

        val merged =
            mergeReloadedMessages(
                reloaded = listOf(user("u1", "first", timestamp = 100)),
                existing = listOf(stale),
                retainIds = setOf("m-kept"),
            )

        assertEquals(listOf("u1"), merged.map { it.id })
    }

    @Test
    fun `an empty transcript leaves the screen untouched instead of wiping it`() {
        val onScreen =
            listOf(
                user("u1", "first", timestamp = 100),
                assistant("m-aborted", "partial answer", timestamp = 200),
            )

        val merged =
            mergeReloadedMessages(
                reloaded = emptyList(),
                existing = onScreen,
                retainIds = setOf("m-aborted"),
            )

        assertEquals(onScreen, merged)
    }

    @Test
    fun `retention never keeps user bubbles`() {
        val optimistic = user("u-local", "second", timestamp = 300)

        val merged =
            mergeReloadedMessages(
                reloaded = listOf(user("u1", "first", timestamp = 100)),
                existing = listOf(optimistic),
                retainIds = setOf("u-local"),
            )

        assertEquals(listOf("u1"), merged.map { it.id })
    }

    @Test
    fun `a retained bubble keeps its streamed content`() {
        val interrupted = assistant("m-aborted", "partial answer", timestamp = 200)
        assertTrue(interrupted.parts.isNotEmpty())

        val merged =
            mergeReloadedMessages(
                reloaded = listOf(user("u2", "second", timestamp = 300)),
                existing = listOf(interrupted),
                retainIds = setOf("m-aborted"),
            )

        assertEquals("partial answer", (merged.first { it.id == "m-aborted" }).text)
    }
}
