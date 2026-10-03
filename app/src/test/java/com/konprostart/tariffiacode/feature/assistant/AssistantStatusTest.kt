package com.konprostart.tariffiacode.feature.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantStatusTest {
    @Test
    fun `matches full and short active service names only`() {
        val full = "com.example/com.example.AssistantService"
        val short = "com.example/.AssistantService"

        assertTrue(AssistantStatus.matchesConfiguredService(full, full, short))
        assertTrue(AssistantStatus.matchesConfiguredService(short, full, short))
        assertFalse(AssistantStatus.matchesConfiguredService("other/.AssistantService", full, short))
        assertFalse(AssistantStatus.matchesConfiguredService(null, full, short))
    }

    @Test
    fun `assistant role can be requested only on android 10 plus when available`() {
        assertFalse(AssistantStatus.canRequestAssistantRole(sdkInt = 28, roleAvailable = true))
        assertFalse(AssistantStatus.canRequestAssistantRole(sdkInt = 29, roleAvailable = false))
        assertTrue(AssistantStatus.canRequestAssistantRole(sdkInt = 29, roleAvailable = true))
        assertTrue(AssistantStatus.canRequestAssistantRole(sdkInt = 34, roleAvailable = true))
    }
}
