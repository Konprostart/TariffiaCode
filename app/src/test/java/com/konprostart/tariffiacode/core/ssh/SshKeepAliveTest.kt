package com.konprostart.tariffiacode.core.ssh

import org.apache.sshd.client.SshClient
import org.apache.sshd.common.CommonModuleProperties
import org.apache.sshd.common.session.SessionHeartbeatController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeepAliveTest {
    @Test
    fun `the heartbeat is enabled on the client before it starts`() {
        val client = SshClient()
        try {
            SshKeepAlive.enableOn(client)

            assertEquals(
                SessionHeartbeatController.HeartbeatType.IGNORE,
                CommonModuleProperties.SESSION_HEARTBEAT_TYPE.getRequired(client),
            )
            assertEquals(SshKeepAlive.interval, CommonModuleProperties.SESSION_HEARTBEAT_INTERVAL.getRequired(client))
        } finally {
            client.stop()
        }
    }

    @Test
    fun `the interval stays well inside a phone's NAT idle window`() {
        // Anything much longer risks the binding already having been dropped; much shorter only
        // costs battery and radio time.
        assertTrue(SshKeepAlive.interval.seconds in 10..60)
    }
}