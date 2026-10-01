package com.yugahashimoto.andcode.feature.schedule

import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageError
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleRunOutcomeTest {
    @Test
    fun `an empty transcript is still running`() {
        assertNull(scheduleCompletionOf(emptyList()))
    }

    @Test
    fun `the prompt alone is still running`() {
        assertNull(scheduleCompletionOf(listOf(message(role = "user"))))
    }

    @Test
    fun `an unfinished reply is still running`() {
        assertNull(scheduleCompletionOf(listOf(message(role = "user"), message(id = "msg-2", completed = null))))
    }

    @Test
    fun `a finished reply completes the run`() {
        val outcome = scheduleCompletionOf(listOf(message(role = "user"), message(id = "msg-2", completed = 42L)))

        assertEquals(ScheduleCompletion.Completed, outcome)
    }

    @Test
    fun `only the newest turn decides the outcome`() {
        val messages =
            listOf(
                message(id = "msg-1", completed = 1L),
                message(id = "msg-2", role = "user"),
            )

        assertNull(scheduleCompletionOf(messages))
    }

    @Test
    fun `a failed reply fails the run and is announced`() {
        val outcome =
            scheduleCompletionOf(
                listOf(message(id = "msg-1", error = error("ApiError", "Free promotion has ended"))),
            )

        assertEquals(ScheduleCompletion.Failed("Free promotion has ended", silent = false), outcome)
    }

    @Test
    fun `a stopped reply fails the run quietly`() {
        val outcome =
            scheduleCompletionOf(
                listOf(message(id = "msg-1", error = error("MessageAbortedError", "aborted"))),
            )

        assertEquals(ScheduleCompletion.Failed("aborted", silent = true), outcome)
    }

    @Test
    fun `a failure without a message falls back to its name`() {
        val outcome = scheduleCompletionOf(listOf(message(id = "msg-1", error = OpenCodeMessageError(name = "UnknownError"))))

        assertEquals(ScheduleCompletion.Failed("UnknownError", silent = false), outcome)
    }

    private fun message(
        id: String = "msg-1",
        role: String = "assistant",
        completed: Long? = null,
        error: OpenCodeMessageError? = null,
    ) = OpenCodeMessage(
        info =
            OpenCodeMessageInfo(
                id = id,
                sessionId = "ses_1",
                role = role,
                time = OpenCodeTime(created = 1, completed = completed),
                error = error,
            ),
    )

    private fun error(
        name: String,
        message: String,
    ) = OpenCodeMessageError(name = name, data = mapOf("message" to JsonPrimitive(message)))
}
