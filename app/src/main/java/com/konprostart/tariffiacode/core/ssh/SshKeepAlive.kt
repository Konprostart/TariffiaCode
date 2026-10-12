package com.konprostart.tariffiacode.core.ssh

import android.util.Log
import org.apache.sshd.common.CommonModuleProperties
import org.apache.sshd.common.session.SessionHeartbeatController
import java.time.Duration
import org.apache.sshd.client.SshClient as MinaApacheSshClient

/**
 * Keeps an SSH session alive across a phone's natural idleness.
 *
 * Mobile carriers and NAT gateways drop idle TCP bindings without telling either end. An
 * `opencode serve` tunnel (or a shell) left alone behind a locked screen simply stops working, and
 * today nothing notices: the request that finally fails is the one that times out, long after the
 * connection died. Apache MINA ships the fix — the session heartbeat — but defaults it off
 * (`HeartbeatType.NONE`, a zero interval), so every session this app opens opts in here.
 *
 * The properties are set on the [MinaApacheSshClient] before it starts. A client is the root of
 * MINA's property-resolver chain (session and connection service resolve through it), so this one
 * call covers every session the client will create.
 *
 * `HeartbeatType.IGNORE` is chosen over the reply-based type on purpose. `IGNORE` sends periodic
 * `SSH_MSG_IGNORE` packets that keep the path alive without asking for an answer, so a session that
 * is merely slow on a congested phone link is never torn down. This is keep-alive only — detecting
 * a dead session and reconnecting is a separate concern handled by the connection layer, not here.
 */
internal object SshKeepAlive {
    /**
     * Comfortably under the idle windows a phone typically sees (often 30–120 s on cellular), while
     * staying cheap: one tiny packet per interval.
     */
    val interval: Duration = Duration.ofSeconds(30)

    /**
     * Enables the heartbeat on [client]. Call before [MinaApacheSshClient.start] so the very first
     * session already carries it.
     */
    fun enableOn(client: MinaApacheSshClient) {
        runCatching {
            CommonModuleProperties.SESSION_HEARTBEAT_TYPE.set(
                client,
                SessionHeartbeatController.HeartbeatType.IGNORE,
            )
            CommonModuleProperties.SESSION_HEARTBEAT_INTERVAL.set(client, interval)
        }.onFailure {
            // Never fatal - keep-alive is an optimisation - but silent here would mean a session
            // that quietly ages out again, so say so.
            Log.w("SshKeepAlive", "SSH keep-alive not enabled", it)
        }
    }
}
